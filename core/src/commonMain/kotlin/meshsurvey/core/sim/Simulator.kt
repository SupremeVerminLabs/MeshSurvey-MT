package meshsurvey.core.sim

import meshsurvey.core.abtest.TestPayload
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.ProtoWriter
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.LinkState
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.util.Clock
import meshsurvey.core.util.Geo
import meshsurvey.core.util.newId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Encoders for FromRadio messages, used by the simulator and by tests. */
object FromRadioEncoder {
    fun myInfo(num: Long) = ProtoWriter().message(3, ProtoWriter().uint32(1, num)).toByteArray()

    fun user(id: String, longName: String, shortName: String, role: Int) =
        ProtoWriter().string(1, id).string(2, longName).string(3, shortName).varint(7, role.toLong())

    fun position(lat: Double, lon: Double) =
        ProtoWriter().sfixed32(1, (lat * 1e7).roundToInt()).sfixed32(2, (lon * 1e7).roundToInt())

    fun nodeInfo(num: Long, longName: String, shortName: String, role: Int, lat: Double?, lon: Double?): ByteArray {
        val ni = ProtoWriter().uint32(1, num).message(2, user("!" + num.toString(16).padStart(8, '0'), longName, shortName, role))
        if (lat != null && lon != null) ni.message(3, position(lat, lon))
        return ProtoWriter().message(4, ni).toByteArray()
    }

    fun loraConfig(preset: Int, region: Int, channelNum: Int, hopLimit: Int, txEnabled: Boolean, overrideMhz: Float = 0f): ByteArray {
        val lora = ProtoWriter().bool(1, true).varint(2, preset.toLong()).varint(7, region.toLong()).varint(8, hopLimit.toLong())
            .bool(9, txEnabled).varint(11, channelNum.toLong()).float(14, overrideMhz)
        return ProtoWriter().message(5, ProtoWriter().message(6, lora)).toByteArray()
    }

    fun metadata(firmware: String, hwModel: Int) =
        ProtoWriter().message(13, ProtoWriter().string(1, firmware).varint(9, hwModel.toLong())).toByteArray()

    fun configComplete(nonce: Long) = ProtoWriter().uint32(7, nonce).toByteArray()

    /** FromRadio { config { lora = <raw LoRaConfig bytes> } } */
    fun loraConfigRaw(raw: ByteArray) = ProtoWriter().message(5, ProtoWriter().messageAlways(6, raw)).toByteArray()

    /** FromRadio { channel { index, settings { name }, role } } */
    fun channel(index: Int, name: String, role: Int) = ProtoWriter().message(10,
        ProtoWriter().int32(1, index).message(2, ProtoWriter().string(3, name)).varint(3, role.toLong())).toByteArray()

    fun rebooted() = ProtoWriter().bool(8, true).toByteArray()

    /** FromRadio { queueStatus { res=1, free=2, maxlen=3, mesh_packet_id=4 } } */
    fun queueStatus(res: Int, free: Int, maxLen: Int, packetId: Long) =
        ProtoWriter().message(11, ProtoWriter().int32(1, res).uint32(2, free.toLong()).uint32(3, maxLen.toLong()).uint32(4, packetId)).toByteArray()

    fun packet(
        from: Long, to: Long, id: Long, port: Int?, payload: ByteArray?,
        snr: Float? = null, rssi: Int? = null, rxTime: Long? = null,
        hopLimit: Int? = null, hopStart: Int? = null, relay: Int? = null, channel: Int = 0,
        requestId: Long? = null, encrypted: ByteArray? = null, viaMqtt: Boolean = false,
    ): ByteArray {
        val p = ProtoWriter().fixed32(1, from).fixed32(2, to).uint32(3, channel.toLong())
        if (port != null) {
            val d = ProtoWriter().varint(1, port.toLong()).bytes(2, payload)
            requestId?.let { d.fixed32(6, it) }
            p.messageAlways(4, d.toByteArray())
        } else if (encrypted != null) p.bytes(5, encrypted)
        p.fixed32(6, id)
        rxTime?.let { p.fixed32(7, it) }
        snr?.let { p.float(8, it) }
        hopLimit?.let { p.uint32(9, it.toLong()) }
        rssi?.let { p.int32(12, it) }
        p.bool(14, viaMqtt)
        hopStart?.let { p.uint32(15, it.toLong()) }
        relay?.let { p.uint32(19, it.toLong()) }
        return ProtoWriter().message(2, p).toByteArray()
    }

    fun routing(errorReason: Int) = ProtoWriter().varint(3, errorReason.toLong()).toByteArray()

