package com.ayoub.ghostscript2

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class GhostTask(
    val id: String,
    val title: String,
    val description: String,
    val type: String, // sleep_timer, distraction_blocker, wisdom_timer, ram_clean, etc.
    var pid: Int = -1,
    val startTime: Long,
    val delaySeconds: Long,
    val targetTime: Long,
    var status: String, // RUNNING, COMPLETED, CANCELLED, FAILED
    val scriptPath: String,
    val logPath: String,
    val pidPath: String,
    val statusPath: String,
    val optionsJson: String = "{}"
) {
    fun toJson(): JSONObject {
        val now = System.currentTimeMillis()
        val remaining = if (status == "RUNNING") max(0L, (targetTime - now) / 1000L) else 0L
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("description", description)
        obj.put("type", type)
        obj.put("pid", pid)
        obj.put("startTime", startTime)
        obj.put("delaySeconds", delaySeconds)
        obj.put("targetTime", targetTime)
        obj.put("remainingSeconds", remaining)
        obj.put("status", status)
        obj.put("logPath", logPath)
        obj.put("options", JSONObject(optionsJson))
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): GhostTask {
            return GhostTask(
                id = obj.getString("id"),
                title = obj.getString("title"),
                description = obj.optString("description", ""),
                type = obj.getString("type"),
                pid = obj.optInt("pid", -1),
                startTime = obj.getLong("startTime"),
                delaySeconds = obj.getLong("delaySeconds"),
                targetTime = obj.getLong("targetTime"),
                status = obj.optString("status", "RUNNING"),
                scriptPath = obj.optString("scriptPath", ""),
                logPath = obj.optString("logPath", ""),
                pidPath = obj.optString("pidPath", ""),
                statusPath = obj.optString("statusPath", ""),
                optionsJson = obj.optJSONObject("options")?.toString() ?: "{}"
            )
        }
    }
}

