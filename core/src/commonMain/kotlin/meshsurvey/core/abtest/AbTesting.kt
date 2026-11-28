package meshsurvey.core.abtest

import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.Fix
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Participation
import meshsurvey.core.model.Source
import meshsurvey.core.model.TestMode
import meshsurvey.core.model.TestRole
import meshsurvey.core.model.TestRx
import meshsurvey.core.model.TestSession
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TxStatus
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Port
import meshsurvey.core.radio.FrequencyCalc
import meshsurvey.core.radio.LoraParams
import meshsurvey.core.radio.Presets
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.ReceivedPacket
import meshsurvey.core.radio.TestBudget
import meshsurvey.core.store.Store
import meshsurvey.core.util.Clock
import meshsurvey.core.util.hexToBytes
import meshsurvey.core.util.newId
import meshsurvey.core.util.toHex

/**
 * Test payload carried in a direct message on PRIVATE_APP (port 256), 18 bytes:
 *   "MST" | version(1) | session id (8) | seq (u16 BE) | sender unix time s (u32 BE)
 * Stock apps ignore this port, so tests don't show up as chat messages on Device B.
 */
/**
 * v2 (22 bytes) appends B's 6-digit participation code (u32 BE). v1 (18 bytes, no code) is still decoded
 * but never accepted by a reference session.
 */
data class TestPayload(val sessionId: String, val seq: Int, val senderTimeS: Long, val refCode: Int? = null) {
    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())
        const val SIZE = 18
        const val SIZE_V2 = 22

        fun decode(b: ByteArray?): TestPayload? {
            if (b == null || (b.size != SIZE && b.size != SIZE_V2)) return null
            if (b[0] != MAGIC[0] || b[1] != MAGIC[1] || b[2] != MAGIC[2]) return null
            val ver = b[3].toInt()
            if (!((ver == 1 && b.size == SIZE) || (ver == 2 && b.size == SIZE_V2))) return null
            val sid = b.copyOfRange(4, 12).toHex()
            val seq = ((b[12].toInt() and 0xff) shl 8) or (b[13].toInt() and 0xff)
            fun u32(o: Int): Long { var t = 0L; for (i in o until o + 4) t = (t shl 8) or (b[i].toLong() and 0xff); return t }
            return TestPayload(sid, seq, u32(14), if (ver == 2) u32(18).toInt() else null)
        }
    }

    fun encode(): ByteArray {
        val sid = sessionId.hexToBytes() ?: error("session id must be 16 hex chars")
        require(sid.size == 8 && seq in 0..0xffff)
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val head = MAGIC + byteArrayOf(if (refCode != null) 2 else 1) + sid + byteArrayOf((seq shr 8).toByte(), seq.toByte()) + u32(senderTimeS and 0xffffffffL)
        return if (refCode != null) head + u32(refCode.toLong() and 0xffffffffL) else head
    }
}

/** Fixed limits (spec: 30 s absolute minimum, finite sessions). Airtime limits come from [TestBudget]. */
data class AbConfig(
    val defaultIntervalS: Int = 60,
    val minIntervalS: Int = TestBudget.ABSOLUTE_MIN_INTERVAL_S,
    val defaultDurationS: Int = 300,
    val maxDurationS: Int = 1800,
    /** After this, a test with no destination ACK becomes UNKNOWN (or keeps RELAY_OBSERVED / NO_ACK). */
    val ackTimeoutS: Int = 90,
)

data class AbState(
    val role: TestRole = TestRole.A_SURVEY,
    val peer: Long? = null,
    /** How B's participation is established; null = not established, nothing can be sent. */
    val participation: Participation? = null,
    /** B's participation code (CODE mode). */
    val refCode: Int? = null,
    val mode: DeliveryMode = DeliveryMode.ACKED,
    /** Hop limit for ACKED tests; null = the radio's configured hop limit. DIRECT_ONLY always uses 0. */
    val hopLimit: Int? = null,
    val session: TestSession? = null,
    val autoRunning: Boolean = false,
    val effectiveIntervalS: Int? = null,
    val nextSendInS: Int? = null,
    val remainingS: Int? = null,
    val cooldownRemainingS: Int = 0,
    val sent: Int = 0,
    val delivered: Int = 0,
    val relayObserved: Int = 0,
    val rejected: Int = 0,
    val noAck: Int = 0,
    val notTransmitted: Int = 0,
    val unknown: Int = 0,
    val message: String? = null,
    val lateAcks: Int = 0,
) {
    val peerConfirmed: Boolean get() = peer != null && participation != null
}

