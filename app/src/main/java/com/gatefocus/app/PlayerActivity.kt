package com.gatefocus.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val title = intent.getStringExtra("title") ?: "GATE Lecture"
        val videoId = intent.getStringExtra("video_id") ?: run {
            finish()
            return
        }

        val btnBack = findViewById<ImageView>(R.id.btn_player_back)
        val textTitle = findViewById<TextView>(R.id.text_player_title)
        val progressBar = findViewById<ProgressBar>(R.id.player_progress)
        val webView = findViewById<WebView>(R.id.player_webview)

        textTitle.text = title
        btnBack.setOnClickListener { finish() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Privacy-focused embedded YouTube player (no algorithmic feed)
        val htmlContent = """
            <!doctype html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; background: #000; }
                    html, body { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
                    iframe { width: 100%; height: 100%; border: none; }
                </style>
            </head>
            <body>
                <iframe
                    src="https://www.youtube-nocookie.com/embed/$videoId?rel=0&modestbranding=1&playsinline=1"
                    title="GATE lecture"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                    allowfullscreen>
                </iframe>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", htmlContent, "text/html", "UTF-8", null)
    }
}
