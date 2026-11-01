package io.github.washingtongif.foldfix

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runs shell commands with ADB-shell privileges through Shizuku. */
object Shell {

    // Shizuku.newProcess is not public in API 13, but is still the simplest way to
    // get a java.lang.Process running as the shell user.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun start(cmd: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

    /** Runs a command to completion and returns stdout+stderr. */
    fun run(cmd: String): String {
        val p = start("$cmd 2>&1")
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }
}

/** Reads the device states this phone defines (CLOSED, HALF_OPENED, OPENED, ...). */
object DeviceStates {
    private val re = Regex("""identifier=(\d+),\s*name='([^']+)'""")

    fun all(): Map<String, Int> =
        re.findAll(Shell.run("cmd device_state print-states"))
            .associate { it.groupValues[2] to it.groupValues[1].toInt() }

    fun current(): String = Shell.run("cmd device_state print-state")

    fun idFor(name: String, fallback: Int): Int =
        try { all()[name] ?: fallback } catch (e: Throwable) { fallback }
}