/** Firmware routing-report classification (see docs/FIRMWARE_VERIFICATION.md). */
object RoutingOutcome {
    /** Errors the sending radio raises itself before/instead of transmitting. */
    val LOCAL_REFUSALS = setOf(4 /*NO_INTERFACE*/, 6 /*NO_CHANNEL*/, 7 /*TOO_LARGE*/, 9 /*DUTY_CYCLE_LIMIT*/,
        32 /*BAD_REQUEST*/, 34 /*PKI_FAILED*/, 38 /*RATE_LIMIT_EXCEEDED*/)

    fun classify(reportFrom: Long, destination: Long, errorReason: Int): TxStatus = when {
        reportFrom == destination && errorReason == 0 -> TxStatus.DELIVERED
        reportFrom == destination -> TxStatus.REJECTED
        errorReason == 0 -> TxStatus.RELAY_OBSERVED
        errorReason in LOCAL_REFUSALS -> TxStatus.NOT_TRANSMITTED
        else -> TxStatus.NO_ACK // MAX_RETRANSMIT, TIMEOUT, NO_ROUTE, GOT_NAK, NO_RESPONSE, …
    }

    /** Whether a later report may replace an earlier one (an ACK always wins; nothing replaces an ACK). */
    fun canReplace(old: TxStatus, new: TxStatus): Boolean = when (old) {
        TxStatus.DELIVERED, TxStatus.REJECTED, TxStatus.NOT_TRANSMITTED -> false
        TxStatus.NO_ACK -> new == TxStatus.DELIVERED || new == TxStatus.REJECTED
        TxStatus.RELAY_OBSERVED -> new != TxStatus.RELAY_OBSERVED
        TxStatus.SENT, TxStatus.UNKNOWN -> true
    }
}

/**
 * Device A (survey) role: explicit, finite tests to one user-confirmed target.
 * Never broadcasts, never tests an unconfirmed node, never retries at the app level
 * (firmware retries only in ACKED mode), stops on disconnect, timeout, app background or STOP.
 * No enrollment: the target is chosen and confirmed per session; nothing is registered remotely.
 */
