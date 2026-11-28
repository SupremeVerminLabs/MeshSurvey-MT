package meshsurvey.core.radio

import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.DeviceMetadataInfo
import meshsurvey.core.proto.FromRadioMsg
import meshsurvey.core.proto.LoraConfigInfo
import meshsurvey.core.proto.MeshPacketRx
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.NodeRecord
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.PositionInfo
import meshsurvey.core.util.Clock
import kotlin.random.Random

enum class RadioState { DISCONNECTED, CONNECTING, DOWNLOADING_CONFIG, READY, FAILED }

/** What the app knows about the connected radio. Unknown fields stay null. */
data class RadioInfo(
    val myNodeNum: Long? = null,
    val firmware: String? = null,
    val hwModel: Int? = null,
    val preset: String? = null,
    val region: String? = null,
    val channelNum: Int? = null,
    val overrideFrequencyMhz: Double? = null,
    val txEnabled: Boolean? = null,
    val hopLimit: Int? = null,
    val linkKind: LinkKind? = null,
    val modemPreset: Int? = null,
    val usePreset: Boolean? = null,
    val regionCode: Int? = null,
    /** Exact LoRaConfig bytes from the radio (for safe, byte-exact restore of temporary preset changes). */
    val loraRaw: ByteArray? = null,
    /** Primary channel name ("" = default, i.e. the preset's display name is used for the slot hash). */
    val primaryChannelName: String? = null,
    val frequencyOffsetMhz: Double? = null,
    val bandwidth: Int? = null,
    val spreadFactor: Int? = null,
    val codingRate: Int? = null,
    /** Configured transmit power (LoRaConfig.tx_power, dBm; 0 = firmware default). Not a measured output. */
    val txPowerDbm: Int? = null,
    /** Firmware family, detected on connect. */
    val protocol: MeshProtocol = MeshProtocol.MESHTASTIC,
    /** MeshCore: the radio's advertised name and full public key (hex). */
    val nodeName: String? = null,
    val publicKeyHex: String? = null,
)

/** A packet as handed to the rest of the app, with the phone-side receive time. */
class ReceivedPacket(
    val packet: MeshPacketRx,
    val phoneTimeMs: Long,
    val monotonicMs: Long,
    /** true if received before the node finished sending its config (likely queued while disconnected) */
    val duringConfigDownload: Boolean,
)

interface RadioListener {
    fun onRadioState(state: RadioState, detail: String) {}
    fun onRadioInfo(info: RadioInfo) {}
    fun onNode(node: NodeRecord) {}
    fun onPacket(rx: ReceivedPacket) {}
    fun onDebugText(text: String) {}
    /** FromRadio.queueStatus: res 0 = accepted; free/maxLen = radio TX queue slots; meshPacketId = packet it refers to (0 = none). */
    fun onQueueStatus(res: Int, free: Int, maxLen: Int, meshPacketId: Long) {}
    /**
     * The connected radio's own position: from its NodeInfo during the config download, or from its own
     * position broadcasts (firmware loops locally sent broadcasts back to the phone API).
     */
    fun onOwnPosition(pos: PositionInfo, fromConfig: Boolean) {}
    /** MeshCore: one over-the-air packet the radio received, with its SNR/RSSI (PUSH_LOG_RX_DATA). */
    fun onMeshCoreRx(rx: MeshCore.RxPacket, phoneTimeMs: Long, duringSetup: Boolean) {}
    /** MeshCore: a node the radio knows (contact list) or just heard advertising itself. */
    fun onMeshCoreNode(publicKeyHex: String, type: Int, name: String?, lat: Double?, lon: Double?, fromContacts: Boolean) {}
    /** MeshCore: battery voltage reported by the radio. */
    fun onMeshCoreBattery(millivolts: Int) {}
}

/**
 * Meshtastic phone-API session over any [RadioLink]:
 * handshake (want_config_id), config/node download, packet delivery,
 * heartbeats for stream links, and sending (only used by explicit A/B tests).
 *
 * Not thread-safe: all calls (including link callbacks) must be marshalled to
 * one thread by the platform. [tick] must be called about once per second.
 */
