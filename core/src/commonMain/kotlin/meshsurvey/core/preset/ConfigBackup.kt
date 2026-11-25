package meshsurvey.core.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import meshsurvey.core.model.PresetChange
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.util.hexToBytes
import meshsurvey.core.util.toHex

/**
 * Second, independent copy of every temporary preset change (the first is the SQLite row).
 * Implementations MUST make [save] durable before returning (write temp file, fsync, atomic rename)
 * and throw if they cannot — a change is never sent without a durable backup.
 */
interface ConfigBackupStore {
    fun save(c: PresetChange)
    fun loadAll(): List<BackupRecord>
    fun markResolved(nodeNum: Long, createdMs: Long)
}

data class BackupRecord(val change: PresetChange, val resolved: Boolean)

/** Not durable. Used only in tests or if the platform provides nothing better (the UI then warns). */
class MemoryBackupStore : ConfigBackupStore {
    val records = LinkedHashMap<Pair<Long, Long>, BackupRecord>()
    var failNextSave = false
    override fun save(c: PresetChange) {
        if (failNextSave) { failNextSave = false; throw IllegalStateException("simulated backup write failure") }
        records[c.nodeNum to c.createdMs] = BackupRecord(c, false)
    }
    override fun loadAll() = records.values.toList()
    override fun markResolved(nodeNum: Long, createdMs: Long) {
        records[nodeNum to createdMs]?.let { records[nodeNum to createdMs] = it.copy(resolved = true) }
    }
}

object BackupCodec {
    const val FORMAT = "meshsurvey.preset-backup"

    fun encode(c: PresetChange, resolved: Boolean): String = buildJsonObject {
        put("format", FORMAT); put("version", 1)
        put("node_num", c.nodeNum); put("created_ms", c.createdMs)
        put("original_lora_hex", c.originalRaw.toHex()); put("temp_lora_hex", c.tempRaw.toHex())
        put("original_preset", c.originalPreset); put("target_preset", c.targetPreset)
        put("auto_restore", c.autoRestore); put("resolved", resolved)
        put("how_to_restore_manually", PresetManager.manualRestoreText(c))
    }.toString()

    fun decode(text: String): BackupRecord? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        if (s("format") != FORMAT) null else BackupRecord(
            PresetChange(nodeNum = l("node_num")!!, createdMs = l("created_ms")!!,
                originalRaw = s("original_lora_hex")!!.hexToBytes()!!, tempRaw = s("temp_lora_hex")!!.hexToBytes()!!,
                originalPreset = s("original_preset"), targetPreset = s("target_preset")!!,
                status = PresetChangeStatus.APPLY_UNCONFIRMED, statusMs = l("created_ms")!!,
                autoRestore = (o["auto_restore"] as? JsonPrimitive)?.booleanOrNull ?: false,
                note = "recovered from backup file"),
            (o["resolved"] as? JsonPrimitive)?.booleanOrNull ?: false)
    } catch (_: Exception) { null }
}
