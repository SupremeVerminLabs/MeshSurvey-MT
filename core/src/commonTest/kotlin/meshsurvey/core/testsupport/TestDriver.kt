package meshsurvey.core.testsupport

import meshsurvey.core.store.SqlDriver

/** A fresh, empty in-memory SQLite database for each call. */
expect fun testDriver(): SqlDriver