class AbTestController(
    private val store: Store,
    private val radio: RadioClient,
    private val clock: Clock,
    private val fix: () -> Fix?,
    private val fixAgeMs: (Fix?) -> Long?,
    private val source: () -> Source,
    /** Returns a reason while sending must be blocked (e.g. a preset change is in progress). */
    private val blocker: () -> String? = { null },
    val config: AbConfig = AbConfig(),
    /** Latest radio-reported airtime, if any. */
    private val metrics: () -> meshsurvey.core.radio.RadioMetrics? = { null },
) {
    companion object {
        /** Refuse automated sessions when the radio reports the channel busier than this (all traffic). */
        const val MAX_CHANNEL_UTIL_FOR_AUTO = 40.0
        const val WARN_CHANNEL_UTIL = 25.0
        /** ACKs arriving up to this long after sending are still matched (also across app restarts). */
        const val LATE_ACK_WINDOW_MS = 2 * 3600_000L
    }
    /** Radio TX queue slots free, from the latest QueueStatus (null = not reported). */
    var queueFree: Int? = null; private set
    private val recentSent = LinkedHashMap<Long, Long>() // packet id -> test_tx row id (for queue reports)
    var state = AbState(); private set
    private val listeners = ArrayList<(AbState) -> Unit>()
    fun addListener(l: (AbState) -> Unit) { listeners += l }

    private class Pending(val rowId: Long, val to: Long, val sentMono: Long, var status: TxStatus)
    private val pending = LinkedHashMap<Long, Pending>()
    private var seq = 0
    private var lastSendMono: Long? = null
    private var autoEndMono = 0L
    private var autoIntervalMs = 0L
    private var nextSendMono = 0L

    private fun publish(s: AbState) { state = s; listeners.forEach { it(s) } }

    /** LoRa parameters of the radio as currently configured (null if unknown). */
    fun currentParams(): LoraParams? {
        val i = radio.info
        return if (i.usePreset == true && i.modemPreset != null) Presets.params(i.modemPreset)
        else FrequencyCalc.from(i).params
    }

    fun budget(mode: DeliveryMode = state.mode): TestBudget? = currentParams()?.let { TestBudget.of(it, mode == DeliveryMode.ACKED) }

    fun setRole(role: TestRole) {
        if (state.autoRunning) stop("role changed")
        endManualSession()
        publish(state.copy(role = role, message = null))
    }

    /**
     * Target is a stock radio (no MeshSurvey) that the operator of A controls. [confirmed] = the operator
     * affirmed this. Participation is asserted, not verifiable; only ACK mode is allowed.
     */
    fun setPeer(peer: Long?, confirmed: Boolean = false) {
        changeTarget(peer)
        publish(state.copy(peer = peer, participation = if (peer != null && confirmed) Participation.OPERATOR_CONTROLS_B else null,
            refCode = null, mode = if (confirmed) DeliveryMode.ACKED else state.mode, message = null))
    }

    /** Target runs MeshSurvey and its operator started a reference session showing [code]. */
    fun setTargetWithCode(peer: Long, code: Int): String? {
        if (code !in 100_000..999_999) return "The participation code has 6 digits"
        changeTarget(peer)
        publish(state.copy(peer = peer, participation = Participation.CODE, refCode = code, message = null))
        return null
    }

    private fun changeTarget(peer: Long?) {
        if (peer != state.peer) {
            if (state.autoRunning) stop("target changed")
            endManualSession()
        }
    }

    fun setMode(mode: DeliveryMode, hopLimit: Int? = state.hopLimit): String? {
        if (state.autoRunning) return "Stop the session first"
        if (mode == DeliveryMode.DIRECT_ONLY && state.participation == Participation.OPERATOR_CONTROLS_B)
            return "Direct-only needs Device B running MeshSurvey (enter its participation code); a stock B can only be tested with ACKs"
        if (mode != state.mode) endManualSession()
        publish(state.copy(mode = mode, hopLimit = hopLimit?.coerceIn(1, 7)))
        return null
    }

    private fun checkCanSend(peer: Long): String? {
        if (state.role != TestRole.A_SURVEY) return "Switch to Device A (survey) role to send tests"
        if (radio.state != RadioState.READY) return "Radio not connected"
        if (peer == BROADCAST || peer == 0L) return "Tests are only sent to a specific device"
        if (peer == radio.info.myNodeNum) return "Cannot test against your own radio"
        if (state.participation == null) return "Enter Device B's participation code, or confirm that you control ${MeshEnums.nodeId(peer)} (stock radio)"
        if (state.participation == Participation.OPERATOR_CONTROLS_B && state.mode == DeliveryMode.DIRECT_ONLY)
            return "Direct-only needs Device B running MeshSurvey"
        if (queueFree == 0) return "The radio's transmit queue is full; wait for it to drain"
        if (radio.info.txEnabled == false) return "Transmit is disabled in the radio's LoRa config"
        blocker()?.let { return it }
        val cd = cooldownRemainingMs()
        if (cd > 0) return "Wait ${(cd + 999) / 1000} s (minimum spacing for this preset)"
        return null
    }

    private fun minSpacingMs(): Long = 1000L * (budget()?.minIntervalS ?: config.minIntervalS)

    private fun cooldownRemainingMs(): Long {
        val last = lastSendMono ?: return 0
        return (minSpacingMs() - (clock.monotonicMs() - last)).coerceAtLeast(0)
    }

    /** Manual single test. Returns an error message or null on success. */
    fun sendSingle(): String? {
        radio.transmitBlock()?.let { return it }
        val peer = state.peer ?: return "Choose a target device"
        if (state.autoRunning) return "An automated session is running"
        checkCanSend(peer)?.let { publish(state.copy(message = it)); return it }
        val s = state.session?.takeIf { it.mode == TestMode.MANUAL && it.peerNode == peer && it.deliveryMode == state.mode && it.preset == radio.info.preset }
            ?: newSession(peer, TestMode.MANUAL, null, null)
        transmit(s)
        return null
    }

    /**
     * Starts a finite automated session. The interval is raised to the airtime-derived minimum
     * if needed, and the number of tests is capped by the per-session airtime budget.
     */
    fun startAuto(intervalS: Int = config.defaultIntervalS, durationS: Int = config.defaultDurationS): String? {
        val peer = state.peer ?: return "Choose a target device"
        radio.transmitBlock()?.let { return it }
        if (state.autoRunning) return "Already running"
        if (intervalS < config.minIntervalS) return "Interval must be at least ${config.minIntervalS} s"
        if (durationS <= 0 || durationS > config.maxDurationS) return "Duration must be 1–${config.maxDurationS} s"
        val b = budget() ?: return "Radio LoRa parameters unknown; cannot compute an airtime budget"
        val effInterval = maxOf(intervalS, b.minIntervalS)
        if (durationS < effInterval) return "Duration must be at least one interval (${effInterval} s for this preset)"
        checkCanSend(peer)?.let { publish(state.copy(message = it)); return it }
        val m = metrics()?.takeIf { it.freshAt(clock.wallMs()) }
        m?.channelUtilPct?.let { if (it > MAX_CHANNEL_UTIL_FOR_AUTO) return "Your radio reports the channel ${it.toInt()} % busy; automated sessions are refused above ${MAX_CHANNEL_UTIL_FOR_AUTO.toInt()} %" }
        val region = radio.info.regionCode?.let { meshsurvey.core.radio.Regions.table[it] }
        if (m?.airUtilTxPct != null && region != null && region.dutyCyclePct < 100 && m.airUtilTxPct > region.dutyCyclePct * 0.5)
            return "Your radio reports ${m.airUtilTxPct.toInt()} % transmit airtime, over half of ${region.name}'s ${region.dutyCyclePct} % duty-cycle limit"
        endManualSession()
        val tests = minOf(durationS / effInterval + 1, b.maxTestsPerSession)
        val effDuration = minOf(durationS.toLong(), (tests - 1).toLong() * effInterval + 1).toInt().coerceAtLeast(1)
        val s = newSession(peer, TestMode.AUTO, effInterval, effDuration)
        autoIntervalMs = effInterval * 1000L
        autoEndMono = clock.monotonicMs() + effDuration * 1000L
        val note = buildString {
            append("Automated session: up to $tests tests every $effInterval s")
            if (effInterval > intervalS) append(" (interval raised: worst-case ${(b.worstCaseMsPerTest).toInt()} ms airtime per test, ≤ ${(TestBudget.MAX_TEST_DUTY * 100).toInt()} % duty)")
        }
        val busyNote = m?.channelUtilPct?.takeIf { it > WARN_CHANNEL_UTIL }?.let { " · channel reported ${it.toInt()} % busy" } ?: ""
        publish(state.copy(autoRunning = true, effectiveIntervalS = effInterval, message = note + busyNote))
        transmit(s)
        nextSendMono = clock.monotonicMs() + autoIntervalMs
        tick()
        return null
    }

    fun stop(reason: String = "stopped by user") {
        val s = state.session
        if (s != null && s.mode == TestMode.AUTO) {
            recordAirtimeEnd(s.id)
            store.endTestSession(s.id, clock.wallMs(), reason)
            publish(state.copy(autoRunning = false, session = null, nextSendInS = null, remainingS = null, effectiveIntervalS = null,
                message = "Session ended: $reason"))
        } else if (state.autoRunning) {
            publish(state.copy(autoRunning = false, nextSendInS = null, remainingS = null, message = "Session ended: $reason"))
        }
    }

    /** Platform calls this when the UI goes to background: no unattended transmission. */
    fun onAppBackground() { if (state.autoRunning) stop("app moved to background") }

    fun onRadioState(s: RadioState) {
        if (s != RadioState.READY && state.autoRunning) stop("radio connection lost")
    }

    fun tick() {
        val now = clock.monotonicMs()
        val timeoutMs = config.ackTimeoutS * 1000L
        val expired = pending.entries.filter { now - it.value.sentMono >= timeoutMs }
        for (e in expired) {
            pending.remove(e.key)
            if (e.value.status == TxStatus.SENT) {
                store.updateTestTx(e.value.rowId, TxStatus.UNKNOWN, clock.wallMs(), null)
                bump(TxStatus.UNKNOWN, TxStatus.SENT)
            }
        }
        if (state.autoRunning) {
            if (now >= autoEndMono) { stop("duration reached"); return }
            if (radio.state != RadioState.READY) { stop("radio connection lost"); return }
            blocker()?.let { stop(it); return }
            if (now >= nextSendMono) {
                state.session?.let { transmit(it) }
                nextSendMono = now + autoIntervalMs
            }
            publish(state.copy(nextSendInS = ((nextSendMono - now + 999) / 1000).toInt(),
                remainingS = ((autoEndMono - now + 999) / 1000).toInt(), cooldownRemainingS = ((cooldownRemainingMs() + 999) / 1000).toInt()))
        } else {
            val cd = ((cooldownRemainingMs() + 999) / 1000).toInt()
            if (cd != state.cooldownRemainingS) publish(state.copy(cooldownRemainingS = cd))
        }
    }

    /** Routing reports for our own test packets. */
    fun onPacket(rx: ReceivedPacket) {
        val p = rx.packet
        if (p.portnum != Port.ROUTING) return
        val reqId = p.requestId ?: return
        val r = try { MeshProto.decodeRouting(p.payload ?: ByteArray(0)) } catch (_: Exception) { return }
        val pend = pending[reqId] ?: run { lateReport(reqId, p.from, r.errorReason); return }
        val new = RoutingOutcome.classify(p.from, pend.to, r.errorReason)
        val prev = pend.status
        if (!RoutingOutcome.canReplace(prev, new)) return
        pend.status = new
        store.updateTestTx(pend.rowId, new, clock.wallMs(), if (r.errorReason != 0) MeshEnums.routingError(r.errorReason) else null)
        // Final states end tracking; NO_ACK and RELAY_OBSERVED keep waiting for a late destination ACK until the timeout.
        if (new == TxStatus.DELIVERED || new == TxStatus.REJECTED || new == TxStatus.NOT_TRANSMITTED) pending.remove(reqId)
        bump(new, prev)
    }

    /**
     * A routing report for a test we no longer track in memory: after the ACK timeout, after a reconnect
     * (the radio queues reports for the phone), or after an app restart. Matched by packet id in the database.
     */
    private fun lateReport(packetId: Long, from: Long, err: Int) {
        val t = store.findTestTxByPacketId(packetId, clock.wallMs() - LATE_ACK_WINDOW_MS) ?: return
        if (t.fromNode != null && t.fromNode != radio.info.myNodeNum) return
        val new = RoutingOutcome.classify(from, t.toNode, err)
        if (!RoutingOutcome.canReplace(t.status, new)) return
        val delayS = (clock.wallMs() - t.sentMs) / 1000
        store.updateTestTx(t.id, new, clock.wallMs(), if (err != 0) MeshEnums.routingError(err) else if (new == TxStatus.DELIVERED) "LATE_ACK_${delayS}S" else null)
        if (state.session?.id == t.testSessionId) bump(new, t.status)
        if (new == TxStatus.DELIVERED) publish(state.copy(lateAcks = state.lateAcks + 1, message = "Late ACK for test #${t.seq} after $delayS s"))
    }

    /** Queue report from the radio. A non-zero result for one of our packets means it was not queued for transmission. */
    fun onQueueStatus(res: Int, free: Int, maxLen: Int, meshPacketId: Long) {
        queueFree = free
        if (res == 0 || meshPacketId == 0L) return
        val row = recentSent.remove(meshPacketId) ?: return
        val prev = pending.remove(meshPacketId)?.status ?: TxStatus.UNKNOWN
        if (!RoutingOutcome.canReplace(prev, TxStatus.NOT_TRANSMITTED)) return
        store.updateTestTx(row, TxStatus.NOT_TRANSMITTED, clock.wallMs(), "QUEUE_REJECTED_$res")
        bump(TxStatus.NOT_TRANSMITTED, prev)
    }

    private fun bump(new: TxStatus, prev: TxStatus) {
        fun AbState.add(st: TxStatus, d: Int) = when (st) {
            TxStatus.DELIVERED -> copy(delivered = delivered + d)
            TxStatus.RELAY_OBSERVED -> copy(relayObserved = relayObserved + d)
            TxStatus.REJECTED -> copy(rejected = rejected + d)
            TxStatus.NO_ACK -> copy(noAck = noAck + d)
            TxStatus.NOT_TRANSMITTED -> copy(notTransmitted = notTransmitted + d)
            TxStatus.UNKNOWN -> copy(unknown = unknown + d)
            TxStatus.SENT -> this
        }
        publish(state.add(prev, -1).add(new, +1))
    }

    private fun recordAirtimeEnd(id: String) {
        val m = metrics()?.takeIf { it.freshAt(clock.wallMs()) }
        store.setTestSessionAirtimeEnd(id, m?.airUtilTxPct, m?.channelUtilPct)
    }

    private fun newSession(peer: Long, mode: TestMode, intervalS: Int?, durationS: Int?): TestSession {
        val m = metrics()?.takeIf { it.freshAt(clock.wallMs()) }
        val s = TestSession(newId(), TestRole.A_SURVEY, mode, peer, radio.info.myNodeNum, clock.wallMs(), null,
            intervalS, durationS, null, source(), preset = radio.info.preset, deliveryMode = state.mode,
            participation = state.participation, refCode = state.refCode, airUtilTxStart = m?.airUtilTxPct, chUtilStart = m?.channelUtilPct)
        store.insertTestSession(s)
        seq = 0
        publish(state.copy(session = s, sent = 0, delivered = 0, relayObserved = 0, rejected = 0, noAck = 0, notTransmitted = 0, unknown = 0))
        return s
    }

    private fun endManualSession() {
        val s = state.session ?: return
        if (s.mode == TestMode.MANUAL) {
            recordAirtimeEnd(s.id)
            store.endTestSession(s.id, clock.wallMs(), "manual session closed")
            publish(state.copy(session = null))
        }
    }

    private fun transmit(s: TestSession) {
        val thisSeq = seq++
        val payload = TestPayload(s.id, thisSeq, clock.wallMs() / 1000, s.refCode).encode()
        val f = fix()
        val age = fixAgeMs(f)
        val pos = f?.takeIf { (age ?: Long.MAX_VALUE) <= 10_000 }
        val acked = state.mode == DeliveryMode.ACKED
        // DIRECT_ONLY: hop_limit 0 without want_ack is left at 0 by the firmware (Router::sendLocal only
        // substitutes the default when want_ack is set and hop_limit == 0).
        val hop = if (acked) state.hopLimit else 0
        val id = radio.sendData(s.peerNode, Port.PRIVATE, payload, wantAck = acked, hopLimit = hop)
        lastSendMono = clock.monotonicMs()
        val status = when {
            id == null -> TxStatus.NOT_TRANSMITTED
            acked -> TxStatus.SENT
            else -> TxStatus.UNKNOWN // no ACK requested: only Device B's log can confirm
        }
        val fq = FrequencyCalc.from(radio.info)
        val b = budget()
        val row = store.insertTestTx(TestTx(testSessionId = s.id, seq = thisSeq, sentMs = clock.wallMs(), packetId = id,
            toNode = s.peerNode, fromNode = radio.info.myNodeNum, lat = pos?.lat, lon = pos?.lon, accuracyM = pos?.accuracyM,
            fixAgeMs = age, wantAck = acked, status = status, statusMs = clock.wallMs(), routingError = null, source = source(),
            deliveryMode = state.mode, hopLimit = hop ?: radio.info.hopLimit, preset = radio.info.preset,
            frequencyMhz = fq.mhz, frequencySource = fq.source.name, airtimeMs = b?.airtimePerTransmissionMs))
        if (id != null && acked) pending[id] = Pending(row, s.peerNode, clock.monotonicMs(), TxStatus.SENT)
        if (id != null) { recentSent[id] = row; while (recentSent.size > 64) recentSent.remove(recentSent.keys.first()) }
        var st = state.copy(sent = state.sent + 1, message = if (id == null) "Radio did not accept the test packet" else "Test #$thisSeq sent (${radio.info.preset ?: "preset ?"})")
        if (status == TxStatus.NOT_TRANSMITTED) st = st.copy(notTransmitted = st.notTransmitted + 1)
        if (status == TxStatus.UNKNOWN) st = st.copy(unknown = st.unknown + 1)
        publish(st)
    }
}

