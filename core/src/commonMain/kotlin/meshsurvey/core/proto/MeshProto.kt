package meshsurvey.core.proto

/**
 * Decoding/encoding of the subset of the Meshtastic phone API this app uses.
 * Every field number here is documented in docs/API_CAPABILITIES.md.
 */
object Port {
    const val TEXT = 1
    const val POSITION = 3
    const val NODEINFO = 4
    const val ROUTING = 5
    const val ADMIN = 6
    const val TEXT_COMPRESSED = 7
    const val WAYPOINT = 8
    const val DETECTION_SENSOR = 10
    const val PAXCOUNTER = 34
    const val SERIAL = 64
    const val STORE_FORWARD = 65
    const val RANGE_TEST = 66
    const val TELEMETRY = 67
    const val TRACEROUTE = 70
    const val NEIGHBORINFO = 71
    const val MAP_REPORT = 73
    /** PRIVATE_APP: first port reserved for private applications; used for A/B test payloads. */
    const val PRIVATE = 256
}

const val BROADCAST: Long = 0xffffffffL

/** A packet as delivered to the client. Nullable = not present on the wire (unknown). */
class MeshPacketRx(
    val from: Long,
    val to: Long,
    val channel: Int?,
    val id: Long,
    val rxTime: Long?,
    val rssi: Int?,
    val snr: Float?,
    val hopLimit: Int?,
    val hopStart: Int?,
    val wantAck: Boolean,
    val viaMqtt: Boolean,
    val relayByte: Int?,
    val nextHopByte: Int?,
    val portnum: Int?,          // null => still encrypted (no key on this node)
    val payload: ByteArray?,
    val requestId: Long?,
    val encryptedLen: Int?,
    val pkiEncrypted: Boolean,
) {
    val decoded: Boolean get() = portnum != null
    /** hop_start - hop_limit; only defined when the sender's firmware reports hop_start. */
    val hopsAway: Int? get() {
        val hs = hopStart ?: return null
        val hl = hopLimit ?: 0
        return if (hs in 1..7 && hl <= hs) hs - hl else null
    }
}

/** A Position message as reported by a radio. Times in seconds since the epoch; ground speed in m/s. */
data class PositionInfo(
    val lat: Double,
    val lon: Double,
    val altitudeM: Int?,
    val timeS: Long?,
    /** 0 unset, 1 manual (fixed position set by the user), 2 internal GPS, 3 external GPS */
    val locationSource: Int,
    /** HDOP x 100, as sent by the firmware */
    val hdop: Int?,
    val groundSpeedMps: Int?,
    val satsInView: Int?,
    /** 1..32; fewer bits = deliberately blurred position. Absent/0 = not stated. */
    val precisionBits: Int?,
)

class NodeRecord(
    val num: Long,
    val userId: String?,
    val longName: String?,
    val shortName: String?,
    val hwModel: Int?,
    val role: Int?,
    val lat: Double?,
    val lon: Double?,
    val lastHeard: Long?,
    val snr: Float?,
    val hopsAway: Int?,
    val viaMqtt: Boolean,
    val metrics: DeviceMetricsInfo? = null,
)

class LoraConfigInfo(
    /** The exact Config.LoRaConfig bytes as received; used for byte-exact restore. */
    val raw: ByteArray,
    val usePreset: Boolean,
    val modemPreset: Int?,
    val region: Int?,
    val hopLimit: Int?,
    val txEnabled: Boolean,
    val txPower: Int?,
    val channelNum: Int?,
    val overrideFrequencyMhz: Float?,
    val bandwidth: Int? = null,
    val spreadFactor: Int? = null,
    val codingRate: Int? = null,
    val frequencyOffsetMhz: Float? = null,
) {
    val presetName: String? get() = if (!usePreset) "CUSTOM" else MeshEnums.preset(modemPreset ?: 0)
    val regionName: String? get() = region?.let { MeshEnums.region(it) } ?: "UNSET"
}

/** DeviceMetrics as reported by a radio about itself (percentages over the firmware's own averaging windows). */
class DeviceMetricsInfo(val batteryLevel: Int?, val voltage: Float?, val channelUtilization: Float?, val airUtilTx: Float?)

class DeviceMetadataInfo(val firmwareVersion: String?, val hwModel: Int?, val role: Int?)

class RoutingInfo(val errorReason: Int, val isRouteReply: Boolean, val isRouteRequest: Boolean)

