package meshsurvey.core.log

import meshsurvey.core.abtest.TestPayload
import meshsurvey.core.model.Fix
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.proto.MeshPacketRx
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.FrequencyCalc
import meshsurvey.core.radio.RadioInfo
import meshsurvey.core.radio.ReceivedPacket
import meshsurvey.core.store.Store
import meshsurvey.core.util.Clock
import meshsurvey.core.util.Geo
import meshsurvey.core.util.newId
import kotlin.math.roundToInt

data class LoggerConfig(
    /** A fix older than this is not attached to an observation (position stays unknown). */
    val maxFixAgeMs: Long = 10_000,
    /** Node rx_time older than this vs. the phone clock => packet was queued, not live. */
    val lateThresholdMs: Long = 30_000,
    val trackMinIntervalMs: Long = 5_000,
    val trackMinDistanceM: Double = 10.0,
    val trackMaxIntervalMs: Long = 60_000,
    /** Off by default (spec §3): store decoded summaries of non-text packets. Text content is never stored. */
    val storeSummaries: Boolean = false,
)

data class LoggerStats(
    val logged: Int = 0,
    val withPosition: Int = 0,
    val encrypted: Int = 0,
    val queuedLate: Int = 0,
    val skippedOwn: Int = 0,
    val skippedMqtt: Int = 0,
    /** Fixes refused because their timestamp was in the future (clock or provider error) or values were invalid. */
    val rejectedFixes: Int = 0,
)

/**
 * Passive reception logging. Never transmits.
 * Each packet delivered by the radio becomes its own [Observation] (no merging by packet id).
 */
class ReceptionLogger(
    private val store: Store,
    private val clock: Clock,
    var config: LoggerConfig = LoggerConfig(),
) {
    var session: SurveySession? = null; private set
    var stats = LoggerStats(); private set
    var lastFix: Fix? = null; private set
    private var lastTrack: Fix? = null
    var radioReady = false
    private val listeners = ArrayList<(Observation) -> Unit>()

    fun addObservationListener(l: (Observation) -> Unit) { listeners += l }

    fun startSession(info: RadioInfo, source: Source, profileId: String? = null): SurveySession {
        session?.let { endSession() }
        val fq = FrequencyCalc.from(info)
        val s = SurveySession(
            id = newId(), startedMs = clock.wallMs(), endedMs = null, status = SessionStatus.ACTIVE,
            receiverNode = info.myNodeNum, linkKind = info.linkKind?.name, firmware = info.firmware,
            preset = info.preset, region = info.region, source = source,
            profileId = profileId, hwModel = info.hwModel, txPowerDbm = info.txPowerDbm,
            frequencyMhz = fq.mhz, frequencySource = fq.source.name,
        )
        store.insertSession(s)
        session = s
        stats = LoggerStats()
        return s
    }

    fun endSession(status: SessionStatus = SessionStatus.ENDED) {
        val s = session ?: return
        store.endSession(s.id, clock.wallMs(), status)
        session = null
    }

    fun fixAgeMs(fix: Fix?): Long? = fix?.let { clock.monotonicMs() - it.monotonicMs }

    /** Current fix if fresh enough to attach to a measurement, else null. */
    fun freshFix(): Fix? = lastFix?.takeIf { (fixAgeMs(it) ?: Long.MAX_VALUE) <= config.maxFixAgeMs }

    fun onFix(fix: Fix) {
        val invalid = !Geo.validLatLon(fix.lat, fix.lon) ||
            fix.monotonicMs > clock.monotonicMs() + 1_000 ||                     // a fix from the future
            (fix.accuracyM != null && (fix.accuracyM.isNaN() || fix.accuracyM < 0))
        if (invalid) { stats = stats.copy(rejectedFixes = stats.rejectedFixes + 1); return }
        lastFix = fix
        val s = session ?: return
        val prev = lastTrack
        val dt = if (prev == null) Long.MAX_VALUE else fix.monotonicMs - prev.monotonicMs
        val moved = if (prev == null) Double.MAX_VALUE else Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
        if (prev == null || (dt >= config.trackMinIntervalMs && moved >= config.trackMinDistanceM) || dt >= config.trackMaxIntervalMs) {
            store.insertTrack(TrackPoint(sessionId = s.id, ms = fix.wallMs, lat = fix.lat, lon = fix.lon,
                accuracyM = fix.accuracyM, radioReady = radioReady, source = s.source))
            lastTrack = fix
        }
    }

    /** Returns the stored observation, or null if the packet is not an RF reception by this radio. */
    fun onPacket(rx: ReceivedPacket, info: RadioInfo): Observation? {
        val s = session ?: return null
        val p = rx.packet
        if (info.myNodeNum != null && p.from == info.myNodeNum) { stats = stats.copy(skippedOwn = stats.skippedOwn + 1); return null }
        if (p.viaMqtt) { stats = stats.copy(skippedMqtt = stats.skippedMqtt + 1); return null }

        val late = isLate(p, rx)
        val status = when {
            late -> RxStatus.QUEUED_LATE
            !p.decoded -> RxStatus.ENCRYPTED
            else -> RxStatus.DECODED
        }
        val fix = if (late) null else freshFix()
        val fq = FrequencyCalc.from(info)
        val o = Observation(
            sessionId = s.id, receiverNode = info.myNodeNum, rxMs = rx.phoneTimeMs, nodeRxTime = p.rxTime,
            lat = fix?.lat, lon = fix?.lon, accuracyM = fix?.accuracyM,
            fixAgeMs = if (late) null else fixAgeMs(lastFix),
            rssi = p.rssi, snr = p.snr?.toDouble(), packetId = p.id, fromNode = p.from, toNode = p.to,
            channel = p.channel, portnum = p.portnum, type = if (p.portnum == Port.PRIVATE && TestPayload.decode(p.payload) != null) PacketType.SURVEY_TEST else PacketType.of(p.portnum),
            status = status,
            hopStart = p.hopStart, hopLimit = p.hopLimit, hopsAway = p.hopsAway, relayByte = p.relayByte,
            payloadLen = p.payload?.size ?: p.encryptedLen,
            preset = info.preset, region = info.region, frequencyMhz = fq.mhz,
            summary = if (config.storeSummaries) Summaries.of(p) else null,
            source = s.source,
            frequencySource = fq.source.name,
            fixSource = fix?.source?.name,
            positionAgeMs = fix?.let { f -> f.positionTimeMs?.let { rx.phoneTimeMs - it } ?: fixAgeMs(f) },
        )
        val id = store.insertObservation(o)
        val saved = o.copy(id = id)
        stats = stats.copy(
            logged = stats.logged + 1,
            withPosition = stats.withPosition + if (fix != null) 1 else 0,
            encrypted = stats.encrypted + if (status == RxStatus.ENCRYPTED) 1 else 0,
            queuedLate = stats.queuedLate + if (late) 1 else 0,
        )
        listeners.forEach { it(saved) }
        return saved
    }

    /**
     * Timestamp validation. A node clock that is ahead of the phone, or more than a day behind,
     * is treated as unsynchronised and not used; then only "arrived during config download" counts as late.
     */
    private fun isLate(p: MeshPacketRx, rx: ReceivedPacket): Boolean {
        val nodeT = p.rxTime?.takeIf { it > 1_600_000_000L }?.let { it * 1000 }
        if (nodeT != null) {
            val delay = rx.phoneTimeMs - nodeT
            if (delay in config.lateThresholdMs..86_400_000L) return true
            if (delay in -5_000L until config.lateThresholdMs) return false
        }
        return rx.duringConfigDownload
    }
}

