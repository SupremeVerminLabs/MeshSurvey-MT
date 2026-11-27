package meshsurvey.desktop

import java.io.File

/** Locates the bundled Windows Bluetooth helper (meshsurvey-ble.exe, built in CI from desktopApp/native/windows-ble). */
object WindowsBle {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun helper(): File? {
        if (!isWindows) return null
        // Override for development builds; otherwise the copy packaged with the app (compose appResources).
        val candidates = listOfNotNull(
            System.getProperty("meshsurvey.bleHelper")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, "meshsurvey-ble.exe") },
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun command(): List<String>? = helper()?.let { listOf(it.absolutePath) }

    fun unavailableReason(): String =
        if (!isWindows) "Bluetooth on desktop is only available on Windows in this release. Use USB or TCP."
        else "The Bluetooth helper (meshsurvey-ble.exe) is missing from this build. Use USB or TCP, or reinstall."
}