sealed class FromRadioMsg {
    class MyInfo(val myNodeNum: Long) : FromRadioMsg()
    class Node(val node: NodeRecord, val raw: ByteArray? = null) : FromRadioMsg()
    class Packet(val packet: MeshPacketRx) : FromRadioMsg()
    class LoraConfig(val cfg: LoraConfigInfo) : FromRadioMsg()
    /** Channel name and role only. The PSK is deliberately not decoded or kept. */
    class Channel(val index: Int, val name: String, val role: Int) : FromRadioMsg()
    class Metadata(val meta: DeviceMetadataInfo) : FromRadioMsg()
    class ConfigComplete(val nonce: Long) : FromRadioMsg()
    class QueueStatus(val free: Int, val maxLen: Int, val meshPacketId: Long, val res: Int) : FromRadioMsg()
    object Rebooted : FromRadioMsg()
    class Log(val text: String) : FromRadioMsg()
    object Other : FromRadioMsg()
}

object MeshProto {
    fun decodeFromRadio(b: ByteArray): FromRadioMsg {
        val f = Wire.parse(b)
        f.f(2)?.bytes?.let { return FromRadioMsg.Packet(decodePacket(it)) }
        f.f(3)?.bytes?.let { return FromRadioMsg.MyInfo(Wire.parse(it).f(1)?.u32 ?: 0) }
        f.f(4)?.bytes?.let { return FromRadioMsg.Node(decodeNodeInfo(it), it) }
        f.f(5)?.bytes?.let { cfg ->
            val c = Wire.parse(cfg)
            c.f(6)?.bytes?.let { return FromRadioMsg.LoraConfig(decodeLora(it)) }
            return FromRadioMsg.Other
        }
        f.f(6)?.bytes?.let { return FromRadioMsg.Log(Wire.parse(it).f(1)?.str ?: "") }
        f.f(10)?.bytes?.let { ch ->
            val c = Wire.parse(ch)
            val name = c.f(2)?.bytes?.let { Wire.parse(it).f(3)?.str } ?: ""
            return FromRadioMsg.Channel(c.f(1)?.int ?: 0, name, c.f(3)?.int ?: 0)
        }
        f.f(7)?.let { return FromRadioMsg.ConfigComplete(it.u32) }
        f.f(8)?.let { return FromRadioMsg.Rebooted }
        f.f(11)?.bytes?.let { q ->
            val qs = Wire.parse(q)
            return FromRadioMsg.QueueStatus(qs.f(2)?.int ?: 0, qs.f(3)?.int ?: 0, qs.f(4)?.u32 ?: 0, qs.f(1)?.int ?: 0)
        }
        f.f(13)?.bytes?.let { m ->
            val md = Wire.parse(m)
            return FromRadioMsg.Metadata(DeviceMetadataInfo(md.f(1)?.str, md.f(9)?.int, md.f(7)?.int))
        }
        return FromRadioMsg.Other
    }

    fun decodePacket(b: ByteArray): MeshPacketRx {
        val f = Wire.parse(b)
        val decoded = f.f(4)?.bytes?.let { Wire.parse(it) }
        val rssi = f.f(12)?.int
        // proto3 omits 0.0. rx_snr and rx_rssi are set together by the radio driver, so an
        // absent SNR alongside a present RSSI means 0.0 dB; with neither, it is unknown.
        val snr = f.f(8)?.float ?: if (rssi != null) 0f else null
        return MeshPacketRx(
            from = f.f(1)?.u32 ?: 0,
            to = f.f(2)?.u32 ?: 0,
            channel = f.f(3)?.int,
            id = f.f(6)?.u32 ?: 0,
            rxTime = f.f(7)?.u32,
            rssi = rssi,
            snr = snr,
            hopLimit = f.f(9)?.int,
            hopStart = f.f(15)?.int,
            wantAck = f.f(10)?.bool ?: false,
            viaMqtt = f.f(14)?.bool ?: false,
            relayByte = f.f(19)?.int?.and(0xff),
            nextHopByte = f.f(18)?.int?.and(0xff),
            portnum = decoded?.let { it.f(1)?.int ?: 0 },
            payload = decoded?.f(2)?.bytes,
            requestId = decoded?.f(6)?.u32,
            encryptedLen = f.f(5)?.bytes?.size,
            pkiEncrypted = f.f(17)?.bool ?: false,
        )
    }

    /** NodeInfo.position (field 3) with quality fields, if present. */
    fun decodeNodeInfoPosition(b: ByteArray): PositionInfo? = Wire.parse(b).f(3)?.bytes?.let { decodePositionInfo(it) }

