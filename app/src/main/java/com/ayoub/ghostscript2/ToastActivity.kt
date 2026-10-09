package com.ayoub.ghostscript2

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast

class ToastActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        val text = intent.getStringExtra("text")
            ?: intent.getStringExtra("msg")
            ?: intent.getStringExtra("message")
            ?: ""

        if (text.isNotBlank()) {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
