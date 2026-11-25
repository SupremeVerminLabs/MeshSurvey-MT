package meshsurvey.core.testsupport

import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.store.SqlDriver

actual fun testDriver(): SqlDriver = JdbcSqlDriver("jdbc:sqlite::memory:")