class RadioClient(private val clock: Clock, private val random: Random = Random.Default) : RadioLink.Listener {

    var state: RadioState = RadioState.DISCONNECTED; private set
    var info: RadioInfo = RadioInfo(); private set
    val nodes = LinkedHashMap<Long, NodeRecord>()
    private val listeners = ArrayList<RadioListener>()
    private var link: RadioLink? = null
    private var nonce: Long = 0
    private var lastHeartbeatMs = 0L
    private var lastRxMs = 0L
    // Config download progress, shown to the user and used to retry a stalled handshake.
    private var configItems = 0
    private var configAttempts = 0
    private var lastWantConfigMs = 0L
    private var lastBootTextMs: Long? = null

    // Protocol probe (stream links): a MeshCore DEVICE_QUERY is sent first; Meshtastic radios ignore it.
    private var probing = false
    private var probeSentMs = 0L
    private var probeTries = 0
    // MeshCore session
    private var mcStage = McStage.NONE
    private var mcStageMs = 0L
    private var mcTries = 0
    private var mcLastBatteryMs = 0L
    private var mcContacts = 0
    private enum class McStage { NONE, SELF_INFO, CONTACTS, READY }

    val protocol: MeshProtocol get() = info.protocol

    companion object {
        /**
         * Firmware's "config only" want_config nonce (PhoneAPI SPECIAL_NONCE_ONLY_CONFIG, 2.6+): the radio sends
         * my_info, its own NodeInfo (full-precision position from its node database), metadata, channels and config,
         * but no other nodes. Local phone-API traffic only; nothing is transmitted over LoRa.
         */
        const val NONCE_ONLY_CONFIG = 69420L
    }

    /**
     * Returns a reason when the app must not transmit (RX-only mode); checked here, at the last step before the radio,
     * so no screen or feature can bypass it. Local admin messages that *restore* the user's configuration stay allowed.
     */
    var transmitBlock: () -> String? = { null }

    /** Transmissions this app asked the radio to make (test packets). Firmware's own traffic is not counted. */
    var appTransmissions = 0; private set

    fun addListener(l: RadioListener) { listeners += l }

    /**
     * Quietly re-reads the radio's own info (used by radio GPS to get its current full-precision position).
     * Does not change [state]; returns false if not connected and ready.
     */
    fun requestOwnInfo(): Boolean {
        if (state != RadioState.READY || protocol != MeshProtocol.MESHTASTIC) return false
        val l = link ?: return false
        return l.send(MeshProto.wantConfig(NONCE_ONLY_CONFIG))
    }
    fun removeListener(l: RadioListener) { listeners -= l }

    val isReady get() = state == RadioState.READY
    val linkKind get() = link?.kind

    fun connect(newLink: RadioLink) {
        disconnect()
        link = newLink
        nodes.clear()
        probing = false; probeTries = 0; mcStage = McStage.NONE
        info = RadioInfo(linkKind = newLink.kind)
        setState(RadioState.CONNECTING, newLink.description)
        newLink.start(this)
    }

    fun disconnect() {
        val l = link ?: return
        link = null
        if (protocol == MeshProtocol.MESHTASTIC && !probing) try { l.send(MeshProto.disconnect()) } catch (_: Exception) {}
        l.close()
        setState(RadioState.DISCONNECTED, "closed")
    }

    /** Periodic housekeeping (heartbeat keeps serial/TCP API sessions alive). */
    fun tick() {
        val l = link ?: return
        l.tick()
        val now = clock.monotonicMs()
        if (probing) { tickProbe(l, now); return }
        if (protocol == MeshProtocol.MESHCORE) { tickMeshCore(now); return }
        // Radio printed boot messages and went quiet without answering: it restarted after our request. Ask again.
        val boot = lastBootTextMs
        if (state == RadioState.DOWNLOADING_CONFIG && configItems == 0 && boot != null && boot > lastWantConfigMs &&
            now - boot >= 3_000 && configAttempts < 3) {
            lastBootTextMs = null
            requestConfig("radio restarted when the port opened; asking again")
            return
        }
        // Stalled handshake: nothing arriving for 15 s while waiting for config -> ask again (3 tries).
        if (state == RadioState.DOWNLOADING_CONFIG && now - lastWantConfigMs > 20_000 && now - lastRxMs > 15_000) {
            if (configAttempts >= 3) {
                setState(RadioState.FAILED, "radio stopped sending its configuration ($configItems items received). " +
                    "Close other apps connected to this radio and try again.")
                return
            }
            requestConfig("no response, asking again")
        }
        if (l.kind.isStream && state == RadioState.READY && now - lastHeartbeatMs > 300_000) {
            l.send(MeshProto.heartbeat())
            lastHeartbeatMs = now
        }
    }

