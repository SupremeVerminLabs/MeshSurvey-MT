package meshsurvey.core.testsupport

import kotlinx.serialization.json.*
import meshsurvey.core.store.SqlDriver
import meshsurvey.core.store.SqlRow
import java.io.BufferedReader
import java.io.BufferedWriter

/** Test-only SqlDriver that talks to real SQLite through tools/sqlite_bridge.py. */
class PythonSqlDriver(path: String = ":memory:") : SqlDriver {
    private val proc = ProcessBuilder("python3", System.getProperty("bridge.script", "tools/sqlite_bridge.py"), path)
        .redirectErrorStream(false).start()
    private val w: BufferedWriter = proc.outputStream.bufferedWriter()
    private val r: BufferedReader = proc.inputStream.bufferedReader()
    private var depth = 0

    private fun call(op: String, sql: String, args: List<Any?>): JsonObject {
        val req = buildJsonObject {
            put("op", op); put("sql", sql)
            put("args", JsonArray(args.map { a -> when (a) {
                null -> JsonNull; is Number -> JsonPrimitive(a); is Boolean -> JsonPrimitive(if (a) 1 else 0); else -> JsonPrimitive(a.toString())
            } }))
        }
        w.write(req.toString()); w.newLine(); w.flush()
        val resp = Json.parseToJsonElement(r.readLine() ?: error("bridge died")).jsonObject
        resp["error"]?.let { throw IllegalStateException("SQL error: ${it.jsonPrimitive.content}\n$sql") }
        return resp
    }

    override fun execute(sql: String, args: List<Any?>): Int = call("exec", sql, args)["changes"]!!.jsonPrimitive.int
    override fun insert(sql: String, args: List<Any?>): Long = call("insert", sql, args)["rowid"]!!.jsonPrimitive.long
    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        call("query", sql, args)["rows"]!!.jsonArray.map { row ->
            val cells = row.jsonArray
            map(object : SqlRow {
                fun p(i: Int) = (cells[i] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                override fun long(i: Int) = p(i)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
                override fun double(i: Int) = p(i)?.doubleOrNull
                override fun string(i: Int) = p(i)?.content
                override fun blob(i: Int): ByteArray? = (cells[i] as? JsonObject)?.get("b64")?.jsonPrimitive?.content
                    ?.let { java.util.Base64.getDecoder().decode(it) }
            })
        }
    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) execute("BEGIN")
        try { val v = block(); if (--depth == 0) execute("COMMIT"); return v }
        catch (e: Throwable) { if (--depth == 0) execute("ROLLBACK"); throw e }
    }
    override fun close() { w.close(); proc.waitFor() }
}

fun testDriver(): SqlDriver = PythonSqlDriver()

fun fileTestDriver(path: String): SqlDriver = PythonSqlDriver(path)
