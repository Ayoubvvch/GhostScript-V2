package com.ayoub.ghostscript2

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "GhostMainActivity2"
    }

    private lateinit var webView: WebView
    private lateinit var taskManager: TaskManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.parseColor("#0b0f19")
            navigationBarColor = Color.parseColor("#0b0f19")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setDecorFitsSystemWindows(false)
            }
        }

        taskManager = TaskManager.getInstance(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0b0f19"))
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    syncStateToJs()
                }
            }

            addJavascriptInterface(GhostBridge(), "GhostNative")
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
                    if (result != "true") {
                        moveTaskToBack(true)
                    }
                }
            }
        })

        webView.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onResume() {
        super.onResume()
        syncStateToJs()
    }

    private fun syncStateToJs() {
        CoroutineScope(Dispatchers.IO).launch {
            val rootOk = RootShell.isRootAvailable()
            val tasksJson = taskManager.getTasksJson()
            val presetsJson = taskManager.getPresetsJson()
            val apps = taskManager.getInstalledDistractionApps()
            val quotes = taskManager.getWisdomQuotes()

            val appsArr = JSONArray()
            for (app in apps) {
                val o = JSONObject()
                o.put("packageName", app["packageName"])
                o.put("label", app["label"])
                o.put("isSuspended", app["isSuspended"])
                o.put("isKnownSocial", app["isKnownSocial"])
                appsArr.put(o)
            }

            val quotesArr = JSONArray()
            for (q in quotes) {
                quotesArr.put(q)
            }

            val stateJson = JSONObject().apply {
                put("isRoot", rootOk)
                put("tasks", JSONArray(tasksJson))
                put("presets", JSONArray(presetsJson))
                put("distractionApps", appsArr)
                put("wisdomQuotes", quotesArr)
            }.toString()

            withContext(Dispatchers.Main) {
                val escaped = stateJson.replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript("if(window.onGhostStateReceived) window.onGhostStateReceived('$escaped');", null)
            }
        }
    }

    inner class GhostBridge {

        @JavascriptInterface
        fun getInitialState(): String {
            return try {
                val rootOk = RootShell.isRootAvailable()
                val tasksJson = taskManager.getTasksJson()
                val presetsJson = taskManager.getPresetsJson()
                val apps = taskManager.getInstalledDistractionApps()
                val quotes = taskManager.getWisdomQuotes()

                val appsArr = JSONArray()
                for (app in apps) {
                    val o = JSONObject()
                    o.put("packageName", app["packageName"])
                    o.put("label", app["label"])
                    o.put("isSuspended", app["isSuspended"])
                    o.put("isKnownSocial", app["isKnownSocial"])
                    appsArr.put(o)
                }

                val quotesArr = JSONArray()
                for (q in quotes) {
                    quotesArr.put(q)
                }

                JSONObject().apply {
                    put("isRoot", rootOk)
                    put("tasks", JSONArray(tasksJson))
                    put("presets", JSONArray(presetsJson))
                    put("distractionApps", appsArr)
                    put("wisdomQuotes", quotesArr)
                }.toString()
            } catch (e: Exception) {
                "{}"
            }
        }

        @JavascriptInterface
        fun requestRefresh() {
            syncStateToJs()
        }

        // ================= DISTRACTION BLOCKER =================
        @JavascriptInterface
        fun suspendDistractions(packagesJson: String, durationMinutes: Int, sendToast: Boolean) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val arr = JSONArray(packagesJson)
                    val pkgs = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        pkgs.add(arr.getString(i))
                    }
                    taskManager.startDistractionSuspendSession(pkgs, durationMinutes, sendToast)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error suspending distractions", e)
                }
            }
        }

        @JavascriptInterface
        fun unsuspendAllDistractions() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.unsuspendAllDistractions()
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsuspending distractions", e)
                }
            }
        }

        // ================= TOAST & WISDOM =================
        @JavascriptInterface
        fun sendCustomToast(message: String) {
            CoroutineScope(Dispatchers.IO).launch {
                taskManager.sendToast(message)
            }
        }

        @JavascriptInterface
        fun sendRandomWisdomToast() {
            CoroutineScope(Dispatchers.IO).launch {
                taskManager.sendRandomWisdomToast()
            }
        }

        @JavascriptInterface
        fun addWisdomQuote(quote: String) {
            CoroutineScope(Dispatchers.IO).launch {
                taskManager.addWisdomQuote(quote)
                syncStateToJs()
            }
        }

        @JavascriptInterface
        fun deleteWisdomQuote(quote: String) {
            CoroutineScope(Dispatchers.IO).launch {
                taskManager.deleteWisdomQuote(quote)
                syncStateToJs()
            }
        }

        // ================= SLEEP & PRESETS =================
        @JavascriptInterface
        fun startSleepTimer(minutes: Int, optionsJson: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val opts = JSONObject(if (optionsJson.isBlank()) "{}" else optionsJson)
                    val pauseMedia = opts.optBoolean("pauseMedia", true)
                    val muteAudio = opts.optBoolean("muteAudio", false)
                    val turnOffWifi = opts.optBoolean("turnOffWifi", false)
                    val turnOffBluetooth = opts.optBoolean("turnOffBluetooth", false)

                    taskManager.startSleepTimer(
                        minutes = minutes,
                        pauseMedia = pauseMedia,
                        muteAudio = muteAudio,
                        turnOffWifi = turnOffWifi,
                        turnOffBluetooth = turnOffBluetooth
                    )
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting sleep timer", e)
                }
            }
        }

        @JavascriptInterface
        fun startBuiltin(type: String, delaySeconds: Long) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.startBuiltinTask(type, delaySeconds)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting builtin task", e)
                }
            }
        }

        @JavascriptInterface
        fun startCustom(name: String, commands: String, delaySeconds: Long) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.startCustomTask(name, commands, delaySeconds)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting custom task", e)
                }
            }
        }

        @JavascriptInterface
        fun stopTask(taskId: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.stopTask(taskId)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping task", e)
                }
            }
        }

        @JavascriptInterface
        fun deleteTask(taskId: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.deleteTask(taskId)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting task", e)
                }
            }
        }

        @JavascriptInterface
        fun clearHistory() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.clearHistory()
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing history", e)
                }
            }
        }

        @JavascriptInterface
        fun getTaskLog(taskId: String): String {
            return try {
                taskManager.getTaskLog(taskId)
            } catch (e: Exception) {
                "تعذر قراءة السجل"
            }
        }

        @JavascriptInterface
        fun savePreset(name: String, desc: String, commands: String, delaySeconds: Long) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.savePreset(name, desc, commands, delaySeconds)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving preset", e)
                }
            }
        }

        @JavascriptInterface
        fun deletePreset(presetId: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    taskManager.deletePreset(presetId)
                    syncStateToJs()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting preset", e)
                }
            }
        }
    }
}
