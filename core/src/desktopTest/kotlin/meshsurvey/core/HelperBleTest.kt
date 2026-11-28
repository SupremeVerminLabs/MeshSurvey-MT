package meshsurvey.core

import meshsurvey.core.radio.BleHelperScan
import meshsurvey.core.radio.BleScanResult
import meshsurvey.core.radio.HelperBleLink
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.radio.RadioState
import meshsurvey.core.util.ManualClock
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Windows Bluetooth helper's line protocol, exercised against tools/fake-ble-helper.py.
 * This tests the app side of the protocol only. It does not test Windows Bluetooth or a radio.
 */
class HelperBleTest {
    private val script = listOf("tools/fake-ble-helper.py", "../tools/fake-ble-helper.py").map(::File).firstOrNull { it.exists() }
    private val python = listOf("python3", "python").firstOrNull { p ->
        runCatching { ProcessBuilder(p, "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    }
    private fun cmd(mode: String) = listOf(python!!, script!!.absolutePath, mode)
    private fun available() = (script != null && python != null).also { if (!it) println("HelperBleTest skipped: python3 or fake helper missing") }

    @Test fun scanListsRadiosAndPairedFlag() {
        if (!available()) return
        val res = BleHelperScan.scan(cmd("normal"), seconds = 1)
        assertTrue(res is BleScanResult.Found, res.toString())
        val r = res as BleScanResult.Found
        assertEquals(2, r.devices.size)
        assertEquals("Meshtastic_ee01", r.devices[0].name); assertEquals(-61, r.devices[0].rssi); assertEquals(false, r.devices[0].paired)
        assertEquals("RAK4631 base", r.devices[1].name); assertEquals(null, r.devices[1].rssi); assertEquals(true, r.devices[1].paired)
    }

    @Test fun adapterOffIsReportedNotHidden() {
        if (!available()) return
        val r = BleHelperScan.scan(cmd("adapter-off"), seconds = 1)
        assertTrue(r is BleScanResult.Unavailable && r.reason.contains("turned off"), r.toString())
    }

    /** Runs a RadioClient on one thread, as the app does, with the link posting into it. */
    private class Harness {
        val ex = Executors.newSingleThreadExecutor()
        val rc = ex.submit(Callable { RadioClient(ManualClock()) }).get()
        val states = java.util.Collections.synchronizedList(ArrayList<Pair<RadioState, String>>())
        init { ex.submit { rc.addListener(object : RadioListener { override fun onRadioState(state: RadioState, detail: String) { states += state to detail } }) }.get() }
        val post: (Runnable) -> Unit = { runCatching { ex.execute(it) } }  // late helper output after shutdown is ignored
        fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) { if (ex.submit(Callable { cond() }).get()) return true; Thread.sleep(50) }
            return false
        }
        fun close() { ex.submit { rc.disconnect() }.get(); ex.shutdown() }
    }

    @Test fun pairsConnectsAndCompletesHandshake() {
        if (!available()) return
        val h = Harness()
        h.ex.submit { h.rc.connect(HelperBleLink(cmd("normal"), "AA:BB:CC:DD:EE:02", "RAK4631 base", "123456", h.post)) }.get()
        assertTrue(h.waitFor { h.rc.state == RadioState.READY }, "states: ${h.states}")
        assertEquals(0x1234ABCDL, h.ex.submit(Callable { h.rc.info.myNodeNum }).get())
        assertTrue(h.states.any { it.second.contains("pairing") })
        h.close()
    }

    @Test fun wrongPinFailsWithAUsefulMessage() {
        if (!available()) return
        val h = Harness()
        h.ex.submit { h.rc.connect(HelperBleLink(cmd("bad-pin"), "AA:BB:CC:DD:EE:02", "x", "000000", h.post)) }.get()
        assertTrue(h.waitFor { h.rc.state == RadioState.FAILED }, "states: ${h.states}")
        assertTrue(h.states.last().second.contains("PIN"))
        h.close()
    }

    @Test fun lostLinkIsReportedAsDisconnected() {
        if (!available()) return
        val h = Harness()
        h.ex.submit { h.rc.connect(HelperBleLink(cmd("drop-after-config"), "AA:BB:CC:DD:EE:01", "x", null, h.post)) }.get()
        assertTrue(h.waitFor { h.states.any { it.first == RadioState.DISCONNECTED && it.second.contains("lost") } }, "states: ${h.states}")
        h.close()
    }

    @Test fun helperCrashIsAFailureNotASilentHang() {
        if (!available()) return
        val h = Harness()
        h.ex.submit { h.rc.connect(HelperBleLink(cmd("crash"), "AA:BB:CC:DD:EE:01", "x", null, h.post)) }.get()
        assertTrue(h.waitFor { h.rc.state == RadioState.FAILED && h.states.last().second.contains("helper stopped") }, "states: ${h.states}")
        h.close()
    }

    @Test fun missingHelperFailsCleanly() {
        val h = Harness()
        h.ex.submit { h.rc.connect(HelperBleLink(listOf("/nonexistent/meshsurvey-ble.exe"), "AA:BB:CC:DD:EE:01", "x", null, h.post)) }.get()
        assertTrue(h.waitFor { h.rc.state == RadioState.FAILED }, "states: ${h.states}")
        h.close()
    }
}

class OsLocationTest {
    @Test fun parsesFreshWifiFixAndRejectsCoarseOrStale() {
        val now = 1_800_000_000_000L
        val ok = meshsurvey.core.radio.OsLocation.parse("pos 37.9779 -122.0311 35.0 12.0 ${now - 2000} 2", now, 500_000)
        assertTrue(ok is meshsurvey.core.radio.OsLocation.Result.Ok, ok.toString())
        val f = (ok as meshsurvey.core.radio.OsLocation.Result.Ok).fix
        assertEquals(37.9779, f.lat); assertEquals(35.0, f.accuracyM); assertEquals(null, f.altitudeM)   // altitude only from satellite
        assertEquals(now - 2000, f.wallMs); assertEquals(498_000L, f.monotonicMs)
        assertEquals(meshsurvey.core.model.FixSource.OS_LOCATION, f.source)
        assertTrue(meshsurvey.core.radio.OsLocation.parse("pos 1 2 3000 0 $now 3", now, 0) is meshsurvey.core.radio.OsLocation.Result.Rejected)
        assertTrue(meshsurvey.core.radio.OsLocation.parse("pos 1 2 5 0 ${now - 60_000} 1", now, 0) is meshsurvey.core.radio.OsLocation.Result.Rejected)
        assertEquals(null, meshsurvey.core.radio.OsLocation.parse("rx 0a0b", now, 0))
    }

    @Test fun scanLinesCarryTheProtocol() {
        val mc = meshsurvey.core.radio.BleHelperScan.parseDevice("devmc AA:BB:CC:DD:EE:03 -70 0 MeshCore-Base")!!
        kotlin.test.assertEquals(meshsurvey.core.radio.MeshProtocol.MESHCORE, mc.protocol)
        kotlin.test.assertEquals("MeshCore-Base", mc.name)
        val mt = meshsurvey.core.radio.BleHelperScan.parseDevice("dev AA:BB:CC:DD:EE:01 -61 0 Meshtastic_ee01")!!
        kotlin.test.assertEquals(meshsurvey.core.radio.MeshProtocol.MESHTASTIC, mt.protocol)
        kotlin.test.assertNull(meshsurvey.core.radio.BleHelperScan.parseDevice("dev AA:BB:CC:DD:EE:02 - 1 RAK4631 base")!!.protocol)
    }
}
