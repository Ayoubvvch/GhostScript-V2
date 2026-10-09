package com.ayoub.ghostscript2

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootShell {
    private const val TAG = "GhostRootShell2"

    private val suBinaryCandidates = arrayOf(
        "su",
        "/data/adb/magisk/magisk su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su"
    )

    private var activeSuPrefix: String? = null

    fun isRootAvailable(): Boolean {
        if (activeSuPrefix != null) return true
        for (candidate in suBinaryCandidates) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$candidate -c id"))
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val output = reader.readLine() ?: ""
                p.waitFor()
                if (output.contains("uid=0")) {
                    activeSuPrefix = candidate
                    Log.i(TAG, "Root detected using: $candidate")
                    return true
                }
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        return false
    }

    fun exec(command: String): String {
        val su = activeSuPrefix ?: if (isRootAvailable()) activeSuPrefix!! else "su"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$su -c \"$command\""))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            ""
        }
    }

    fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        val out = exec("kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD")
        return out.contains("ALIVE")
    }

    fun killProcess(pid: Int) {
        if (pid > 0) {
            exec("kill -9 $pid 2>/dev/null")
        }
    }

    /**
     * Installs a convenient CLI wrapper at /data/local/tmp/toast
     * allowing any script, cron, or terminal user to run:
     * toast "Einstein said: Genius is 1% talent and 99% hard work."
     */
    fun setupCliToastWrapper(context: Context) {
        try {
            val scriptContent = """
#!/system/bin/sh
MSG="${'$'}*"
if [ -z "${'$'}MSG" ]; then
    MSG="GhostScript Toast"
fi
am broadcast -a com.ayoub.ghostscript2.SHOW_TOAST --es text "${'$'}MSG" >/dev/null 2>&1
""".trimIndent()
            val target = File("/data/local/tmp/toast")
            exec("cat << 'EOF' > /data/local/tmp/toast\n$scriptContent\nEOF\nchmod 777 /data/local/tmp/toast")
            Log.i(TAG, "CLI toast tool installed at /data/local/tmp/toast")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up CLI toast wrapper", e)
        }
    }
}
