// meshsurvey-ble: a small Windows Bluetooth LE (and location) bridge for MeshSurvey Lite (MIT licence).
//
// Java on Windows has no Bluetooth LE API, so the desktop app starts this helper as a child
// process and talks to it over stdin/stdout, one text line per message. Only the Windows
// Runtime Bluetooth API (part of Windows 10/11) is used; there are no other dependencies.
// The C runtime is linked statically, so no Visual C++ redistributable is needed.
//
// The helper only moves Meshtastic phone-API bytes (ToRadio/FromRadio). It never creates
// packets itself; all transmission rules stay in the app (RadioClient / A/B test controller).
//
// Commands (one per line, from the app):
//   adapter                     -> "adapter ok" | "adapter off" | "adapter none" | "adapter nole"
//   scan <seconds>              -> "dev <address> <rssi|-> <paired 0|1> <name>"... then "scan-done"
//   pair <address> <pin>        -> "paired" | "error pair <reason>"
//   connect <address>           -> "connected <mtu>" | "error connect <reason>"
//   tx <hex>                    -> nothing on success | "error tx <reason>"
//   disconnect                  -> "disconnected"
//   location                    -> "location started" then "pos <lat> <lon> <accuracy_m> <altitude_m> <epoch_ms> <source>"...
//                                  (source: 0 cellular, 1 satellite, 2 Wi-Fi, 3 IP address, 4 unknown, 5 default, 6 obfuscated)
//                                  | "error location <reason>"; "locstatus <n>" on status changes
//   location-stop               -> "location stopped"
//   awake on|off                -> "awake on|off": keeps Windows from sleeping while on (SetThreadExecutionState on the
//                                  command thread, which lives as long as the helper); the screen may still turn off
//   quit                        -> exits
// Unsolicited lines: "rx <hex>" (one FromRadio message), "lost" (link dropped),
//   "log <text>" (diagnostics), "error <where> <reason>".
// The helper exits when stdin closes, so it never outlives the app.

#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Devices.Geolocation.h>
#include <winrt/Windows.Devices.Radios.h>
#include <winrt/Windows.Storage.Streams.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Devices::Enumeration;
using namespace Windows::Devices::Geolocation;
using namespace Windows::Storage::Streams;

// Meshtastic BLE service and characteristics (same UUIDs as the Android app's BleLink).
static const guid SERVICE   { 0x6ba1b218, 0x15a8, 0x461f, { 0x9f, 0xa8, 0x5d, 0xca, 0xe2, 0x73, 0xea, 0xfd } };
static const guid TO_RADIO  { 0xf75c76d2, 0x129e, 0x4dad, { 0xa1, 0xdd, 0x78, 0x66, 0x12, 0x44, 0x01, 0xe7 } };
static const guid FROM_RADIO{ 0x2c55e69e, 0x4993, 0x11ed, { 0xb8, 0x78, 0x02, 0x42, 0xac, 0x12, 0x00, 0x02 } };
static const guid FROM_NUM  { 0xed9da18c, 0xa800, 0x4f66, { 0xa6, 0x70, 0xaa, 0x75, 0x47, 0xe3, 0x44, 0x53 } };

