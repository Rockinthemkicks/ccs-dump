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
            text = "1. Tap the button below, open \"CCS Node Dump\" and turn on \"Use CCS Node Dump\".\n" +
                "2. Open MobileCCS. Each screen is saved about 3 seconds after it loads; " +
                "your phone buzzes when it saves.\n" +
                "3. Dumps appear in Downloads/ccs-dumps/. Other apps are ignored.\n"
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
