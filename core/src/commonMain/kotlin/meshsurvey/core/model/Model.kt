package meshsurvey.core.model

/**
 * Data categories are kept in separate types/tables (spec §6):
 * physical reception observations, controlled test transmissions,
 * confirmed test receptions, survey sessions, track points, registered devices, imports.
 * Every record carries a [Source]. Unknown values are null, never defaulted.
 */
enum class Source {
    /** Measured by a real radio connected to this app instance. */
    LOCAL_RF,
    /** Came from another instance's exported log (e.g. Device B's session file). */
    IMPORTED,
    /** Produced by the simulated radio. Never RF evidence. */
    SIMULATED,
}

/** How the reception reached the app. */
enum class RxStatus {
    /** Decoded by the node (it holds the channel key). */
    DECODED,
    /** Heard but not decryptable by the node; only header metadata is available. */
    ENCRYPTED,
    /** Delivered late from the node's queue (e.g. after a reconnect); no receiver position is attached. */
    QUEUED_LATE,
}

enum class PacketType {
    TEXT, POSITION, NODEINFO, ROUTING, ADMIN, TELEMETRY, TRACEROUTE, NEIGHBORINFO, MAP_REPORT,
    RANGE_TEST, WAYPOINT, STORE_FORWARD, PAXCOUNTER, DETECTION_SENSOR, SURVEY_TEST, PRIVATE, ENCRYPTED, OTHER;

    companion object {
        fun of(port: Int?): PacketType = when (port) {
            null -> ENCRYPTED
            1, 7 -> TEXT
            3 -> POSITION
            4 -> NODEINFO
            5 -> ROUTING
            6 -> ADMIN
            8 -> WAYPOINT
            10 -> DETECTION_SENSOR
            34 -> PAXCOUNTER
            65 -> STORE_FORWARD
            66 -> RANGE_TEST
            67 -> TELEMETRY
            70 -> TRACEROUTE
            71 -> NEIGHBORINFO
            73 -> MAP_REPORT
            256 -> PRIVATE
            else -> OTHER
        }
    }
}

/** MOCK = Android reported the location as coming from a mock provider; kept but tagged, never trusted silently. */
/** RADIO = the connected radio's own position (its GPS or its configured fixed position); OS_LOCATION = the operating system's location service (Windows). */
enum class FixSource { GPS, NETWORK, NMEA_SERIAL, MANUAL, SIMULATED, MOCK, RADIO, OS_LOCATION }

/** A receiver position fix. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val wallMs: Long,
    val monotonicMs: Long,
    val source: FixSource,
)

enum class SessionStatus { ACTIVE, ENDED, INTERRUPTED }

/** A passive survey (logging) session. */
data class SurveySession(
    val id: String,
    val startedMs: Long,
    val endedMs: Long?,
    val status: SessionStatus,
    val receiverNode: Long?,
    val linkKind: String?,
    val firmware: String?,
    val preset: String?,
    val region: String?,
    val source: Source,
    /** Stock firmware suppresses duplicate receptions before the client sees them. */
    val duplicatesSuppressed: Boolean = true,
    val notes: String? = null,
)

/** One physical reception reported by the connected radio. Never merged by packet id. */
data class Observation(
    val id: Long = 0,
    val sessionId: String?,
    val receiverNode: Long?,
    val rxMs: Long,
    val nodeRxTime: Long?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val fixAgeMs: Long?,
    val rssi: Int?,
    val snr: Double?,
    val packetId: Long,
    val fromNode: Long,
    val toNode: Long,
    val channel: Int?,
    val portnum: Int?,
    val type: PacketType,
    val status: RxStatus,
    val hopStart: Int?,
    val hopLimit: Int?,
    val hopsAway: Int?,
    /** Raw relay_node byte from the packet header; NOT resolved to a specific node. */
    val relayByte: Int?,
    val payloadLen: Int?,
    val preset: String?,
    val region: String?,
    /** Only set when the radio reports an explicit frequency override; otherwise unknown. */
    val frequencyMhz: Double?,
    /** Decoded summary (never text-message content); null unless the user enables summaries. */
    val summary: String?,
    val source: Source,
    val originDevice: String? = null,
    val frequencySource: String? = null,
    val fixSource: String? = null,
)

data class TrackPoint(
    val id: Long = 0,
    val sessionId: String?,
    val ms: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    /** Radio link was ready when this point was taken (so absence of receptions is meaningful). */
    val radioReady: Boolean,
    val source: Source,
    val originDevice: String? = null,
)

/** A device the user owns/controls and explicitly registered for A/B tests. */
data class TestDevice(val nodeNum: Long, val label: String, val addedMs: Long)

enum class TestRole { A_SURVEY, B_REFERENCE }
enum class TestMode { MANUAL, AUTO }

data class TestSession(
    val id: String,
    val role: TestRole,
    val mode: TestMode,
    val peerNode: Long,
    val localNode: Long?,
    val startedMs: Long,
    val endedMs: Long?,
    val intervalS: Int?,
    val durationS: Int?,
    val endReason: String?,
    val source: Source,
    val originDevice: String? = null,
    val preset: String? = null,
    val deliveryMode: DeliveryMode? = null,
    val participation: Participation? = null,
    /** A: B's participation code entered for this session. B: the code this reference session displays. */
    val refCode: Int? = null,
    /** Radio-reported airtime (% over the radio's own window), snapshots at session start/end. Null if not reported. */
    val airUtilTxStart: Double? = null,
    val chUtilStart: Double? = null,
    val airUtilTxEnd: Double? = null,
    val chUtilEnd: Double? = null,
    /** B reference sessions: when the session is scheduled to end. */
    val plannedEndMs: Long? = null,
)

