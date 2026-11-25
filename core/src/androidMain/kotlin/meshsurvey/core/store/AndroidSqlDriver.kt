package meshsurvey.core.store

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Android SqlDriver over the platform SQLite. Schema/migrations are handled by SqlStore (PRAGMA user_version). */
class AndroidSqlDriver private constructor(private val db: SQLiteDatabase) : SqlDriver {
    constructor(context: Context, name: String = "meshsurvey.db") :
        this(context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            enableWriteAheadLogging()
            // every committed row must survive process death, power loss and reboots
            rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
        })

    companion object {
        /** Opens an existing SQLite file (e.g. an MBTiles map) read-only. */
        fun openReadOnly(path: String) = AndroidSqlDriver(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
    }

    private fun bindArgs(args: List<Any?>): Array<Any?> = args.map {
        when (it) { is Boolean -> if (it) 1L else 0L; is Int -> it.toLong(); is Float -> it.toDouble(); else -> it }
    }.toTypedArray()

    override fun execute(sql: String, args: List<Any?>): Int {
        val head = sql.trimStart().take(6).uppercase()
        if (head.startsWith("PRAGMA") || head.startsWith("CREATE") || head.startsWith("BEGIN") || head.startsWith("COMMIT")) {
            if (head.startsWith("PRAGMA") && !sql.contains("=")) { db.rawQuery(sql, null).use { it.moveToFirst() }; return 0 }
            db.execSQL(sql, bindArgs(args)); return 0
        }
        val st = db.compileStatement(sql)
        bindArgs(args).forEachIndexed { i, a -> bindOne(st, i + 1, a) }
        return st.executeUpdateDelete()
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        val st = db.compileStatement(sql)
        bindArgs(args).forEachIndexed { i, a -> bindOne(st, i + 1, a) }
        return st.executeInsert() // -1 when OR IGNORE skipped the row
    }

    private fun bindOne(st: android.database.sqlite.SQLiteStatement, idx: Int, a: Any?) {
        when (a) {
            null -> st.bindNull(idx)
            is Long -> st.bindLong(idx, a)
            is Double -> st.bindDouble(idx, a)
            is ByteArray -> st.bindBlob(idx, a)
            else -> st.bindString(idx, a.toString())
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        // rawQuery only binds strings; SQLite's type affinity makes numeric comparisons work for our schema.
        val sargs = args.map { a -> when (a) { null -> null; is Boolean -> if (a) "1" else "0"; else -> a.toString() } }.toTypedArray()
        return db.rawQueryWithFactory({ _, driver, editTable, q ->
            sargs.forEachIndexed { i, a -> if (a == null) q.bindNull(i + 1) else bindTyped(q, i + 1, args[i]) }
            android.database.sqlite.SQLiteCursor(driver, editTable, q)
        }, sql, null, "").use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>()
            while (c.moveToNext()) out += map(row)
            out
        }
    }

    private fun bindTyped(q: android.database.sqlite.SQLiteQuery, idx: Int, a: Any?) {
        when (a) {
            is Long -> q.bindLong(idx, a)
            is Int -> q.bindLong(idx, a.toLong())
            is Double -> q.bindDouble(idx, a)
            is Float -> q.bindDouble(idx, a.toDouble())
            is Boolean -> q.bindLong(idx, if (a) 1 else 0)
            else -> q.bindString(idx, a.toString())
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val v = block(); db.setTransactionSuccessful(); return v } finally { db.endTransaction() }
    }

    override fun close() = db.close()

    private class CursorRow(val c: Cursor) : SqlRow {
        override fun long(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
        override fun double(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
        override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
        override fun blob(i: Int): ByteArray? = if (c.isNull(i)) null else c.getBlob(i)
    }
}
