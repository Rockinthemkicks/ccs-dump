package com.example.ccsdump

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "1. Tap the button below, open \"CCS Node Dump\" and turn it on.\n" +
                "2. On the same page, turn on \"CCS Node Dump shortcut\" " +
                "(a small floating or nav-bar button appears).\n" +
                "3. Open MobileCCS to the screen you want and tap that shortcut button.\n" +
                "4. Dumps appear in Downloads/ccs-dumps/."
            textSize = 16f
        })
        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        setContentView(layout)
    }
}