data class CustomPreset(
    val id: String,
    val name: String,
    val description: String,
    val commands: String,
    val delaySeconds: Long,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("description", description)
        obj.put("commands", commands)
        obj.put("delaySeconds", delaySeconds)
        obj.put("createdAt", createdAt)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): CustomPreset {
            return CustomPreset(
                id = obj.getString("id"),
                name = obj.getString("name"),
                description = obj.optString("description", ""),
                commands = obj.getString("commands"),
                delaySeconds = obj.optLong("delaySeconds", 0L),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

class TaskManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "GhostTaskManager2"

        @Volatile
        private var instance: TaskManager? = null

        fun getInstance(context: Context): TaskManager {
            return instance ?: synchronized(this) {
                instance ?: TaskManager(context.applicationContext).also { instance = it }
            }
        }

        val knownDistractionPackages = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.twitter.android",
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.netflix.mediaclient",
            "tv.twitch.android.app",
            "com.discord",
            "com.pinterest"
        )
    }

    private val tasksDir = File(context.filesDir, "tasks").apply { mkdirs() }
    private val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val registryFile = File(context.filesDir, "tasks_registry.json")
    private val presetsFile = File(context.filesDir, "presets.json")
    private val wisdomQuotesFile = File(context.filesDir, "wisdom_quotes.json")

    private val tasks = mutableListOf<GhostTask>()
    private val presets = mutableListOf<CustomPreset>()
    private val wisdomQuotes = mutableListOf<String>()

    init {
        loadPresets()
        loadWisdomQuotes()
        loadTasks()
        refreshTasksState()
        RootShell.setupCliToastWrapper(context)
    }

    private fun loadTasks() {
        tasks.clear()
        if (registryFile.exists()) {
            try {
                val content = registryFile.readText()
                val arr = JSONArray(content)
                for (i in 0 until arr.length()) {
                    tasks.add(GhostTask.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading tasks registry", e)
            }
        }
    }

    private fun saveTasks() {
        try {
            val arr = JSONArray()
            for (t in tasks) {
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("title", t.title)
                obj.put("description", t.description)
                obj.put("type", t.type)
                obj.put("pid", t.pid)
                obj.put("startTime", t.startTime)
                obj.put("delaySeconds", t.delaySeconds)
                obj.put("targetTime", t.targetTime)
                obj.put("status", t.status)
                obj.put("scriptPath", t.scriptPath)
                obj.put("logPath", t.logPath)
                obj.put("pidPath", t.pidPath)
                obj.put("statusPath", t.statusPath)
                obj.put("options", JSONObject(t.optionsJson))
                arr.put(obj)
            }
            registryFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving tasks registry", e)
        }
    }

    private fun loadPresets() {
        presets.clear()
        if (presetsFile.exists()) {
            try {
                val arr = JSONArray(presetsFile.readText())
                for (i in 0 until arr.length()) {
                    presets.add(CustomPreset.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading presets", e)
            }
        }
        if (presets.isEmpty()) {
            presets.add(CustomPreset("preset_1", "تنظيف الكاش والـ RAM", "تفريغ الـ PageCache والذاكرة الحرة فوراً", "sync && echo 3 > /proc/sys/vm/drop_caches", 0, System.currentTimeMillis()))
            presets.add(CustomPreset("preset_2", "تجميد تطبيقات الخلفية", "إيقاف وتجميد التطبيقات المعلقة في الذاكرة", "am kill-all", 0, System.currentTimeMillis()))
            presets.add(CustomPreset("preset_3", "إطلاق Toast حكمة أينشتاين", "إرسال Toast تحفيزي لشاشة الهاتف مباشرة", "am broadcast -a com.ayoub.ghostscript2.SHOW_WISDOM", 0, System.currentTimeMillis()))
            savePresets()
        }
    }

    private fun savePresets() {
        try {
            val arr = JSONArray()
            for (p in presets) {
                arr.put(p.toJson())
            }
            presetsFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving presets", e)
        }
    }

    private fun loadWisdomQuotes() {
        wisdomQuotes.clear()
        if (wisdomQuotesFile.exists()) {
            try {
                val arr = JSONArray(wisdomQuotesFile.readText())
                for (i in 0 until arr.length()) {
                    wisdomQuotes.add(arr.getString(i))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wisdom quotes", e)
            }
        }
        if (wisdomQuotes.isEmpty()) {
            wisdomQuotes.addAll(ToastReceiver.defaultWisdomQuotes)
            saveWisdomQuotes()
        }
    }

    private fun saveWisdomQuotes() {
        try {
            val arr = JSONArray()
            for (q in wisdomQuotes) {
                arr.put(q)
            }
            wisdomQuotesFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving wisdom quotes", e)
        }
    }

    @Synchronized
    fun refreshTasksState() {
        val now = System.currentTimeMillis()
        var changed = false

        for (task in tasks) {
            if (task.status == "RUNNING") {
                val statusFile = File(task.statusPath)
                val pidFile = File(task.pidPath)

                if (task.pid <= 0 && pidFile.exists()) {
                    try {
                        val p = pidFile.readText().trim().toIntOrNull()
                        if (p != null) {
                            task.pid = p
                            changed = true
                        }
                    } catch (_: Exception) {}
                }

                if (statusFile.exists()) {
                    val s = statusFile.readText().trim()
                    if (s == "COMPLETED" || s == "DONE") {
                        task.status = "COMPLETED"
                        changed = true
                        continue
                    } else if (s == "FAILED") {
                        task.status = "FAILED"
                        changed = true
                        continue
                    } else if (s == "CANCELLED") {
                        task.status = "CANCELLED"
                        changed = true
                        continue
                    }
                }

                if (task.pid > 0) {
                    val isAlive = RootShell.isProcessAlive(task.pid)
                    if (!isAlive) {
                        if (now >= task.targetTime) {
                            task.status = "COMPLETED"
                        } else {
                            task.status = "FAILED"
                        }
                        changed = true
                    }
                } else if (now > task.targetTime + 5000) {
                    task.status = "COMPLETED"
                    changed = true
                }
            }
        }

        if (changed) {
            saveTasks()
        }
    }

    // ==================== DISTRACTION BLOCKER / SUSPEND ====================

    fun getInstalledDistractionApps(): List<Map<String, Any>> {
        val pm = context.packageManager
        val discoveredPackages = mutableSetOf<String>()

        // 1. Try PackageManager
        try {
            val installed = pm.getInstalledApplications(0)
            for (app in installed) {
                discoveredPackages.add(app.packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PackageManager getInstalledApplications error", e)
        }

        // 2. Guaranteed Root fallback via shell: pm list packages
        try {
            val shellOut = RootShell.exec("pm list packages")
            for (line in shellOut.lines()) {
                val pkg = line.removePrefix("package:").trim()
                if (pkg.isNotBlank()) {
                    discoveredPackages.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root pm list packages error", e)
        }

        val result = mutableListOf<Map<String, Any>>()

        for (pkg in discoveredPackages) {
            val p = pkg.lowercase()

            // Exclude system frameworks & self
            if (pkg == "com.ayoub.ghostscript" || pkg == "com.ayoub.ghostscript2" ||
                p.startsWith("android") || p.startsWith("com.android.internal")) {
                continue
            }

            // Exclude study & AI apps (ChatGPT, Gemini, etc.)
            if (p.contains("chatgpt") || p.contains("openai") || p.contains("gemini") ||
                p.contains("bard") || p.contains("claude") || p.contains("obsidian") ||
                p.contains("notion")) {
                continue
            }

            // Match all distraction / social patterns (including TikTok normal & Lite zhiliaoapp)
            val isDistraction = p.contains("zhiliao") ||
                                p.contains("musically") ||
                                p.contains("tiktok") ||
                                p.contains("instagram") ||
                                p.contains("whatsapp") ||
                                p.contains("facebook") ||
                                p.contains("katana") ||
                                p.contains("orca") ||
                                p.contains("messenger") ||
                                p.contains("telegram") ||
                                p.contains("youtube") ||
                                p.contains("twitter") ||
                                p.contains("snapchat") ||
                                p.contains("reddit") ||
                                p.contains("netflix") ||
                                p.contains("discord") ||
                                p.contains("twitch") ||
                                p.contains("pinterest") ||
                                p.contains("threads") ||
                                p.contains("kwai") ||
                                knownDistractionPackages.contains(pkg)

            if (isDistraction) {
                val label = getAppFriendlyLabel(pm, pkg)
                val isSuspended = checkIsSuspended(pm, pkg)

                result.add(mapOf(
                    "packageName" to pkg,
                    "label" to label,
                    "isSuspended" to isSuspended,
                    "isKnownSocial" to true
                ))
            }
        }

        return result.sortedBy { it["label"] as String }
    }

    private fun getAppFriendlyLabel(pm: PackageManager, pkg: String): String {
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            val l = pm.getApplicationLabel(info).toString()
            if (l.isNotBlank() && l != pkg) return l
        } catch (_: Exception) {}

        val p = pkg.lowercase()
        return when {
            p == "com.zhiliaoapp.musically.go" -> "TikTok Lite"
            p == "com.zhiliaoapp.musically" || p.contains("tiktok") -> "TikTok"
            p == "com.whatsapp.w4b" -> "WhatsApp Business"
            p == "com.whatsapp" -> "WhatsApp"
            p == "com.facebook.katana" -> "Facebook"
            p == "com.facebook.orca" -> "Messenger"
            p == "com.instagram.android" -> "Instagram"
            p == "org.telegram.messenger.web" -> "Telegram Web"
            p.contains("telegram") -> "Telegram"
            p == "com.google.android.youtube" -> "YouTube"
            p == "com.google.android.apps.youtube.music" -> "YouTube Music"
            p.contains("twitter") -> "X (Twitter)"
            p.contains("snapchat") -> "Snapchat"
            p.contains("reddit") -> "Reddit"
            p.contains("netflix") -> "Netflix"
            p.contains("pinterest") -> "Pinterest"
            p.contains("discord") -> "Discord"
            else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun checkIsSuspended(pm: PackageManager, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return pm.isPackageSuspended(pkg)
            } catch (_: Exception) {}
        }
        val out = RootShell.exec("dumpsys package $pkg | grep -i 'suspended=true'")
        return out.isNotBlank()
    }

    fun startDistractionSuspendSession(
        packages: List<String>,
        durationMinutes: Int,
        sendToast: Boolean
    ): GhostTask {
        val taskId = "distract_" + System.currentTimeMillis()
        val delaySec = durationMinutes.toLong() * 60L
        val startTime = System.currentTimeMillis()
        val targetTime = if (delaySec > 0) startTime + (delaySec * 1000L) else startTime

        val scriptFile = File(tasksDir, "$taskId.sh")
        val logFile = File(logsDir, "$taskId.log")
        val pidFile = File(tasksDir, "$taskId.pid")
        val statusFile = File(tasksDir, "$taskId.status")

        val pkgsStr = packages.joinToString(" ")
        val pkgCount = packages.size

        // 1. Immediately suspend packages via root
        for (pkg in packages) {
            RootShell.exec("cmd package suspend \"$pkg\" || pm suspend \"$pkg\" || true")
        }

        // 2. Fire starting motivational Toast
        if (sendToast) {
            val quote = wisdomQuotes.randomOrNull() ?: "حان وقت التركيز العظيم! 🚀"
            val startMsg = "تم تجميد $pkgCount من المشتتات بنجاح 🛡️\n$quote"
            val broadcastCmd = "am broadcast -a com.ayoub.ghostscript2.SHOW_TOAST --es text \"$startMsg\""
            RootShell.exec(broadcastCmd)
        }

        // 3. Build restore payload commands when timer finishes
        val unsuspendCommands = buildString {
            for (pkg in packages) {
                appendLine("cmd package unsuspend \"$pkg\" || pm unsuspend \"$pkg\" || true")
            }
            if (sendToast) {
                appendLine("am broadcast -a com.ayoub.ghostscript2.SHOW_TOAST --es text \"🎉 اكتملت جلسة التركيز بنجاح! تم إلغاء تجميد التطبيقات.\" || true")
            }
        }

        val scriptContent = buildScript(
            taskId = taskId,
            delaySeconds = delaySec,
            payloadCommands = unsuspendCommands,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            logPath = logFile.absolutePath
        )

        scriptFile.writeText(scriptContent)
        RootShell.exec("chmod 755 \"${scriptFile.absolutePath}\"")

        if (delaySec > 10) {
            scheduleAlarm(taskId, targetTime)
        }

        val launchCmd = "nohup sh \"${scriptFile.absolutePath}\" >> \"${logFile.absolutePath}\" 2>&1 & echo \$!"
        val launchedPidStr = RootShell.exec(launchCmd).lines().lastOrNull { it.isNotBlank() } ?: "-1"
        val launchedPid = launchedPidStr.trim().toIntOrNull() ?: -1

        val title = "جلسة تركيز وتجميد المشتتات (${if (durationMinutes > 0) "$durationMinutes د" else "دائم"})"
        val desc = "تم تعليق $pkgCount تطبيق مشتت (YouTube, Instagram, etc.) لدعم المذاكرة"

        val task = GhostTask(
            id = taskId,
            title = title,
            description = desc,
            type = "distraction_blocker",
            pid = launchedPid,
            startTime = startTime,
            delaySeconds = delaySec,
            targetTime = targetTime,
            status = "RUNNING",
            scriptPath = scriptFile.absolutePath,
            logPath = logFile.absolutePath,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            optionsJson = JSONObject().apply {
                put("packages", JSONArray(packages))
                put("durationMinutes", durationMinutes)
            }.toString()
        )

        synchronized(this) {
            tasks.add(0, task)
            saveTasks()
        }

        return task
    }

    fun unsuspendAllDistractions(): Boolean {
        // Find all active distraction blocker tasks and cancel them
        synchronized(this) {
            for (t in tasks) {
                if (t.type == "distraction_blocker" && t.status == "RUNNING") {
                    stopTask(t.id)
                }
            }
        }

        // Unsuspend all known distraction packages
        for (pkg in knownDistractionPackages) {
            RootShell.exec("cmd package unsuspend \"$pkg\" || pm unsuspend \"$pkg\" || true")
        }

        // Send confirmation toast
        RootShell.exec("am broadcast -a com.ayoub.ghostscript2.SHOW_TOAST --es text \"✅ تم إلغاء تجميد جميع التطبيقات فوراً.\"")
        return true
    }

    // ==================== TOAST & WISDOM MESSENGER ====================

    fun sendToast(message: String) {
        try {
            ToastOverlayService.show(context, message)
        } catch (_: Exception) {}
        val safe = message.replace("\"", "\\\"")
        RootShell.exec("am start -n com.ayoub.ghostscript2/.ToastActivity --es text \"$safe\" --activity-no-animation || true")
    }

    fun sendRandomWisdomToast() {
        val quote = wisdomQuotes.randomOrNull() ?: "الخيال أهم من المعرفة."
        sendToast(quote)
    }

    fun getWisdomQuotes(): List<String> = wisdomQuotes.toList()

    @Synchronized
    fun addWisdomQuote(quote: String) {
        if (quote.isNotBlank() && !wisdomQuotes.contains(quote)) {
            wisdomQuotes.add(0, quote)
            saveWisdomQuotes()
        }
    }

    @Synchronized
    fun deleteWisdomQuote(quote: String) {
        wisdomQuotes.remove(quote)
        saveWisdomQuotes()
    }

    fun getToastLogs(): String {
        val logFile = File(logsDir, "toasts.log")
        return if (logFile.exists()) logFile.readText().trim() else "لا توجد رسائل سابقة."
    }

    // ==================== SLEEP TIMER & CORE TASKS ====================

    fun startSleepTimer(
        minutes: Int,
        pauseMedia: Boolean,
        muteAudio: Boolean,
        turnOffWifi: Boolean,
        turnOffBluetooth: Boolean
    ): GhostTask {
        val taskId = "sleep_" + System.currentTimeMillis()
        val delaySec = minutes.toLong() * 60L
        val startTime = System.currentTimeMillis()
        val targetTime = startTime + (delaySec * 1000L)

        val scriptFile = File(tasksDir, "$taskId.sh")
        val logFile = File(logsDir, "$taskId.log")
        val pidFile = File(tasksDir, "$taskId.pid")
        val statusFile = File(tasksDir, "$taskId.status")

        val commands = buildString {
            if (pauseMedia) {
                appendLine("cmd media_session dispatch pause || input keyevent 127 || true")
            }
            if (muteAudio) {
                appendLine("cmd media_session volume --stream 3 --set 0 || true")
            }
            if (turnOffWifi) {
                appendLine("svc wifi disable || true")
            }
            if (turnOffBluetooth) {
                appendLine("svc bluetooth disable || true")
            }
            appendLine("cmd power sleep || input keyevent 223 || input keyevent 26")
        }

        val title = "مؤقت النوم وإطفاء الشاشة ($minutes دقيقة)"
        val desc = buildString {
            append("إطفاء الشاشة")
            if (pauseMedia) append(" + إيقاف الوسائط")
            if (muteAudio) append(" + كتم الصوت")
            if (turnOffWifi) append(" + إيقاف Wi-Fi")
            if (turnOffBluetooth) append(" + إيقاف البلوتوث")
        }

        val scriptContent = buildScript(
            taskId = taskId,
            delaySeconds = delaySec,
            payloadCommands = commands,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            logPath = logFile.absolutePath
        )

        scriptFile.writeText(scriptContent)
        RootShell.exec("chmod 755 \"${scriptFile.absolutePath}\"")

        scheduleAlarm(taskId, targetTime)

        val launchCmd = "nohup sh \"${scriptFile.absolutePath}\" >> \"${logFile.absolutePath}\" 2>&1 & echo \$!"
        val launchedPidStr = RootShell.exec(launchCmd).lines().lastOrNull { it.isNotBlank() } ?: "-1"
        val launchedPid = launchedPidStr.trim().toIntOrNull() ?: -1

        val task = GhostTask(
            id = taskId,
            title = title,
            description = desc,
            type = "sleep_timer",
            pid = launchedPid,
            startTime = startTime,
            delaySeconds = delaySec,
            targetTime = targetTime,
            status = "RUNNING",
            scriptPath = scriptFile.absolutePath,
            logPath = logFile.absolutePath,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            optionsJson = JSONObject().apply {
                put("minutes", minutes)
                put("pauseMedia", pauseMedia)
                put("muteAudio", muteAudio)
                put("turnOffWifi", turnOffWifi)
                put("turnOffBluetooth", turnOffBluetooth)
            }.toString()
        )

        synchronized(this) {
            tasks.add(0, task)
            saveTasks()
        }

        return task
    }

    fun startBuiltinTask(type: String, delaySeconds: Long): GhostTask {
        val taskId = "${type}_" + System.currentTimeMillis()
        val startTime = System.currentTimeMillis()
        val targetTime = startTime + (delaySeconds * 1000L)

        val scriptFile = File(tasksDir, "$taskId.sh")
        val logFile = File(logsDir, "$taskId.log")
        val pidFile = File(tasksDir, "$taskId.pid")
        val statusFile = File(tasksDir, "$taskId.status")

        val (title, desc, commands) = when (type) {
            "ram_purge" -> Triple("تنظيف الذاكرة والكاش", "تفريغ PageCache الذاكرة المؤقتة عبر drop_caches", "sync && echo 3 > /proc/sys/vm/drop_caches")
            "kill_background" -> Triple("تجميد تطبيقات الخلفية", "إنهاء العمليات المعلقة في الذاكرة لتسريع الهاتف", "am kill-all")
            "power_off" -> Triple("إيقاف تشغيل الهاتف المجدول", "إغلاق الهاتف تماماً لتوفير الطاقة", "reboot -p")
            "reboot" -> Triple("إعادة تشغيل الهاتف المجدولة", "إعادة تشغيل الجهاز لتجديد موارد النظام", "reboot")
            "power_saver" -> Triple("وضع التوفير الفائق للطاقة", "تفعيل وضع توفير الطاقة وتعطيل الشبكات", "cmd power set-mode 1\nsvc wifi disable\nsvc bluetooth disable")
            else -> Triple("مهمة نظام روت", "تنفيذ سكريبت تلقائي", "true")
        }

        val scriptContent = buildScript(
            taskId = taskId,
            delaySeconds = delaySeconds,
            payloadCommands = commands,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            logPath = logFile.absolutePath
        )

        scriptFile.writeText(scriptContent)
        RootShell.exec("chmod 755 \"${scriptFile.absolutePath}\"")

        if (delaySeconds > 10) {
            scheduleAlarm(taskId, targetTime)
        }

        val launchCmd = "nohup sh \"${scriptFile.absolutePath}\" >> \"${logFile.absolutePath}\" 2>&1 & echo \$!"
        val launchedPidStr = RootShell.exec(launchCmd).lines().lastOrNull { it.isNotBlank() } ?: "-1"
        val launchedPid = launchedPidStr.trim().toIntOrNull() ?: -1

        val task = GhostTask(
            id = taskId,
            title = title,
            description = desc,
            type = type,
            pid = launchedPid,
            startTime = startTime,
            delaySeconds = delaySeconds,
            targetTime = targetTime,
            status = "RUNNING",
            scriptPath = scriptFile.absolutePath,
            logPath = logFile.absolutePath,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath
        )

        synchronized(this) {
            tasks.add(0, task)
            saveTasks()
        }

        return task
    }

    fun startCustomTask(name: String, commands: String, delaySeconds: Long): GhostTask {
        val taskId = "custom_" + System.currentTimeMillis()
        val startTime = System.currentTimeMillis()
        val targetTime = startTime + (delaySeconds * 1000L)

        val scriptFile = File(tasksDir, "$taskId.sh")
        val logFile = File(logsDir, "$taskId.log")
        val pidFile = File(tasksDir, "$taskId.pid")
        val statusFile = File(tasksDir, "$taskId.status")

        val scriptContent = buildScript(
            taskId = taskId,
            delaySeconds = delaySeconds,
            payloadCommands = commands,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath,
            logPath = logFile.absolutePath
        )

        scriptFile.writeText(scriptContent)
        RootShell.exec("chmod 755 \"${scriptFile.absolutePath}\"")

        if (delaySeconds > 10) {
            scheduleAlarm(taskId, targetTime)
        }

        val launchCmd = "nohup sh \"${scriptFile.absolutePath}\" >> \"${logFile.absolutePath}\" 2>&1 & echo \$!"
        val launchedPidStr = RootShell.exec(launchCmd).lines().lastOrNull { it.isNotBlank() } ?: "-1"
        val launchedPid = launchedPidStr.trim().toIntOrNull() ?: -1

        val desc = if (delaySeconds > 0) "تشغيل بعد ${delaySeconds / 60} دقيقة" else "تشغيل فوري"

        val task = GhostTask(
            id = taskId,
            title = if (name.isNotBlank()) name else "سكريبت مخصص",
            description = desc,
            type = "custom",
            pid = launchedPid,
            startTime = startTime,
            delaySeconds = delaySeconds,
            targetTime = targetTime,
            status = "RUNNING",
            scriptPath = scriptFile.absolutePath,
            logPath = logFile.absolutePath,
            pidPath = pidFile.absolutePath,
            statusPath = statusFile.absolutePath
        )

        synchronized(this) {
            tasks.add(0, task)
            saveTasks()
        }

        return task
    }

    @Synchronized
    fun stopTask(taskId: String): Boolean {
        val task = tasks.find { it.id == taskId } ?: return false

        cancelAlarm(taskId)

        if (task.pid > 0) {
            RootShell.killProcess(task.pid)
        }
        RootShell.exec("pkill -f \"${task.id}.sh\" || true")

        // If it was a distraction blocker, unsuspend packages!
        if (task.type == "distraction_blocker") {
            try {
                val opts = JSONObject(task.optionsJson)
                val pkgsArr = opts.optJSONArray("packages")
                if (pkgsArr != null) {
                    for (i in 0 until pkgsArr.length()) {
                        val p = pkgsArr.getString(i)
                        RootShell.exec("cmd package unsuspend \"$p\" || pm unsuspend \"$p\" || true")
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            File(task.statusPath).writeText("CANCELLED")
            File(task.logPath).appendText("\n[Stopped by user]")
        } catch (_: Exception) {}

        task.status = "CANCELLED"
        saveTasks()
        return true
    }

    @Synchronized
    fun deleteTask(taskId: String): Boolean {
        stopTask(taskId)
        val task = tasks.find { it.id == taskId } ?: return false
        try {
            File(task.scriptPath).delete()
            File(task.logPath).delete()
            File(task.pidPath).delete()
            File(task.statusPath).delete()
        } catch (_: Exception) {}
        tasks.remove(task)
        saveTasks()
        return true
    }

    @Synchronized
    fun clearHistory() {
        val toRemove = tasks.filter { it.status != "RUNNING" }
        for (task in toRemove) {
            try {
                File(task.scriptPath).delete()
                File(task.logPath).delete()
                File(task.pidPath).delete()
                File(task.statusPath).delete()
            } catch (_: Exception) {}
        }
        tasks.removeAll(toRemove)
        saveTasks()
    }

    fun getTaskLog(taskId: String): String {
        val task = tasks.find { it.id == taskId } ?: return "المهمة غير موجودة"
        val logFile = File(task.logPath)
        return if (logFile.exists()) {
            val content = logFile.readText().trim()
            if (content.isNotBlank()) content else "السجل فارغ حتى الآن..."
        } else {
            "لم يتم تسجيل أي مخرجات بعد."
        }
    }

    @Synchronized
    fun getTasksJson(): String {
        refreshTasksState()
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(t.toJson())
        }
        return arr.toString()
    }

    fun getPresetsJson(): String {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(p.toJson())
        }
        return arr.toString()
    }

    @Synchronized
    fun savePreset(name: String, desc: String, commands: String, delaySeconds: Long): CustomPreset {
        val id = "preset_" + System.currentTimeMillis()
        val preset = CustomPreset(id, name, desc, commands, delaySeconds, System.currentTimeMillis())
        presets.add(preset)
        savePresets()
        return preset
    }

    @Synchronized
    fun deletePreset(presetId: String): Boolean {
        val p = presets.find { it.id == presetId } ?: return false
        presets.remove(p)
        savePresets()
        return true
    }

    fun onAlarmTriggered(taskId: String) {
        val task = tasks.find { it.id == taskId } ?: return
        if (task.status == "RUNNING") {
            Log.i(TAG, "Alarm triggered execution for task: ${task.id}")
            refreshTasksState()
        }
    }

    private fun scheduleAlarm(taskId: String, triggerAtMillis: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, TaskReceiver::class.java).apply {
                action = TaskReceiver.ACTION_ALARM_TRIGGER
                putExtra(TaskReceiver.EXTRA_TASK_ID, taskId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                taskId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm", e)
        }
    }

    private fun cancelAlarm(taskId: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, TaskReceiver::class.java).apply {
                action = TaskReceiver.ACTION_ALARM_TRIGGER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                taskId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
        } catch (_: Exception) {}
    }

    private fun buildScript(
        taskId: String,
        delaySeconds: Long,
        payloadCommands: String,
        pidPath: String,
        statusPath: String,
        logPath: String
    ): String {
        val d = "$"
        return """
#!/system/bin/sh
# GhostScript V2 Autonomous Runner
# Task ID: $taskId

echo "${d}${d}" > "$pidPath"
echo "RUNNING" > "$statusPath"
echo "[${d}(date '+%Y-%m-%d %H:%M:%S')] 👻 Ghost task launched (PID: ${d}${d})" >> "$logPath"

if [ $delaySeconds -gt 0 ]; then
    echo "[${d}(date '+%Y-%m-%d %H:%M:%S')] ⏳ Dormant sleep for $delaySeconds seconds (0% CPU)..." >> "$logPath"
    sleep $delaySeconds
fi

echo "[${d}(date '+%Y-%m-%d %H:%M:%S')] ⚡ Executing payload commands..." >> "$logPath"

$payloadCommands

EXIT_CODE=${d}?
echo "[${d}(date '+%Y-%m-%d %H:%M:%S')] 🏁 Finished with exit code: ${d}EXIT_CODE" >> "$logPath"

if [ ${d}EXIT_CODE -eq 0 ]; then
    echo "COMPLETED" > "$statusPath"
else
    echo "FAILED" > "$statusPath"
fi

rm -f "$pidPath"
exit ${d}EXIT_CODE
""".trimIndent()
    }
}