/** Optional non-text summaries (off by default). */
object Summaries {
    fun of(p: MeshPacketRx): String? {
        val pl = p.payload ?: return null
        return try {
            when (p.portnum) {
                Port.TEXT, Port.TEXT_COMPRESSED -> null // message contents are never stored
                Port.POSITION -> MeshProto.decodePosition(pl)?.let { "position reported" }
                Port.NODEINFO -> MeshProto.decodeUser(pl).let { (ln, sn, role) ->
                    "nodeinfo ${sn ?: "?"} ${ln ?: ""} ${role?.let { meshsurvey.core.proto.MeshEnums.role(it) } ?: ""}".trim()
                }
                Port.TELEMETRY -> telemetry(pl)
                Port.NEIGHBORINFO -> "neighbors=" + Wire.parse(pl).count { it.num == 4 }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun telemetry(pl: ByteArray): String {
        val t = Wire.parse(pl)
        t.f(2)?.bytes?.let { b ->
            val d = Wire.parse(b)
            val parts = ArrayList<String>()
            d.f(1)?.let { parts += if (it.int > 100) "bat=ext" else "bat=${it.int}%" }
            d.f(2)?.let { parts += "v=${(it.float * 100).roundToInt() / 100.0}" }
            d.f(3)?.let { parts += "chutil=${(it.float * 10).roundToInt() / 10.0}%" }
            d.f(4)?.let { parts += "airtx=${(it.float * 10).roundToInt() / 10.0}%" }
            return "device " + parts.joinToString(" ")
        }
        if (t.f(3) != null) return "environment"
        if (t.f(5) != null) return "power"
        if (t.f(6) != null) return "localstats"
        return "telemetry"
    }
}
