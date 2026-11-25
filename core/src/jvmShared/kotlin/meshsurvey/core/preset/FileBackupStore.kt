package meshsurvey.core.preset

import meshsurvey.core.model.PresetChange
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON file per temporary preset change, outside the database. Written to a temp file,
 * fsync'd, then atomically renamed, so a crash or power loss leaves either the old or the new
 * file, never a partial one. Resolved backups are kept (marked resolved) as an audit trail.
 * Each file contains plain-language manual restore instructions.
 */
class FileBackupStore(private val dir: File) : ConfigBackupStore {
    init { dir.mkdirs() }

    private fun file(node: Long, created: Long) = File(dir, "preset-backup-${node.toString(16).padStart(8, '0')}-$created.json")

    override fun save(c: PresetChange) = write(file(c.nodeNum, c.createdMs), BackupCodec.encode(c, false))

    override fun loadAll(): List<BackupRecord> =
        (dir.listFiles { f -> f.name.startsWith("preset-backup-") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }.mapNotNull { runCatching { BackupCodec.decode(it.readText()) }.getOrNull() }

    override fun markResolved(nodeNum: Long, createdMs: Long) {
        val f = file(nodeNum, createdMs)
        val rec = runCatching { BackupCodec.decode(f.readText()) }.getOrNull() ?: return
        write(f, BackupCodec.encode(rec.change, true))
    }

    private fun write(target: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot create ${dir.absolutePath}")
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(text.encodeToByteArray()); out.fd.sync() }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