    fun deviceTelemetry(timeS: Long, battery: Int, voltage: Float, chUtil: Float, airTx: Float) =
        ProtoWriter().fixed32(1, timeS).message(2, ProtoWriter().varint(1, battery.toLong()).float(2, voltage).float(3, chUtil).float(4, airTx)).toByteArray()
}

class SimNode(val num: Long, val shortName: String, val longName: String, val role: Int,
              val lat: Double, val lon: Double, val gainDb: Double, val periodS: Int)

/**
 * A deterministic synthetic mesh around a centre point. Signal levels follow a simple
 * log-distance model with noise. This is for development only: nothing it produces is RF evidence.
 */
class SimWorld(seed: Int = 7, val centerLat: Double = 45.4215, val centerLon: Double = -75.6972) {
    val random = Random(seed)
    val myNodeNum = 0x51a0_0001L
    /** Simulated user-owned reference device (register it to try A/B tests). */
    val peerNodeNum = 0x51a0_00b2L
    val repeaterNum = 0x51a0_0099L
    val nodes: List<SimNode>

    init {
        val list = ArrayList<SimNode>()
        list += SimNode(repeaterNum, "RPT", "Sim Hill Repeater", 4, centerLat + 0.012, centerLon - 0.018, 9.0, 900)
        list += SimNode(peerNodeNum, "SB", "Sim Reference B", 0, centerLat - 0.004, centerLon + 0.006, 0.0, 600)
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")
        names.forEachIndexed { i, n ->
            val ang = random.nextDouble(0.0, 2 * PI); val dist = random.nextDouble(0.006, 0.04)
            list += SimNode(0x51a0_1000L + i, n.take(3).uppercase(), "Sim $n", if (i == 2) 2 else 0,
                centerLat + dist * sin(ang), centerLon + dist * cos(ang) * 1.4, if (i == 2) 6.0 else 0.0, 120 + random.nextInt(240))
        }
        nodes = list
    }

    fun node(num: Long) = nodes.firstOrNull { it.num == num }

    fun gauss(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-9); val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    /** SNR (dB) of a link of the given length; LoRa long-fast decodes down to about -18 dB. */
    fun snr(distanceM: Double, gainDb: Double): Double = 12.0 - 32.0 * log10(max(distanceM, 30.0) / 300.0) + gainDb + gauss() * 2.5

    fun rssiFromSnr(snr: Double): Int = (-118 + snr * 1.6 + gauss() * 2).roundToInt().coerceIn(-135, -40)

    companion object { const val DECODE_FLOOR = -18.0 }
}