    /**
     * Send a decoded packet. Returns the packet id, or null if not ready.
     * Only [meshsurvey.core.abtest.AbTestController] calls this, and only for
     * user-initiated tests to registered devices.
     */
    fun sendData(to: Long, port: Int, payload: ByteArray, wantAck: Boolean, channel: Int = 0, hopLimit: Int? = null): Long? {
        val l = link ?: return null
        if (state != RadioState.READY || protocol != MeshProtocol.MESHTASTIC) return null
        require(to != BROADCAST) { "broadcast sending is not permitted by this app" }
        require(port == Port.PRIVATE) { "only A/B test payloads may be sent" }
        transmitBlock()?.let { reason -> listeners.forEach { it.onDebugText("transmission refused: $reason") }; return null }
        val id = (random.nextLong(1, 0xffffffffL)) and 0xffffffffL
        return if (l.send(MeshProto.sendPacket(to, port, payload, id, wantAck, channel, hopLimit))) { appTransmissions++; id } else null
    }

    // ---------------- RadioLink.Listener ----------------

    override fun onLinkState(state: LinkState, detail: String) {
        when (state) {
            LinkState.CONNECTING -> setState(RadioState.CONNECTING, detail)
            LinkState.CONNECTED -> {
                configAttempts = 0
                lastHeartbeatMs = clock.monotonicMs()
                when (link?.detectedProtocol) {
                    MeshProtocol.MESHTASTIC -> requestConfig("connected; requesting configuration")
                    MeshProtocol.MESHCORE -> startMeshCore(null)
                    null -> startProbe()
                }
            }
            LinkState.DISCONNECTED -> setState(RadioState.DISCONNECTED, detail)
            LinkState.FAILED -> setState(RadioState.FAILED, detail)
        }
    }

    override fun onDebugText(text: String) {
        // Console text before any config arrives usually means the radio is booting: many ESP32 boards
        // restart when a USB port is opened. tick() asks for the config again once the boot output stops.
        if ((state == RadioState.DOWNLOADING_CONFIG && configItems == 0) || probing) lastBootTextMs = clock.monotonicMs()
        listeners.forEach { it.onDebugText(text) }
    }

