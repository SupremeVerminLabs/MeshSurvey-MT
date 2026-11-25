package meshsurvey.core.radio

import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.DeviceMetadataInfo
import meshsurvey.core.proto.FromRadioMsg
import meshsurvey.core.proto.LoraConfigInfo
import meshsurvey.core.proto.MeshPacketRx
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.NodeRecord
import meshsurvey.core.proto.Port
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

    fun addListener(l: RadioListener) { listeners += l }
    fun removeListener(l: RadioListener) { listeners -= l }

    val isReady get() = state == RadioState.READY
    val linkKind get() = link?.kind

    fun connect(newLink: RadioLink) {
        disconnect()
        link = newLink
        nodes.clear()
        info = RadioInfo(linkKind = newLink.kind)
        setState(RadioState.CONNECTING, newLink.description)
        newLink.start(this)
    }

    fun disconnect() {
        val l = link ?: return
        link = null
        try { l.send(MeshProto.disconnect()) } catch (_: Exception) {}
        l.close()
        setState(RadioState.DISCONNECTED, "closed")
    }

    /** Periodic housekeeping (heartbeat keeps serial/TCP API sessions alive). */
    fun tick() {
        val l = link ?: return
        l.tick()
        val now = clock.monotonicMs()
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
        if (state != RadioState.READY) return null
        require(to != BROADCAST) { "broadcast sending is not permitted by this app" }
        require(port == Port.PRIVATE) { "only A/B test payloads may be sent" }
        val id = (random.nextLong(1, 0xffffffffL)) and 0xffffffffL
        return if (l.send(MeshProto.sendPacket(to, port, payload, id, wantAck, channel, hopLimit))) id else null
    }

    // ---------------- RadioLink.Listener ----------------

    override fun onLinkState(state: LinkState, detail: String) {
        when (state) {
            LinkState.CONNECTING -> setState(RadioState.CONNECTING, detail)
            LinkState.CONNECTED -> {
                nonce = random.nextLong(1, 0x7fffffffL)
                setState(RadioState.DOWNLOADING_CONFIG, detail)
                link?.send(MeshProto.wantConfig(nonce))
                lastHeartbeatMs = clock.monotonicMs()
            }
            LinkState.DISCONNECTED -> setState(RadioState.DISCONNECTED, detail)
            LinkState.FAILED -> setState(RadioState.FAILED, detail)
        }
    }

    override fun onDebugText(text: String) { listeners.forEach { it.onDebugText(text) } }

    override fun onFromRadio(bytes: ByteArray) {
        lastRxMs = clock.monotonicMs()
        val msg = try { MeshProto.decodeFromRadio(bytes) } catch (e: Exception) {
            listeners.forEach { it.onDebugText("undecodable FromRadio (${bytes.size} B): ${e.message}") }
            return
        }
        when (msg) {
            is FromRadioMsg.MyInfo -> updateInfo(info.copy(myNodeNum = msg.myNodeNum))
            is FromRadioMsg.Node -> { nodes[msg.node.num] = msg.node; listeners.forEach { it.onNode(msg.node) } }
            is FromRadioMsg.LoraConfig -> updateInfo(applyLora(msg.cfg))
            is FromRadioMsg.Metadata -> updateInfo(applyMeta(msg.meta))
            is FromRadioMsg.Channel -> if (msg.role == 1) updateInfo(info.copy(primaryChannelName = msg.name))
            is FromRadioMsg.ConfigComplete -> if (msg.nonce == nonce) setState(RadioState.READY, "config received")
            is FromRadioMsg.Packet -> {
                val rx = ReceivedPacket(msg.packet, clock.wallMs(), clock.monotonicMs(), state != RadioState.READY)
                learnFromPacket(msg.packet)
                listeners.forEach { it.onPacket(rx) }
            }
            is FromRadioMsg.Rebooted -> {
                // Node rebooted: re-request config on the same link.
                nonce = random.nextLong(1, 0x7fffffffL)
                setState(RadioState.DOWNLOADING_CONFIG, "radio rebooted")
                link?.send(MeshProto.wantConfig(nonce))
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
    )

    /** Re-requests the full config (used to confirm a configuration change). */
    fun refreshConfig() {
        val l = link ?: return
        nonce = random.nextLong(1, 0x7fffffffL)
        setState(RadioState.DOWNLOADING_CONFIG, "re-reading configuration")
        l.send(MeshProto.wantConfig(nonce))
    }

    /**
     * Sends an AdminMessage to the locally connected node only. Used exclusively by
     * [meshsurvey.core.preset.PresetManager] after explicit user approval.
     */
    fun sendAdminToLocalNode(adminPayload: ByteArray): Boolean {
        val l = link ?: return false
        val me = info.myNodeNum ?: return false
        if (state != RadioState.READY) return false
        return l.send(MeshProto.sendAdminLocal(me, adminPayload, random.nextLong(1, 0xffffffffL)))
    }

    private fun applyMeta(m: DeviceMetadataInfo) = info.copy(firmware = m.firmwareVersion, hwModel = m.hwModel)

    private fun updateInfo(i: RadioInfo) { info = i; listeners.forEach { it.onRadioInfo(i) } }

    private fun setState(s: RadioState, detail: String) {
        state = s
        listeners.forEach { it.onRadioState(s, detail) }
    }
}