/** Simulated radio implementing the same phone API the real links carry. Source is always SIMULATED. */
class SimulatedLink(
    private val world: SimWorld,
    private val clock: Clock,
    private val receiverPos: () -> Pair<Double, Double>?,
    /** When true, the simulated peer sends a test packet to us every 60 s (to exercise Device B role). */
    var peerSendsTests: Boolean = false,
    /** The participation code the simulated peer was "told" (normally read from this device's reference session). */
    private val peerRefCode: () -> Int? = { null },
) : RadioLink {
    override val kind = LinkKind.SIMULATED
    override val description = "Simulator (not RF)"
    private var listener: RadioLink.Listener? = null
    private val outbox = ArrayList<Pair<Long, ByteArray>>() // due monotonic ms -> FromRadio
    private val nextDue = HashMap<Long, Long>()
    private var nextPeerTest = 0L
    private var peerSeq = 0
    private val peerSession = newId(world.random)
    private var connected = false
    private val r get() = world.random
    /**
     * The simulated radio's LoRaConfig, including fields this app does not model (tx_power, sx126x_rx_boosted_gain,
     * ignore_incoming, ignore_mqtt) so tests can prove they survive a temporary preset change untouched.
     */
    var loraRaw: ByteArray = ProtoWriter().bool(1, true).varint(7, 1).varint(8, 3).bool(9, true).int32(10, 27)
        .bool(13, true).varint(103, 0x1234).varint(103, 0x5678).bool(104, true).toByteArray()
        private set
    val currentPreset: Int get() = Wire.parse(loraRaw).f(2)?.int ?: 0
    var adminMessagesReceived = 0; private set

    override fun start(listener: RadioLink.Listener) {
        this.listener = listener
        listener.onLinkState(LinkState.CONNECTING, description)
        connected = true
        listener.onLinkState(LinkState.CONNECTED, description)
    }

    override fun close() {
        if (connected) listener?.onLinkState(LinkState.DISCONNECTED, "simulator closed")
        connected = false; listener = null; outbox.clear()
    }

    private fun queue(delayMs: Long, b: ByteArray) { outbox += (clock.monotonicMs() + delayMs) to b }

    override fun send(toRadio: ByteArray): Boolean {
        if (!connected) return false
        val f = Wire.parse(toRadio)
        f.f(3)?.let { want ->
            val now = clock.monotonicMs()
            queue(0, FromRadioEncoder.myInfo(world.myNodeNum))
            queue(0, FromRadioEncoder.metadata("2.7.15-sim", 43))
            queue(0, FromRadioEncoder.loraConfigRaw(loraRaw))
            queue(0, FromRadioEncoder.channel(0, "", 1))
            queue(0, FromRadioEncoder.nodeInfo(world.myNodeNum, "Sim Survey A", "SA", 0, null, null))
            world.nodes.forEach { queue(0, FromRadioEncoder.nodeInfo(it.num, it.longName, it.shortName, it.role, it.lat, it.lon)) }
            queue(0, FromRadioEncoder.configComplete(want.u32))
            world.nodes.forEach { nextDue[it.num] = now + r.nextLong(2_000, it.periodS * 1000L) }
            nextPeerTest = now + 20_000
        }
        f.f(1)?.bytes?.let { pkt -> handleOutgoing(Wire.parse(pkt)) }
        return true
    }

    private fun handleOutgoing(p: List<meshsurvey.core.proto.PField>) {
        val to = p.f(2)?.u32 ?: return
        val id = p.f(6)?.u32 ?: return
        val data = p.f(4)?.bytes?.let { Wire.parse(it) }
        if (to == world.myNodeNum && data?.f(1)?.int == Port.ADMIN) {
            // AdminMessage.set_config(34).lora(6): apply and simulate the firmware reboot
            val admin = Wire.parse(data.f(2)?.bytes ?: return)
            val lora = admin.f(34)?.bytes?.let { Wire.parse(it).f(6)?.bytes } ?: return
            adminMessagesReceived++
            loraRaw = lora
            queue(1_500, FromRadioEncoder.rebooted())
            return
        }
        val wantAck = p.f(10)?.bool ?: false
        val target = world.node(to)
        val pos = receiverPos()
        if (!wantAck) return
        if (target == null || pos == null) { queue(30_000, routingFrom(world.myNodeNum, id, 5)); return }
        val snr = world.snr(Geo.distanceM(pos.first, pos.second, target.lat, target.lon), 0.0)
        if (snr >= SimWorld.DECODE_FLOOR) {
            queue(r.nextLong(1_500, 4_000), routingFrom(to, id, 0))
        } else {
            val viaRpt = world.node(world.repeaterNum)!!
            val s1 = world.snr(Geo.distanceM(pos.first, pos.second, viaRpt.lat, viaRpt.lon), viaRpt.gainDb)
            val s2 = world.snr(Geo.distanceM(viaRpt.lat, viaRpt.lon, target.lat, target.lon), viaRpt.gainDb)
            if (s1 >= SimWorld.DECODE_FLOOR) queue(r.nextLong(800, 1_500), routingFrom(world.myNodeNum, id, 0)) // implicit ACK
            if (s1 >= SimWorld.DECODE_FLOOR && s2 >= SimWorld.DECODE_FLOOR) queue(r.nextLong(4_000, 9_000), routingFrom(to, id, 0))
            else queue(r.nextLong(25_000, 35_000), routingFrom(world.myNodeNum, id, 5)) // MAX_RETRANSMIT
        }
    }

    private fun routingFrom(from: Long, requestId: Long, err: Int): ByteArray {
        val local = from == world.myNodeNum
        return FromRadioEncoder.packet(from, world.myNodeNum, r.nextLong(1, 0xffffffffL), Port.ROUTING, FromRadioEncoder.routing(err),
            snr = if (local) null else 4f, rssi = if (local) null else -100, rxTime = clock.wallMs() / 1000,
            hopLimit = 3, hopStart = 3, relay = (from and 0xff).toInt(), requestId = requestId)
    }

    override fun tick() {
        val l = listener ?: return
        val now = clock.monotonicMs()
        generate(now)
        val due = outbox.filter { it.first <= now }
        outbox.removeAll(due)
        due.forEach { l.onFromRadio(it.second) }
    }

    private fun generate(now: Long) {
        val pos = receiverPos() ?: return
        for (n in world.nodes) {
            val due = nextDue[n.num] ?: continue
            if (now < due) continue
            nextDue[n.num] = now + (n.periodS * 1000L * r.nextDouble(0.6, 1.4)).toLong()
            // On a temporary test preset only the reference device (assumed switched too) is audible.
            if (currentPreset != 0 && n.num != world.peerNodeNum) continue
            emitFrom(n, pos)
        }
        if (peerSendsTests && now >= nextPeerTest) {
            nextPeerTest = now + 60_000
            val peer = world.node(world.peerNodeNum)!!
            val snr = world.snr(Geo.distanceM(pos.first, pos.second, peer.lat, peer.lon), 0.0)
            if (snr >= SimWorld.DECODE_FLOOR) {
                val payload = TestPayload(peerSession, peerSeq++, clock.wallMs() / 1000, peerRefCode()).encode()
                queue(0, FromRadioEncoder.packet(peer.num, world.myNodeNum, r.nextLong(1, 0xffffffffL), Port.PRIVATE, payload,
                    snr = snr.toFloat(), rssi = world.rssiFromSnr(snr), rxTime = clock.wallMs() / 1000, hopLimit = 3, hopStart = 3,
                    relay = (peer.num and 0xff).toInt()))
            } else peerSeq++
        }
    }

    private fun emitFrom(n: SimNode, pos: Pair<Double, Double>) {
        val d = Geo.distanceM(pos.first, pos.second, n.lat, n.lon)
        var snr = world.snr(d, n.gainDb)
        var hopLimit = 3
        var relay = (n.num and 0xff).toInt()
        if (snr < SimWorld.DECODE_FLOOR) {
            val rpt = world.node(world.repeaterNum)!!
            if (n.num == rpt.num) return
            val s1 = world.snr(Geo.distanceM(n.lat, n.lon, rpt.lat, rpt.lon), n.gainDb + rpt.gainDb / 2)
            val s2 = world.snr(Geo.distanceM(pos.first, pos.second, rpt.lat, rpt.lon), rpt.gainDb)
            if (s1 < SimWorld.DECODE_FLOOR || s2 < SimWorld.DECODE_FLOOR) return
            snr = s2; hopLimit = 2; relay = (rpt.num and 0xff).toInt()
        }
        val rssi = world.rssiFromSnr(snr)
        val id = r.nextLong(1, 0xffffffffL)
        val t = clock.wallMs() / 1000
        val roll = r.nextInt(100)
        val bytes = when {
            roll < 12 -> FromRadioEncoder.packet(n.num, BROADCAST, id, null, null, snr.toFloat(), rssi, t, hopLimit, 3, relay,
                channel = 31, encrypted = ByteArray(24 + r.nextInt(40)) { r.nextInt(256).toByte() })
            roll < 40 -> FromRadioEncoder.packet(n.num, BROADCAST, id, Port.TELEMETRY,
                FromRadioEncoder.deviceTelemetry(t, 60 + r.nextInt(40), 3.7f + r.nextFloat() * 0.4f, r.nextFloat() * 20, r.nextFloat() * 3),
                snr.toFloat(), rssi, t, hopLimit, 3, relay)
            roll < 65 -> FromRadioEncoder.packet(n.num, BROADCAST, id, Port.POSITION, FromRadioEncoder.position(n.lat, n.lon).toByteArray(),
                snr.toFloat(), rssi, t, hopLimit, 3, relay)
            roll < 85 -> FromRadioEncoder.packet(n.num, BROADCAST, id, Port.NODEINFO,
                FromRadioEncoder.user(MeshEnumsId(n.num), n.longName, n.shortName, n.role).toByteArray(), snr.toFloat(), rssi, t, hopLimit, 3, relay)
            else -> FromRadioEncoder.packet(n.num, BROADCAST, id, Port.TEXT, "sim message".encodeToByteArray(), snr.toFloat(), rssi, t, hopLimit, 3, relay)
        }
        queue(0, bytes)
    }

    private fun MeshEnumsId(n: Long) = "!" + n.toString(16).padStart(8, '0')
}

/** Simulated receiver moving on a loop around the world centre (1 fix/s). Source SIMULATED. */
class SimulatedLocation(private val world: SimWorld, private val clock: Clock, private val radiusM: Double = 2_500.0, private val speedMs: Double = 11.0) {
    private val start = clock.monotonicMs()
    fun current(): Fix {
        val t = (clock.monotonicMs() - start) / 1000.0
        val ang = (t * speedMs / radiusM) % (2 * PI)
        val wobble = 1.0 + 0.15 * sin(ang * 5)
        val lat = world.centerLat + (radiusM * wobble / 111_320.0) * sin(ang)
        val lon = world.centerLon + (radiusM * wobble / (111_320.0 * cos(world.centerLat * PI / 180))) * cos(ang)
        return Fix(lat, lon, 4.0 + world.random.nextDouble() * 6, 70.0, clock.wallMs(), clock.monotonicMs(), FixSource.SIMULATED)
    }
}