    fun decodeNodeInfo(b: ByteArray): NodeRecord {
        val f = Wire.parse(b)
        val u = f.f(2)?.bytes?.let { Wire.parse(it) }
        val pos = f.f(3)?.bytes?.let { decodePosition(it) }
        return NodeRecord(
            num = f.f(1)?.u32 ?: 0,
            userId = u?.f(1)?.str, longName = u?.f(2)?.str, shortName = u?.f(3)?.str,
            hwModel = u?.f(5)?.int, role = u?.let { it.f(7)?.int ?: 0 },
            lat = pos?.first, lon = pos?.second,
            lastHeard = f.f(5)?.u32, snr = f.f(4)?.float, hopsAway = f.f(9)?.int,
            viaMqtt = f.f(8)?.bool ?: false,
            metrics = f.f(6)?.bytes?.let { decodeDeviceMetrics(it) },
        )
    }

    /** DeviceMetrics { battery_level=1, voltage=2, channel_utilization=3, air_util_tx=4 } */
    fun decodeDeviceMetrics(b: ByteArray): DeviceMetricsInfo {
        val d = Wire.parse(b)
        return DeviceMetricsInfo(d.f(1)?.int, d.f(2)?.float, d.f(3)?.float, d.f(4)?.float)
    }

    /** Telemetry { device_metrics = 2 } payload, or null if this telemetry is another kind. */
    fun decodeTelemetryDeviceMetrics(payload: ByteArray): DeviceMetricsInfo? =
        Wire.parse(payload).f(2)?.bytes?.let { decodeDeviceMetrics(it) }

    /** Position.latitude_i / longitude_i are sfixed32 in 1e-7 degrees. (0,0) is treated as absent. */
    fun decodePosition(b: ByteArray): Pair<Double, Double>? {
        val p = Wire.parse(b)
        val la = p.f(1)?.int ?: 0
        val lo = p.f(2)?.int ?: 0
        return if (la == 0 && lo == 0) null else la * 1e-7 to lo * 1e-7
    }

    /**
     * Position with the fields needed to judge quality (mesh.proto): latitude_i 1, longitude_i 2,
     * altitude 3, time 4, location_source 5, HDOP 12, ground_speed 15, sats_in_view 19, precision_bits 23.
     */
    fun decodePositionInfo(b: ByteArray): PositionInfo? {
        val p = Wire.parse(b)
        val la = p.f(1)?.int ?: 0
        val lo = p.f(2)?.int ?: 0
        if (la == 0 && lo == 0) return null
        return PositionInfo(
            lat = la * 1e-7, lon = lo * 1e-7,
            altitudeM = p.f(3)?.int,
            timeS = p.f(4)?.u32?.takeIf { it > 0 },
            locationSource = p.f(5)?.int ?: 0,
            hdop = p.f(12)?.int,
            groundSpeedMps = p.f(15)?.int,
            satsInView = p.f(19)?.int,
            precisionBits = p.f(23)?.int,
        )
    }

    fun decodeUser(b: ByteArray): Triple<String?, String?, Int?> {
        val u = Wire.parse(b)
        // role is an enum whose default (CLIENT=0) proto3 omits; a present User without it means CLIENT
        return Triple(u.f(2)?.str, u.f(3)?.str, u.f(7)?.int ?: 0)
    }

    fun decodeLora(b: ByteArray): LoraConfigInfo {
        val l = Wire.parse(b)
        return LoraConfigInfo(
            raw = b.copyOf(),
            usePreset = l.f(1)?.bool ?: false,
            modemPreset = l.f(2)?.int,
            region = l.f(7)?.int,
            hopLimit = l.f(8)?.int,
            txEnabled = l.f(9)?.bool ?: false,
            txPower = l.f(10)?.int,
            channelNum = l.f(11)?.int,
            overrideFrequencyMhz = l.f(14)?.float?.takeIf { it > 0f },
            bandwidth = l.f(3)?.int, spreadFactor = l.f(4)?.int, codingRate = l.f(5)?.int,
            frequencyOffsetMhz = l.f(6)?.float,
        )
    }

    fun decodeRouting(b: ByteArray): RoutingInfo {
        val r = Wire.parse(b)
        return RoutingInfo(r.f(3)?.int ?: 0, r.f(2) != null, r.f(1) != null)
    }

    // ---------------- ToRadio ----------------