data class ReferenceState(
    val session: TestSession? = null,
    val received: Int = 0,
    val ignoredNoSession: Int = 0,
    val ignoredWrongCode: Int = 0,
    val remainingS: Int? = null,
)

/**
 * Device B (reference) role. B participates only explicitly: its operator starts a finite reference
 * session, which shows a 6-digit participation code to give to Device A's operator (in person, phone, text).
 * Only tests addressed to this radio AND carrying that code, during the session, are recorded.
 * B never transmits test traffic; in ACK mode its firmware sends the routing ACK itself.
 */
class ReferenceController(
    private val store: Store,
    private val clock: Clock,
    private val fix: () -> Fix?,
    private val source: () -> Source,
    private val radioInfo: () -> meshsurvey.core.radio.RadioInfo,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    companion object {
        const val DEFAULT_DURATION_S = 2 * 3600
        const val MAX_DURATION_S = 8 * 3600
    }
    var state = ReferenceState(); private set
    private val listeners = ArrayList<(TestRx) -> Unit>()
    private val stateListeners = ArrayList<(ReferenceState) -> Unit>()
    fun addListener(l: (TestRx) -> Unit) { listeners += l }
    fun addStateListener(l: (ReferenceState) -> Unit) { stateListeners += l }
    val received get() = state.received
    private fun publish(s: ReferenceState) { state = s; stateListeners.forEach { it(s) } }

    fun start(durationS: Int = DEFAULT_DURATION_S): String? {
        if (state.session != null) return "A reference session is already running"
        if (durationS !in 60..MAX_DURATION_S) return "Duration must be 1 min – ${MAX_DURATION_S / 3600} h"
        val info = radioInfo()
        val code = random.nextInt(100_000, 1_000_000)
        val now = clock.wallMs()
        val s = TestSession(newId(random), TestRole.B_REFERENCE, TestMode.MANUAL, 0L, info.myNodeNum, now, null, null, durationS, null,
            source(), preset = info.preset, participation = Participation.CODE, refCode = code, plannedEndMs = now + durationS * 1000L)
        store.insertTestSession(s)
        publish(ReferenceState(session = s, remainingS = durationS))
        return null
    }

    fun stop(reason: String = "stopped by user") {
        val s = state.session ?: return
        store.endTestSession(s.id, clock.wallMs(), reason)
        publish(state.copy(session = null, remainingS = null))
    }

    fun tick() {
        val s = state.session ?: return
        val end = s.plannedEndMs ?: return
        val now = clock.wallMs()
        if (now >= end) stop("duration reached") else publish(state.copy(remainingS = ((end - now + 999) / 1000).toInt()))
    }

    fun onPacket(rx: ReceivedPacket, myNode: Long?): TestRx? {
        val p = rx.packet
        if (p.portnum != Port.PRIVATE) return null
        val tp = TestPayload.decode(p.payload) ?: return null
        if (myNode != null && (p.to != myNode || p.from == myNode)) return null
        val s = state.session
        if (s == null) { publish(state.copy(ignoredNoSession = state.ignoredNoSession + 1)); return null }
        if (tp.refCode == null || tp.refCode != s.refCode) { publish(state.copy(ignoredWrongCode = state.ignoredWrongCode + 1)); return null }
        val info = radioInfo()
        val f = fix()
        val fq = FrequencyCalc.from(info)
        val r = TestRx(testSessionId = tp.sessionId, seq = tp.seq, fromNode = p.from, toNode = p.to, packetId = p.id,
            rxMs = rx.phoneTimeMs, senderTimeS = tp.senderTimeS, rssi = p.rssi, snr = p.snr?.toDouble(),
            hopStart = p.hopStart, hopLimit = p.hopLimit, relayByte = p.relayByte,
            lat = f?.lat, lon = f?.lon, accuracyM = f?.accuracyM, source = source(),
            preset = info.preset, frequencyMhz = fq.mhz, frequencySource = fq.source.name, refSessionId = s.id)
        if (store.insertTestRx(r)) { publish(state.copy(received = state.received + 1)); listeners.forEach { it(r) } }
        return r
    }
}

