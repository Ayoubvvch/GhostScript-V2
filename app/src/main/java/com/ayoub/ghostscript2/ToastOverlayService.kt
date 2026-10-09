package com.ayoub.ghostscript2

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.TextView

class ToastOverlayService : Service() {
    companion object {
        private const val TAG = "ToastOverlayService"
        const val EXTRA_TEXT = "text"

        fun show(context: Context, text: String) {
            try {
                val intent = Intent(context, ToastOverlayService::class.java).apply {
                    putExtra(EXTRA_TEXT, text)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting overlay service", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "GhostScript Toast"
        displayFloatingOverlay(text)
        return START_NOT_STICKY
    }

    private fun displayFloatingOverlay(text: String) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200 // Positioned gracefully above navigation bar
        }

        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        val cornerRadius = 24 * density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0B0F19")) // AMOLED deep dark
                setStroke((1.5 * density).toInt(), Color.parseColor("#A0C084FC")) // glowing purple
                this.cornerRadius = cornerRadius
            }
            elevation = 18 * density
        }

        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = (330 * density).toInt()
            gravity = Gravity.CENTER
            setLineSpacing(4f, 1.1f)
        }

        container.addView(textView)

        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 220 }
        container.startAnimation(fadeIn)

        try {
            windowManager.addView(container, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add window overlay, falling back to Trampoline Activity", e)
            val fallback = Intent(this, ToastActivity::class.java).apply {
                putExtra("text", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(fallback)
            stopSelf()
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var isDismissed = false

        val dismissRunnable = Runnable {
            if (!isDismissed) {
                isDismissed = true
                val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 250 }
                container.startAnimation(fadeOut)
                handler.postDelayed({
                    try {
                        windowManager.removeView(container)
                    } catch (_: Exception) {}
                    stopSelf()
                }, 240)
            }
        }

        container.setOnClickListener {
            handler.removeCallbacks(dismissRunnable)
            dismissRunnable.run()
        }

        handler.postDelayed(dismissRunnable, 3800)
    }
}