    /** ToRadio { want_config_id = 3 } */
    fun wantConfig(nonce: Long): ByteArray = ProtoWriter().uint32(3, nonce).toByteArray()

    /**
     * AdminMessage { set_config (34) : Config { lora (6) : <loraRaw> } }.
     * Firmware (AdminModule) skips the session-passkey check for messages from the local client
     * (from == 0) and reboots the node when preset/bandwidth fields change.
     */
    fun adminSetLora(loraRaw: ByteArray): ByteArray =
        ProtoWriter().messageAlways(34, ProtoWriter().messageAlways(6, loraRaw).toByteArray()).toByteArray()

    /** ToRadio { packet = MeshPacket { to = local node, decoded { portnum = ADMIN_APP, payload } } } */
    fun sendAdminLocal(myNode: Long, adminPayload: ByteArray, id: Long): ByteArray {
        val data = ProtoWriter().varint(1, Port.ADMIN.toLong()).bytes(2, adminPayload)
        val pkt = ProtoWriter().fixed32(2, myNode).message(4, data).fixed32(6, id)
        return ProtoWriter().message(1, pkt).toByteArray()
    }

    /**
     * ToRadio { heartbeat = 7 } — empty message, i.e. nonce 0. Must never carry nonce 1:
     * PhoneAPI treats nonce 1 as a request to broadcast this node's NodeInfo.
     */
    fun heartbeat(): ByteArray = ProtoWriter().messageAlways(7, ByteArray(0)).toByteArray()

    /** ToRadio { disconnect = 4 } */
    fun disconnect(): ByteArray = ProtoWriter().bool(4, true).toByteArray()

    /** ToRadio { packet = 1 : MeshPacket { to, channel, decoded{portnum,payload}, id, want_ack, hop_limit } } */
    fun sendPacket(to: Long, port: Int, payload: ByteArray, id: Long, wantAck: Boolean, channel: Int = 0, hopLimit: Int? = null): ByteArray {
        val data = ProtoWriter().varint(1, port.toLong()).bytes(2, payload)
        val pkt = ProtoWriter()
            .fixed32(2, to)
            .uint32(3, channel.toLong())
            .message(4, data)
            .fixed32(6, id)
            .apply { if (hopLimit != null) uint32(9, hopLimit.toLong()) }
            .bool(10, wantAck)
        return ProtoWriter().message(1, pkt).toByteArray()
    }
}

object MeshEnums {
    /** HardwareModel enum 0..100 (firmware 2.7.26 mesh.proto); higher or unknown values are shown by number. */
    private val hwModels = listOf("UNSET", "TLORA_V2", "TLORA_V1", "TLORA_V2_1_1P6", "TBEAM", "HELTEC_V2_0", "TBEAM_V0P7", "T_ECHO", "TLORA_V1_1P3", "RAK4631", "HELTEC_V2_1", "HELTEC_V1", "LILYGO_TBEAM_S3_CORE", "RAK11200", "NANO_G1", "TLORA_V2_1_1P8", "TLORA_T3_S3", "NANO_G1_EXPLORER", "NANO_G2_ULTRA", "LORA_TYPE", "WIPHONE", "WIO_WM1110", "RAK2560", "HELTEC_HRU_3601", "HELTEC_WIRELESS_BRIDGE", "STATION_G1", "RAK11310", "SENSELORA_RP2040", "SENSELORA_S3", "CANARYONE", "RP2040_LORA", "STATION_G2", "LORA_RELAY_V1", "T_ECHO_PLUS", "PPR", "GENIEBLOCKS", "NRF52_UNKNOWN", "PORTDUINO", "ANDROID_SIM", "DIY_V1", "NRF52840_PCA10059", "DR_DEV", "M5STACK", "HELTEC_V3", "HELTEC_WSL_V3", "BETAFPV_2400_TX", "BETAFPV_900_NANO_TX", "RPI_PICO", "HELTEC_WIRELESS_TRACKER", "HELTEC_WIRELESS_PAPER", "T_DECK", "T_WATCH_S3", "PICOMPUTER_S3", "HELTEC_HT62", "EBYTE_ESP32_S3", "ESP32_S3_PICO", "CHATTER_2", "HELTEC_WIRELESS_PAPER_V1_0", "HELTEC_WIRELESS_TRACKER_V1_0", "UNPHONE", "TD_LORAC", "CDEBYTE_EORA_S3", "TWC_MESH_V4", "NRF52_PROMICRO_DIY", "RADIOMASTER_900_BANDIT_NANO", "HELTEC_CAPSULE_SENSOR_V3", "HELTEC_VISION_MASTER_T190", "HELTEC_VISION_MASTER_E213", "HELTEC_VISION_MASTER_E290", "HELTEC_MESH_NODE_T114", "SENSECAP_INDICATOR", "TRACKER_T1000_E", "RAK3172", "WIO_E5", "RADIOMASTER_900_BANDIT", "ME25LS01_4Y10TD", "RP2040_FEATHER_RFM95", "M5STACK_COREBASIC", "M5STACK_CORE2", "RPI_PICO2", "M5STACK_CORES3", "SEEED_XIAO_S3", "MS24SF1", "TLORA_C6", "WISMESH_TAP", "ROUTASTIC", "MESH_TAB", "MESHLINK", "XIAO_NRF52_KIT", "THINKNODE_M1", "THINKNODE_M2", "T_ETH_ELITE", "HELTEC_SENSOR_HUB", "MUZI_BASE", "HELTEC_MESH_POCKET", "SEEED_SOLAR_NODE", "NOMADSTAR_METEOR_PRO", "CROWPANEL", "LINK_32", "SEEED_WIO_TRACKER_L1", "SEEED_WIO_TRACKER_L1_EINK")
    fun hwModelName(n: Int): String = hwModels.getOrNull(n) ?: "hardware #$n"