enum class Outcome(val label: String) {
    RECEIVED_AT_B("Received at B (measured by B)"),
    DELIVERED_ACK_ONLY("Destination ACK; B's log does not cover that time"),
    ACK_BUT_NOT_LOGGED("Destination ACK, but B's app did not log it"),
    REJECTED_BY_B("B's radio received it but could not decode it (NAK)"),
    NOT_RECEIVED("Not received (B was logging)"),
    RELAY_ONLY("Relay observed, no destination ACK, B log unavailable"),
    NO_ACK("No ACK after firmware retries, B log unavailable"),
    NOT_TRANSMITTED("Not transmitted by A's radio"),
    UNKNOWN("Unknown"),
}

data class CompareRow(val tx: TestTx, val rx: TestRx?, val outcome: Outcome)

data class CompareResult(val rows: List<CompareRow>) {
    fun count(o: Outcome) = rows.count { it.outcome == o }
    val sent get() = rows.count { it.outcome != Outcome.NOT_TRANSMITTED }
    /** Ratio over transmitted tests whose fate is established by B's log. Null if none. */
    val measuredDeliveryRatio: Double? get() {
        val rec = count(Outcome.RECEIVED_AT_B)
        val known = rec + count(Outcome.NOT_RECEIVED) + count(Outcome.ACK_BUT_NOT_LOGGED)
        return if (known == 0) null else rec.toDouble() / known
    }
}

