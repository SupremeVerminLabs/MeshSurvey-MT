@file:Suppress("PackageDirectoryMismatch")
package kotlin.test
// Minimal kotlin.test facade over JUnit 4, for local verification only.
// Real Gradle builds use the official kotlin-test artifact.
typealias Test = org.junit.Test
fun assertEquals(expected: Any?, actual: Any?, message: String? = null) = org.junit.Assert.assertEquals(message, expected, actual)
fun assertNotEquals(illegal: Any?, actual: Any?, message: String? = null) = org.junit.Assert.assertNotEquals(message, illegal, actual)
fun assertTrue(actual: Boolean, message: String? = null) = org.junit.Assert.assertTrue(message, actual)
fun assertFalse(actual: Boolean, message: String? = null) = org.junit.Assert.assertFalse(message, actual)
fun <T : Any> assertNotNull(actual: T?, message: String? = null): T { org.junit.Assert.assertNotNull(message, actual); return actual!! }
fun assertNull(actual: Any?, message: String? = null) = org.junit.Assert.assertNull(message, actual)
fun fail(message: String? = null): Nothing { org.junit.Assert.fail(message); throw AssertionError(message) }
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try { block() } catch (e: Throwable) { if (e is T) return e; throw AssertionError("expected ${T::class.simpleName}, got $e") }
    throw AssertionError("expected ${T::class.simpleName} to be thrown")
}
fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double, message: String? = null) =
    org.junit.Assert.assertEquals(message, expected, actual, absoluteTolerance)