    override fun onFromRadio(bytes: ByteArray) {
        lastRxMs = clock.monotonicMs()
        if (protocol != MeshProtocol.MESHTASTIC) return
        if (probing) {   // a Meshtastic frame while probing: it is a Meshtastic radio
            probing = false
            link?.setProtocol(MeshProtocol.MESHTASTIC)
            configAttempts = 0
            requestConfig("Meshtastic radio; requesting configuration")
            return
        }
        if (state == RadioState.DOWNLOADING_CONFIG) {
            configItems++
            if (configItems == 1 || configItems % 10 == 0) setState(RadioState.DOWNLOADING_CONFIG, "$configItems items received")
        }
        val msg = try { MeshProto.decodeFromRadio(bytes) } catch (e: Exception) {
            listeners.forEach { it.onDebugText("undecodable FromRadio (${bytes.size} B): ${e.message}") }
            return
        }
        when (msg) {
            is FromRadioMsg.MyInfo -> updateInfo(info.copy(myNodeNum = msg.myNodeNum))
            is FromRadioMsg.Node -> {
                nodes[msg.node.num] = msg.node; listeners.forEach { it.onNode(msg.node) }
                if (msg.node.num == info.myNodeNum) msg.raw?.let { raw ->
                    runCatching { MeshProto.decodeNodeInfoPosition(raw) }.getOrNull()?.let { p -> listeners.forEach { it.onOwnPosition(p, true) } }
                }
            }
            is FromRadioMsg.LoraConfig -> updateInfo(applyLora(msg.cfg))
            is FromRadioMsg.Metadata -> updateInfo(applyMeta(msg.meta))
            is FromRadioMsg.Channel -> if (msg.role == 1) updateInfo(info.copy(primaryChannelName = msg.name))
            is FromRadioMsg.ConfigComplete -> if (msg.nonce == nonce) setState(RadioState.READY, "config received ($configItems items)")
                else if (msg.nonce != NONCE_ONLY_CONFIG) listeners.forEach { it.onDebugText("ignored config_complete_id ${msg.nonce} (waiting for $nonce)") }
            is FromRadioMsg.Packet -> {
                val rx = ReceivedPacket(msg.packet, clock.wallMs(), clock.monotonicMs(), state != RadioState.READY)
                learnFromPacket(msg.packet)
                listeners.forEach { it.onPacket(rx) }
            }
            is FromRadioMsg.Rebooted -> {
                // Node rebooted: re-request config on the same link.
                configAttempts = 0
                requestConfig("radio rebooted")
            }
            is FromRadioMsg.Log -> listeners.forEach { it.onDebugText(msg.text) }
            is FromRadioMsg.QueueStatus -> listeners.forEach { it.onQueueStatus(msg.res, msg.free, msg.maxLen, msg.meshPacketId) }
            else -> {}
        }
    }

    private fun learnFromPacket(p: MeshPacketRx) {
        val payload = p.payload ?: return
        try {
            if (p.portnum == Port.NODEINFO) {
                val (ln, sn, role) = MeshProto.decodeUser(payload)
                val old = nodes[p.from]
                val n = NodeRecord(p.from, old?.userId, ln ?: old?.longName, sn ?: old?.shortName, old?.hwModel,
                    role ?: old?.role, old?.lat, old?.lon, p.rxTime, p.snr, p.hopsAway, p.viaMqtt)
                nodes[p.from] = n; listeners.forEach { it.onNode(n) }
            } else if (p.portnum == Port.POSITION) {
                if (p.from == info.myNodeNum) MeshProto.decodePositionInfo(payload)?.let { own -> listeners.forEach { it.onOwnPosition(own, false) } }
                val pos = MeshProto.decodePosition(payload) ?: return
                val old = nodes[p.from]
                val n = NodeRecord(p.from, old?.userId, old?.longName, old?.shortName, old?.hwModel, old?.role,
                    pos.first, pos.second, p.rxTime, p.snr, p.hopsAway, p.viaMqtt)
                nodes[p.from] = n; listeners.forEach { it.onNode(n) }
            }
        } catch (_: Exception) { /* malformed payload: ignore for node table */ }
    }

    private fun applyLora(c: LoraConfigInfo) = info.copy(
        preset = c.presetName, region = c.regionName, channelNum = c.channelNum,
        overrideFrequencyMhz = c.overrideFrequencyMhz?.toDouble(), txEnabled = c.txEnabled, hopLimit = c.hopLimit,
        modemPreset = c.modemPreset ?: 0, usePreset = c.usePreset, regionCode = c.region ?: 0, loraRaw = c.raw,
        frequencyOffsetMhz = c.frequencyOffsetMhz?.toDouble(), bandwidth = c.bandwidth, spreadFactor = c.spreadFactor, codingRate = c.codingRate,
        txPowerDbm = c.txPower,
    )

    /** Re-requests the full config (used to confirm a configuration change). */
    fun refreshConfig() {
        if (link == null || protocol != MeshProtocol.MESHTASTIC) return
        configAttempts = 0
        requestConfig("re-reading configuration")
    }

    private fun requestConfig(detail: String) {
        val l = link ?: return
        nonce = random.nextLong(1, 0x7fffffffL)
        configItems = 0
        lastBootTextMs = null
        configAttempts++
        lastWantConfigMs = clock.monotonicMs()
        setState(RadioState.DOWNLOADING_CONFIG, if (configAttempts > 1) "$detail (try $configAttempts of 3)" else detail)
        l.send(MeshProto.wantConfig(nonce))
    }

