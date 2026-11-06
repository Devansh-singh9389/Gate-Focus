package com.gatefocus.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class BlockScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block_screen)

        val rawApp = intent.getStringExtra("blocked_app") ?: "Distracting content"
        val friendlyAppName = when (rawApp) {
            "com.instagram.android" -> "Instagram"
            "com.threads.android", "com.instagram.barcelona" -> "Threads"
            "com.facebook.katana" -> "Facebook"
            "com.snapchat.android" -> "Snapchat"
            "com.google.android.youtube" -> "YouTube Shorts"
            else -> rawApp
        }

        val messageView = findViewById<TextView>(R.id.text_blocked_app_message)
        messageView.text = "Study mode is active. $friendlyAppName is blocked for this session.\nTake a breath and return to your CSE preparation."

        val btnBack = findViewById<MaterialButton>(R.id.btn_back_to_focus)
        btnBack.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            finish()
        }
    }
}