static std::mutex outMx;
static void emit(const std::string& line) {
    std::lock_guard<std::mutex> g(outMx);
    std::fwrite(line.data(), 1, line.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

static std::string oneLine(std::string s) {
    for (auto& c : s) if (c == '\r' || c == '\n') c = ' ';
    return s;
}
static std::string err(const hresult_error& e) {
    std::ostringstream o;
    o << oneLine(to_string(e.message())) << " (0x" << std::hex << static_cast<uint32_t>(e.code()) << ")";
    return o.str();
}

static std::string toHex(const std::vector<uint8_t>& v) {
    static const char* d = "0123456789abcdef";
    std::string s; s.reserve(v.size() * 2);
    for (auto b : v) { s += d[b >> 4]; s += d[b & 15]; }
    return s;
}
static bool fromHex(const std::string& s, std::vector<uint8_t>& out) {
    if (s.size() % 2) return false;
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    out.clear();
    for (size_t i = 0; i < s.size(); i += 2) {
        int h = nib(s[i]), l = nib(s[i + 1]);
        if (h < 0 || l < 0) return false;
        out.push_back(static_cast<uint8_t>(h * 16 + l));
    }
    return true;
}

static std::string addrToString(uint64_t a) {
    char buf[18];
    std::snprintf(buf, sizeof buf, "%02X:%02X:%02X:%02X:%02X:%02X",
        (unsigned)(a >> 40) & 0xff, (unsigned)(a >> 32) & 0xff, (unsigned)(a >> 24) & 0xff,
        (unsigned)(a >> 16) & 0xff, (unsigned)(a >> 8) & 0xff, (unsigned)a & 0xff);
    return buf;
}
static bool parseAddr(const std::string& s, uint64_t& a) {
    a = 0; int n = 0;
    for (char c : s) {
        if (c == ':') continue;
        int v = (c >= '0' && c <= '9') ? c - '0' : (c >= 'a' && c <= 'f') ? c - 'a' + 10 : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
        if (v < 0) return false;
        a = (a << 4) | (uint64_t)v; n++;
    }
    return n == 12;
}

static std::vector<uint8_t> bufferBytes(const IBuffer& b) {
    std::vector<uint8_t> v(b.Length());
    if (!v.empty()) DataReader::FromBuffer(b).ReadBytes(v);
    return v;
}
static IBuffer bytesBuffer(const std::vector<uint8_t>& v) {
    DataWriter w;
    w.WriteBytes(array_view<uint8_t const>(v.data(), v.data() + v.size()));
    return w.DetachBuffer();
}

// ---------------------------------------------------------------- connection state
static std::mutex gattMx;                   // serialises every GATT operation
static BluetoothLEDevice device{ nullptr };
static GattSession session{ nullptr };
static GattCharacteristic toRadio{ nullptr }, fromRadio{ nullptr }, fromNum{ nullptr };
static event_token valueToken{}, statusToken{};
static std::atomic<bool> connected{ false };

static std::mutex drainMx;
static std::condition_variable drainCv;
static bool drainRequested = false;
static std::atomic<bool> quitting{ false };

static void requestDrain() {
    { std::lock_guard<std::mutex> g(drainMx); drainRequested = true; }
    drainCv.notify_one();
}

// Reads FromRadio until it returns an empty value. Runs on its own thread; woken by FromNum
// notifications, after each write, and every 2 s as a fallback poll.
static void drainLoop() {
    while (!quitting) {
        {
            std::unique_lock<std::mutex> lk(drainMx);
            drainCv.wait_for(lk, std::chrono::seconds(2), [] { return drainRequested || quitting.load(); });
            drainRequested = false;
        }
        if (quitting) break;
        if (!connected) continue;
        for (int i = 0; i < 1000 && connected && !quitting; i++) {   // bounded: a misbehaving device cannot spin us forever
            std::vector<uint8_t> v;
            try {
                std::lock_guard<std::mutex> g(gattMx);
                if (!fromRadio) break;
                auto r = fromRadio.ReadValueAsync(BluetoothCacheMode::Uncached).get();
                if (r.Status() != GattCommunicationStatus::Success) {
                    emit("error read status " + std::to_string((int)r.Status()) + " (if this persists, pair the radio: PIN on its screen, or 123456)");
                    break;
                }
                v = bufferBytes(r.Value());
            } catch (const hresult_error& e) { emit("error read " + err(e)); break; }
            if (v.empty()) break;
            emit("rx " + toHex(v));
        }
    }
}

static void closeDevice() {
    std::lock_guard<std::mutex> g(gattMx);
    connected = false;
    try {
        if (fromNum && valueToken.value) fromNum.ValueChanged(valueToken);
        if (device && statusToken.value) device.ConnectionStatusChanged(statusToken);
    } catch (...) {}
    valueToken = {}; statusToken = {};
    toRadio = nullptr; fromRadio = nullptr; fromNum = nullptr;
    if (session) { try { session.MaintainConnection(false); session.Close(); } catch (...) {} session = nullptr; }
    if (device) { try { device.Close(); } catch (...) {} device = nullptr; }
}

// ---------------------------------------------------------------- commands
static void cmdAdapter() {
    try {
        auto a = BluetoothAdapter::GetDefaultAsync().get();
        if (!a) { emit("adapter none"); return; }
        if (!a.IsLowEnergySupported()) { emit("adapter nole"); return; }
        auto r = a.GetRadioAsync().get();
        emit(r && r.State() == Windows::Devices::Radios::RadioState::On ? "adapter ok" : "adapter off");
    } catch (const hresult_error& e) { emit("error adapter " + err(e)); }
}

struct ScanState {
    std::mutex m;
    std::set<uint64_t> mesh;                 // addresses that advertised the Meshtastic service
    std::map<uint64_t, std::string> names;
    std::map<uint64_t, int16_t> rssi;
};

static void cmdScan(int seconds) {
    if (seconds < 1) seconds = 1;
    if (seconds > 30) seconds = 30;
    // Shared, so a late advertisement callback after Stop() never touches freed memory.
    auto st = std::make_shared<ScanState>();
    try {
        BluetoothLEAdvertisementWatcher w;
        w.ScanningMode(BluetoothLEScanningMode::Active);
        auto tok = w.Received([st](const BluetoothLEAdvertisementWatcher&, const BluetoothLEAdvertisementReceivedEventArgs& a) {
            std::lock_guard<std::mutex> g(st->m);
            uint64_t addr = a.BluetoothAddress();
            for (const auto& u : a.Advertisement().ServiceUuids()) if (u == SERVICE) st->mesh.insert(addr);
            auto n = to_string(a.Advertisement().LocalName());
            if (!n.empty()) st->names[addr] = oneLine(n);
            st->rssi[addr] = a.RawSignalStrengthInDBm();
        });
        w.Start();
        std::this_thread::sleep_for(std::chrono::seconds(seconds));
        w.Stop();
        w.Received(tok);
    } catch (const hresult_error& e) { emit("error scan " + err(e)); }

    std::set<uint64_t> reported;
    {
        std::lock_guard<std::mutex> g(st->m);
        for (auto addr : st->mesh) {
            emit("dev " + addrToString(addr) + " " + std::to_string(st->rssi[addr]) + " 0 " +
                 (st->names.count(addr) ? st->names[addr] : std::string("Meshtastic")));
            reported.insert(addr);
        }
    }
    // Paired devices too: a node that is already connected elsewhere may not be advertising.
    try {
        auto list = DeviceInformation::FindAllAsync(BluetoothLEDevice::GetDeviceSelectorFromPairingState(true)).get();
        for (const auto& di : list) {
            auto dev = BluetoothLEDevice::FromIdAsync(di.Id()).get();
            if (!dev) continue;
            uint64_t addr = dev.BluetoothAddress();
            if (reported.count(addr)) continue;
            emit("dev " + addrToString(addr) + " - 1 " + oneLine(to_string(di.Name())));
            dev.Close();
        }
    } catch (const hresult_error& e) { emit("log paired-device listing failed: " + err(e)); }
    emit("scan-done");
}

static void cmdPair(uint64_t addr, const std::string& pin) {
    try {
        auto dev = BluetoothLEDevice::FromBluetoothAddressAsync(addr).get();
        if (!dev) { emit("error pair device not found (is it switched on and in range?)"); return; }
        auto pairing = dev.DeviceInformation().Pairing();
        if (pairing.IsPaired()) { emit("paired"); dev.Close(); return; }
        auto custom = pairing.Custom();
        hstring hpin = to_hstring(pin);
        auto token = custom.PairingRequested([hpin](const DeviceInformationCustomPairing&, const DevicePairingRequestedEventArgs& a) {
            if (a.PairingKind() == DevicePairingKinds::ProvidePin) a.Accept(hpin); else a.Accept();
        });
        auto res = custom.PairAsync(DevicePairingKinds::ConfirmOnly | DevicePairingKinds::ProvidePin,
                                    DevicePairingProtectionLevel::Encryption).get();
        custom.PairingRequested(token);
        auto st = res.Status();
        if (st == DevicePairingResultStatus::Paired || st == DevicePairingResultStatus::AlreadyPaired) emit("paired");
        else emit("error pair status " + std::to_string((int)st) + " (wrong PIN, or the radio rejected pairing)");
        dev.Close();
    } catch (const hresult_error& e) { emit("error pair " + err(e)); }
}

static GattCharacteristic findChar(const GattDeviceService& s, const guid& id) {
    auto r = s.GetCharacteristicsForUuidAsync(id, BluetoothCacheMode::Uncached).get();
    if (r.Status() != GattCommunicationStatus::Success || r.Characteristics().Size() == 0) return nullptr;
    return r.Characteristics().GetAt(0);
}

static void cmdConnect(uint64_t addr) {
    closeDevice();
    std::string fail;
    uint32_t mtu = 0;
    try {
        std::lock_guard<std::mutex> g(gattMx);
        device = BluetoothLEDevice::FromBluetoothAddressAsync(addr).get();
        if (!device) fail = "device not found (is it switched on and in range?)";
        if (fail.empty()) {
            session = GattSession::FromDeviceIdAsync(device.BluetoothDeviceId()).get();
            if (session) { session.MaintainConnection(true); mtu = session.MaxPduSize(); }
            auto sr = device.GetGattServicesForUuidAsync(SERVICE, BluetoothCacheMode::Uncached).get();
            if (sr.Status() != GattCommunicationStatus::Success) fail = "could not read services (status " + std::to_string((int)sr.Status()) + ")";
            else if (sr.Services().Size() == 0) fail = "not a Meshtastic device (service not found)";
            else {
                auto svc = sr.Services().GetAt(0);
                toRadio = findChar(svc, TO_RADIO);
                fromRadio = findChar(svc, FROM_RADIO);
                fromNum = findChar(svc, FROM_NUM);
                if (!toRadio || !fromRadio || !fromNum) fail = "Meshtastic characteristics missing";
            }
        }
        if (fail.empty()) {
            auto st = fromNum.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue::Notify).get();
            if (st != GattCommunicationStatus::Success)
                emit("log could not subscribe to notifications (status " + std::to_string((int)st) + "); polling instead");
            valueToken = fromNum.ValueChanged([](const GattCharacteristic&, const GattValueChangedEventArgs&) { requestDrain(); });
            statusToken = device.ConnectionStatusChanged([](const BluetoothLEDevice& d, const IInspectable&) {
                if (d.ConnectionStatus() == BluetoothConnectionStatus::Disconnected && connected.exchange(false)) emit("lost");
            });
            connected = true;
        }
    } catch (const hresult_error& e) { fail = err(e); }
    if (!fail.empty()) { closeDevice(); emit("error connect " + fail); return; }
    emit("connected " + std::to_string(mtu));
}

static void cmdTx(const std::string& hex) {
    std::vector<uint8_t> v;
    if (!fromHex(hex, v)) { emit("error tx bad hex"); return; }
    try {
        std::lock_guard<std::mutex> g(gattMx);
        if (!connected || !toRadio) { emit("error tx not connected"); return; }
        auto r = toRadio.WriteValueWithResultAsync(bytesBuffer(v), GattWriteOption::WriteWithResponse).get();
        if (r.Status() != GattCommunicationStatus::Success) {
            emit("error tx status " + std::to_string((int)r.Status()) + " (if this persists, pair the radio)");
            return;
        }
    } catch (const hresult_error& e) { emit("error tx " + err(e)); return; }
    requestDrain();   // the radio usually has data for us after a write (e.g. the config download)
}

// ---------------------------------------------------------------- Windows location service
static Geolocator geo{ nullptr };
static event_token geoPosToken{}, geoStatusToken{};

static void cmdLocationStop() {
    if (geo) {
        try { geo.PositionChanged(geoPosToken); geo.StatusChanged(geoStatusToken); } catch (...) {}
        geo = nullptr;
    }
    geoPosToken = {}; geoStatusToken = {};
}

static void cmdLocation() {
    cmdLocationStop();
    try {
        auto access = Geolocator::RequestAccessAsync().get();
        if (access != GeolocationAccessStatus::Allowed) {
            emit("error location access denied: turn on Location services and 'Let desktop apps access your location' "
                 "in Windows Settings > Privacy & security > Location");
            return;
        }
        geo = Geolocator();
        geo.DesiredAccuracy(PositionAccuracy::High);
        geo.ReportInterval(1000);
        geoStatusToken = geo.StatusChanged([](const Geolocator&, const StatusChangedEventArgs& a) {
            emit("locstatus " + std::to_string((int)a.Status()));
        });
        geoPosToken = geo.PositionChanged([](const Geolocator&, const PositionChangedEventArgs& a) {
            try {
                auto c = a.Position().Coordinate();
                auto p = c.Point().Position();
                long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(winrt::clock::to_sys(c.Timestamp()).time_since_epoch()).count();
                char buf[200];
                std::snprintf(buf, sizeof buf, "pos %.7f %.7f %.1f %.1f %lld %d", p.Latitude, p.Longitude, c.Accuracy(), p.Altitude, ms, (int)c.PositionSource());
                emit(buf);
            } catch (const hresult_error& e) { emit("error location " + err(e)); }
        });
        emit("location started");
    } catch (const hresult_error& e) { emit("error location " + err(e)); }
}

int main() {
    init_apartment(apartment_type::multi_threaded);
    std::thread drainer(drainLoop);
    emit("ready meshsurvey-ble 1");
    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::string cmd; in >> cmd;
        if (cmd == "adapter") cmdAdapter();
        else if (cmd == "scan") { int s = 5; in >> s; cmdScan(s); }
        else if (cmd == "pair") {
            std::string a, pin; in >> a >> pin; uint64_t addr;
            if (!parseAddr(a, addr)) emit("error pair bad address"); else cmdPair(addr, pin);
        }
        else if (cmd == "connect") {
            std::string a; in >> a; uint64_t addr;
            if (!parseAddr(a, addr)) emit("error connect bad address"); else cmdConnect(addr);
        }
        else if (cmd == "tx") { std::string h; in >> h; cmdTx(h); }
        else if (cmd == "disconnect") { closeDevice(); emit("disconnected"); }
        else if (cmd == "location") cmdLocation();
        else if (cmd == "awake") {
            std::string v; in >> v;
            SetThreadExecutionState(v == "on" ? (ES_CONTINUOUS | ES_SYSTEM_REQUIRED) : ES_CONTINUOUS);
            emit(std::string("awake ") + (v == "on" ? "on" : "off"));
        }
        else if (cmd == "location-stop") { cmdLocationStop(); emit("location stopped"); }
        else if (cmd == "quit") break;
        else if (!cmd.empty()) emit("error unknown command " + cmd);
    }
    quitting = true;
    cmdLocationStop();
    drainCv.notify_all();
    closeDevice();
    drainer.join();
    return 0;
}