/** Compares Device A's transmissions with Device B's imported reception log. No over-the-air reporting needed. */
object SessionCompare {
    /**
     * @param bWindows B's radio-connected windows. @param bRefSessions B's reference sessions (imported);
     * when given, "B was logging" also requires an active reference session covering the test.
     */
    fun compare(tx: List<TestTx>, rx: List<TestRx>, bWindows: List<LoggingWindow>, ackTimeoutMs: Long = 90_000,
                bRefSessions: List<TestSession>? = null): CompareResult {
        val byKey = HashMap<Pair<String, Int>, TestRx>()
        for (r in rx) byKey.getOrPut(r.testSessionId to r.seq) { r }
        val rows = tx.map { t ->
            val r = byKey[t.testSessionId to t.seq]?.takeIf { t.fromNode == null || it.fromNode == t.fromNode }
            val connected = bWindows.any { it.startMs <= t.sentMs && it.endMs >= t.sentMs + ackTimeoutMs }
            val inSession = bRefSessions == null || bRefSessions.any { rs ->
                val end = rs.endedMs ?: rs.plannedEndMs ?: Long.MAX_VALUE
                rs.startedMs <= t.sentMs && end >= t.sentMs + ackTimeoutMs
            }
            val bLogging = connected && inSession
            val o = when {
                r != null -> Outcome.RECEIVED_AT_B
                t.status == TxStatus.NOT_TRANSMITTED -> Outcome.NOT_TRANSMITTED
                t.status == TxStatus.REJECTED -> Outcome.REJECTED_BY_B
                t.status == TxStatus.DELIVERED && bLogging -> Outcome.ACK_BUT_NOT_LOGGED
                t.status == TxStatus.DELIVERED -> Outcome.DELIVERED_ACK_ONLY
                bLogging -> Outcome.NOT_RECEIVED
                t.status == TxStatus.RELAY_OBSERVED -> Outcome.RELAY_ONLY
                t.status == TxStatus.NO_ACK -> Outcome.NO_ACK
                else -> Outcome.UNKNOWN
            }
            CompareRow(t, r, o)
        }
        return CompareResult(rows)
    }
}