    /**
     * Sends an AdminMessage to the locally connected node only. Used exclusively by
     * [meshsurvey.core.preset.PresetManager] after explicit user approval.
     */
    fun sendAdminToLocalNode(adminPayload: ByteArray): Boolean {
        val l = link ?: return false
        val me = info.myNodeNum ?: return false
        if (state != RadioState.READY || protocol != MeshProtocol.MESHTASTIC) return false
        return l.send(MeshProto.sendAdminLocal(me, adminPayload, random.nextLong(1, 0xffffffffL)))
    }

    // ---------------- protocol probe ----------------

    private fun startProbe() {
        probing = true
        probeTries++
        probeSentMs = clock.monotonicMs()
        setState(RadioState.DOWNLOADING_CONFIG, "identifying the radio (MeshCore or Meshtastic)")
        link?.sendMeshCore(MeshCore.deviceQuery())
    }

    private fun tickProbe(l: RadioLink, now: Long) {
        val boot = lastBootTextMs
        // Console output: the board is probably restarting (many ESP32 boards reset when the port opens). Wait until
        // it has been quiet for 3 s, then ask once more.
        if (boot != null && now - boot < 3_000) return
        if (boot != null && boot >= probeSentMs && probeTries < 3) { lastBootTextMs = null; startProbe(); return }
        if (now - probeSentMs < 2_500) return
        probing = false
        lastBootTextMs = null
        l.setProtocol(MeshProtocol.MESHTASTIC)
        configAttempts = 0
        requestConfig("no MeshCore reply; requesting Meshtastic configuration")
    }

    // ---------------- MeshCore (listen-only) ----------------

    override fun onMeshCoreFrame(bytes: ByteArray) {
        lastRxMs = clock.monotonicMs()
        if (bytes.isEmpty()) return
        if (probing) {
            probing = false
            link?.setProtocol(MeshProtocol.MESHCORE)
            startMeshCore(bytes)
            return
        }
        if (protocol != MeshProtocol.MESHCORE) return
        handleMeshCore(bytes)
    }

    private fun sendMc(frame: ByteArray): Boolean {
        val code = frame.firstOrNull()?.toInt()?.and(0xff) ?: return false
        // Last line of defence: only local, non-transmitting requests ever reach a MeshCore radio from this app.
        check(code in MeshCore.ALLOWED_COMMANDS) { "MeshCore command $code is not permitted" }
        return link?.sendMeshCore(frame) == true
    }

    private fun startMeshCore(firstFrame: ByteArray?) {
        updateInfo(info.copy(protocol = MeshProtocol.MESHCORE))
        mcTries = 0
        mcContacts = 0
        firstFrame?.let { handleMeshCore(it) }
        mcRequestSelf()
    }

    private fun mcRequestSelf() {
        mcTries++
        mcStage = McStage.SELF_INFO
        mcStageMs = clock.monotonicMs()
        setState(RadioState.DOWNLOADING_CONFIG, "MeshCore radio; reading its settings" + if (mcTries > 1) " (try $mcTries of 3)" else "")
        sendMc(MeshCore.appStart())
        if (info.firmware == null) sendMc(MeshCore.deviceQuery())
    }

    private fun tickMeshCore(now: Long) {
        when (mcStage) {
            McStage.SELF_INFO -> if (now - mcStageMs > 8_000) {
                if (mcTries >= 3) { mcStage = McStage.NONE; setState(RadioState.FAILED, "MeshCore radio did not answer. Close the MeshCore app if it is connected, and try again.") }
                else mcRequestSelf()
            }
            McStage.CONTACTS -> if (now - mcStageMs > 15_000) mcReady("contact list incomplete ($mcContacts received)")
            McStage.READY -> if (now - mcLastBatteryMs >= 60_000) { mcLastBatteryMs = now; sendMc(MeshCore.getBattery()) }
            McStage.NONE -> {}
        }
    }

