package meshsurvey.core.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

/** Desktop SqlDriver over sqlite-jdbc (org.xerial). URL e.g. "jdbc:sqlite:/path/meshsurvey.db". */
class JdbcSqlDriver(url: String, readOnly: Boolean = false) : SqlDriver {
    // sqlite-jdbc reads "open_mode"; 1 = SQLITE_OPEN_READONLY (used for user-supplied MBTiles files)
    private val conn: Connection = DriverManager.getConnection(url,
        java.util.Properties().apply { if (readOnly) setProperty("open_mode", "1") }).apply { autoCommit = true }
    private var depth = 0

    init {
        if (!readOnly) conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys=ON")
            if (!url.contains(":memory:")) { st.execute("PRAGMA journal_mode=WAL"); st.execute("PRAGMA synchronous=FULL") /* survive power loss and host reboots */ }
        }
    }

    private fun bind(ps: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is Long -> ps.setLong(idx, a)
                is Int -> ps.setInt(idx, a)
                is Double -> ps.setDouble(idx, a)
                is Float -> ps.setDouble(idx, a.toDouble())
                is Boolean -> ps.setInt(idx, if (a) 1 else 0)
                is ByteArray -> ps.setBytes(idx, a)
                else -> ps.setString(idx, a.toString())
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int =
        if (sql.trimStart().startsWith("PRAGMA", ignoreCase = true) && args.isEmpty()) {
            conn.createStatement().use { it.execute(sql); 0 }
        } else conn.prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }

    override fun insert(sql: String, args: List<Any?>): Long =
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            bind(ps, args)
            if (ps.executeUpdate() == 0) return -1
            conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) rs.getLong(1) else -1 } }
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val row = RsRow(rs)
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) conn.autoCommit = false
        try {
            val v = block()
            if (--depth == 0) { conn.commit(); conn.autoCommit = true }
            return v
        } catch (e: Throwable) {
            if (--depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw e
        }
    }

    override fun close() = conn.close()

    private class RsRow(val rs: ResultSet) : SqlRow {
        override fun long(i: Int): Long? = rs.getLong(i + 1).let { if (rs.wasNull()) null else it }
        override fun double(i: Int): Double? = rs.getDouble(i + 1).let { if (rs.wasNull()) null else it }
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun blob(i: Int): ByteArray? = rs.getBytes(i + 1)
    }
}