/**
 * How Device B's participation in a test session is established.
 * CODE: B's operator started a reference session in MeshSurvey and gave A its code (verified when B's log is imported).
 * OPERATOR_CONTROLS_B: B is a stock radio the operator of A controls; participation is asserted, not verifiable.
 */
enum class Participation { CODE, OPERATOR_CONTROLS_B }

/**
 * Outcome of one controlled transmission as reported to Device A by its own radio.
 * Semantics verified against firmware source (ReliableRouter / NextHopRouter / Router), see
 * docs/FIRMWARE_VERIFICATION.md. Only DELIVERED (or Device B's own log) proves delivery.
 */
enum class TxStatus {
    /** Handed to the radio; no routing report yet. */
    SENT,
    /** Routing ACK (error NONE) from the destination node itself: delivered. */
    DELIVERED,
    /**
     * A retransmission of our packet was observed (implicit ACK: our node heard it rebroadcast,
     * or a next-hop relay sent a 0-hop ACK). Not proof of delivery to B.
     */
    RELAY_OBSERVED,
    /** NAK from the destination (e.g. NO_CHANNEL, PKI_UNKNOWN_PUBKEY): B's radio received it but could not decode it. */
    REJECTED,
    /** Our radio exhausted its retransmissions without an ACK (MAX_RETRANSMIT): delivery unknown, likely not delivered. */
    NO_ACK,
    /** Our radio refused to transmit (DUTY_CYCLE_LIMIT, NO_INTERFACE, TOO_LARGE, BAD_REQUEST, …) or did not accept the packet. */
    NOT_TRANSMITTED,
    /** No ACK requested (direct-only test), or no report before the timeout: delivery unknown. */
    UNKNOWN,
}

enum class DeliveryMode {
    /** want_ack, firmware retries (up to 3 transmissions), relays allowed. Works with a stock Device B. */
    ACKED,
    /** hop_limit 0, no ACK, exactly one transmission, no relaying. Only Device B's log can confirm reception. */
    DIRECT_ONLY,
}

data class TestTx(
    val id: Long = 0,
    val testSessionId: String,
    val seq: Int,
    val sentMs: Long,
    val packetId: Long?,
    val toNode: Long,
    val fromNode: Long?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val fixAgeMs: Long?,
    val wantAck: Boolean,
    val status: TxStatus,
    val statusMs: Long?,
    val routingError: String?,
    val source: Source,
    val originDevice: String? = null,
    val deliveryMode: DeliveryMode? = null,
    val hopLimit: Int? = null,
    val preset: String? = null,
    val frequencyMhz: Double? = null,
    val frequencySource: String? = null,
    val airtimeMs: Double? = null,
)

data class TestRx(
    val id: Long = 0,
    val testSessionId: String,
    val seq: Int,
    val fromNode: Long,
    val toNode: Long?,
    val packetId: Long,
    val rxMs: Long,
    val senderTimeS: Long?,
    val rssi: Int?,
    val snr: Double?,
    val hopStart: Int?,
    val hopLimit: Int?,
    val relayByte: Int?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val source: Source,
    val originDevice: String? = null,
    val preset: String? = null,
    val frequencyMhz: Double? = null,
    val frequencySource: String? = null,
    /** B's reference session in which this test was accepted. */
    val refSessionId: String? = null,
)

/** Interval during which a device's app was connected to its radio (used to judge "missing" receptions). */
data class LoggingWindow(val startMs: Long, val endMs: Long, val originDevice: String?)

data class ImportRecord(val sha256: String, val importedMs: Long, val originDevice: String?, val kind: String, val rows: Int)

enum class PresetChangeStatus {
    /** set_config sent; waiting for the radio to come back with the target preset. */
    APPLYING,
    /** Radio confirmed running the temporary test preset. */
    ACTIVE,
    /** Target preset not confirmed within the timeout. */
    APPLY_UNCONFIRMED,
    /** Original LoRa config sent back; waiting for confirmation. */
    RESTORING,
    /** Radio confirmed back on the exact original LoRa config. */
    RESTORED,
    /** Restore not confirmed within the timeout — user must check the radio. */
    RESTORE_UNCONFIRMED,
}

/** A user-approved temporary preset change, with the byte-exact original LoRa config kept for restore. */
data class PresetChange(
    val id: Long = 0,
    val nodeNum: Long,
    val createdMs: Long,
    val originalRaw: ByteArray,
    val tempRaw: ByteArray,
    val originalPreset: String?,
    val targetPreset: String,
    val status: PresetChangeStatus,
    val statusMs: Long,
    val autoRestore: Boolean,
    val note: String? = null,
) {
    val isOpen get() = status != PresetChangeStatus.RESTORED
}

/**
 * Timing anomalies detected while running: the app was suspended (TICK_GAP, e.g. Android Doze or a
 * paused process) or the wall clock jumped relative to the monotonic clock (CLOCK_JUMP).
 */
enum class ClockEventKind { TICK_GAP, CLOCK_JUMP }

data class ClockEvent(val id: Long = 0, val wallMs: Long, val kind: ClockEventKind, val magnitudeMs: Long, val sessionId: String?)
