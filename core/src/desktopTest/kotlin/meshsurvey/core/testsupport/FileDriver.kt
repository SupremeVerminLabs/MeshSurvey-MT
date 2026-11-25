package meshsurvey.core.testsupport

import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.store.SqlDriver

fun fileTestDriver(path: String): SqlDriver = JdbcSqlDriver("jdbc:sqlite:$path")