    private val PRESETS = mapOf(
        0 to "LONG_FAST", 1 to "LONG_SLOW", 2 to "VERY_LONG_SLOW", 3 to "MEDIUM_SLOW", 4 to "MEDIUM_FAST",
        5 to "SHORT_SLOW", 6 to "SHORT_FAST", 7 to "LONG_MODERATE", 8 to "SHORT_TURBO", 9 to "LONG_TURBO",
        10 to "LITE_FAST", 11 to "LITE_SLOW", 12 to "NARROW_FAST", 13 to "NARROW_SLOW", 14 to "TINY_FAST",
        15 to "TINY_SLOW", 16 to "MEDIUM_TURBO",
    )
    fun presetNumber(name: String): Int? = PRESETS.entries.firstOrNull { it.value == name }?.key
    val presetNames: List<String> get() = PRESETS.values.toList()
    private val REGIONS = listOf(
        "UNSET", "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH",
        "LORA_24", "UA_433", "UA_868", "MY_433", "MY_919", "SG_923", "PH_433", "PH_868", "PH_915",
        "ANZ_433", "KZ_433", "KZ_863", "NP_865", "BR_902", "ITU1_2M", "ITU2_2M", "EU_866", "EU_874", "EU_917",
        "EU_N_868", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM",
    )
    private val ROLES = listOf(
        "CLIENT", "CLIENT_MUTE", "ROUTER", "ROUTER_CLIENT", "REPEATER", "TRACKER", "SENSOR", "TAK",
        "CLIENT_HIDDEN", "LOST_AND_FOUND", "TAK_TRACKER", "ROUTER_LATE", "CLIENT_BASE",
    )
    private val ROUTING_ERRORS = mapOf(
        0 to "NONE", 1 to "NO_ROUTE", 2 to "GOT_NAK", 3 to "TIMEOUT", 4 to "NO_INTERFACE",
        5 to "MAX_RETRANSMIT", 6 to "NO_CHANNEL", 7 to "TOO_LARGE", 8 to "NO_RESPONSE",
        9 to "DUTY_CYCLE_LIMIT", 32 to "BAD_REQUEST", 33 to "NOT_AUTHORIZED", 34 to "PKI_FAILED",
        35 to "PKI_UNKNOWN_PUBKEY", 36 to "ADMIN_BAD_SESSION_KEY", 37 to "ADMIN_PUBLIC_KEY_UNAUTHORIZED",
        38 to "RATE_LIMIT_EXCEEDED",
    )
    fun preset(v: Int) = PRESETS[v] ?: "PRESET_$v"
    fun region(v: Int) = REGIONS.getOrNull(v) ?: "REGION_$v"
    fun role(v: Int) = ROLES.getOrNull(v) ?: "ROLE_$v"
    fun routingError(v: Int) = ROUTING_ERRORS[v] ?: "ERROR_$v"
    fun nodeId(num: Long) = "!" + num.toString(16).padStart(8, '0')
    fun parseNodeId(s: String): Long? {
        val t = s.trim().removePrefix("!").removePrefix("0x")
        return t.toLongOrNull(16)?.takeIf { it in 1..0xfffffffeL }
    }
}