    private fun mcReady(detail: String) {
        mcStage = McStage.READY
        mcLastBatteryMs = clock.monotonicMs()
        sendMc(MeshCore.getBattery())
        setState(RadioState.READY, detail)
    }

    private fun handleMeshCore(f: ByteArray) {
        when (f[0].toInt() and 0xff) {
            MeshCore.RESP_SELF_INFO -> MeshCore.decodeSelfInfo(f)?.let { si ->
                val label = buildString {
                    append("MC ")
                    si.frequencyMhz?.let { append(trimNum(it, 3)) } ?: append("?")
                    si.bandwidthKhz?.let { append("/BW").append(trimNum(it, 2)) }
                    si.spreadingFactor?.let { append("/SF").append(it) }
                }
                updateInfo(info.copy(
                    myNodeNum = MeshCoreIds.fromPrefix(si.publicKey.copyOfRange(0, 4)), nodeName = si.name, publicKeyHex = si.publicKey.toHexString(),
                    preset = label, overrideFrequencyMhz = si.frequencyMhz, spreadFactor = si.spreadingFactor, codingRate = si.codingRate,
                    txPowerDbm = si.txPowerDbm, txEnabled = true,
                ))
                if (mcStage == McStage.SELF_INFO) {
                    mcStage = McStage.CONTACTS
                    mcStageMs = clock.monotonicMs()
                    setState(RadioState.DOWNLOADING_CONFIG, "MeshCore ${si.name}: reading known nodes")
                    sendMc(MeshCore.getContacts())
                }
            }
            MeshCore.RESP_DEVICE_INFO -> MeshCore.decodeDeviceInfo(f)?.let { d ->
                val fw = listOfNotNull("MeshCore", d.version ?: "fw ${d.firmwareCode}", d.buildDate?.let { "($it)" }).joinToString(" ")
                updateInfo(info.copy(firmware = fw + (d.model?.let { " · $it" } ?: "")))
            }
            MeshCore.RESP_CONTACTS_START -> { mcStageMs = clock.monotonicMs() }
            MeshCore.RESP_CONTACT, MeshCore.PUSH_NEW_ADVERT -> MeshCore.decodeContact(f)?.let { c ->
                mcContacts++
                mcStageMs = clock.monotonicMs()
                listeners.forEach { it.onMeshCoreNode(c.keyHex, c.type, c.name.ifEmpty { null }, c.lat, c.lon, true) }
            }
            MeshCore.RESP_END_OF_CONTACTS -> if (mcStage == McStage.CONTACTS) mcReady("$mcContacts known nodes")
            MeshCore.RESP_ERR -> if (mcStage == McStage.CONTACTS) mcReady("contact list not available")
            MeshCore.RESP_BATT_AND_STORAGE -> MeshCore.decodeBatteryMv(f)?.let { mv -> listeners.forEach { it.onMeshCoreBattery(mv) } }
            MeshCore.PUSH_LOG_RX_DATA -> {
                val rx = try { MeshCore.decodeRx(f) } catch (_: Exception) { null }
                if (rx == null) { listeners.forEach { it.onDebugText("undecodable MeshCore packet (${f.size} B)") }; return }
                rx.advert?.let { a -> listeners.forEach { it.onMeshCoreNode(a.keyHex, a.type, a.name, a.lat, a.lon, false) } }
                listeners.forEach { it.onMeshCoreRx(rx, clock.wallMs(), state != RadioState.READY) }
            }
            else -> {}   // adverts by key, message-waiting notices etc.: not needed for listening
        }
    }

    private fun ByteArray.toHexString() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun trimNum(v: Double, d: Int): String {
        var m = 1.0; repeat(d) { m *= 10 }
        val r = kotlin.math.round(v * m) / m
        return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun applyMeta(m: DeviceMetadataInfo) = info.copy(firmware = m.firmwareVersion, hwModel = m.hwModel)

    private fun updateInfo(i: RadioInfo) { info = i; listeners.forEach { it.onRadioInfo(i) } }

    private fun setState(s: RadioState, detail: String) {
        state = s
        listeners.forEach { it.onRadioState(s, detail) }
    }
}
