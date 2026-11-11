package com.she.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * She — one-tap launcher for the She web app running in Termux.
 *
 * On tap:
 *  1. Checks Termux (com.termux) is installed; if not, shows a message
 *     with a Play Store link.
 *  2. Generates a random shell token, passes it to Termux by running
 *     `SHE_TOKEN=<token> bash $HOME/.she/start.sh` via the Termux
 *     RUN_COMMAND service. start.sh starts llama-server (GGUF, :8090)
 *     and node kali-bridge.js (:8080) if they are not already running.
 *  3. Polls http://127.0.0.1:8080/state for up to ~60s, showing
 *     "Starting She…" with the Furret icon.
 *  4. Shows the bundled web face (assets/she/index.html) in a WebView.
 *     NOTE: the asset folder must NOT be named ".she" — Android's asset
 *     packaging (AAPT/Gradle) excludes dot-directories, so a ".she"
 *     folder is never packaged into the APK and the WebView fails with
 *     ERR_FILE_NOT_FOUND even though the bridge is up.
 *     The page talks to the bridge on localhost:8080; the shell token
 *     is available to it via the SheBridge JavascriptInterface, so
 *     "Run in Termux" can POST /shell with X-She-Token.
 *
 * The WebView is the face of the app; no model or shell runs inside
 * the APK itself — everything runs in Termux, on-device.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splash: View
    private lateinit var splashText: TextView
    private lateinit var termuxMissing: View
    private var shellToken: String = ""

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val BRIDGE_STATE_URL = "http://127.0.0.1:8080/state"
        private const val POLL_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 1_000L
    }

    /** Exposed to index.html as window.SheBridge — token only, nothing else. */
    inner class SheBridge {
        @JavascriptInterface
        fun getToken(): String = shellToken
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        splash = findViewById(R.id.splash)
        splashText = findViewById(R.id.splash_text)
        termuxMissing = findViewById(R.id.termux_missing)
        findViewById<ImageView>(R.id.splash_icon).setImageResource(R.drawable.furret)

        if (!isTermuxInstalled()) {
            splash.visibility = View.GONE
            termuxMissing.visibility = View.VISIBLE
            findViewById<Button>(R.id.install_termux_btn).setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TERMUX_PACKAGE")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TERMUX_PACKAGE")))
                }
            }
            return
        }

        setupWebView()
        shellToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        startSheInTermux()
        waitForBridgeThenShow()
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The bundled page is file:// and talks to http://localhost:8080.
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(SheBridge(), "SheBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                // A file:// failure is a missing bundled page in the APK,
                // NOT a Termux/bridge failure — do not blame Termux for it.
                if (failingUrl.startsWith("file://")) {
                    splashText.text = "Bundled page missing in the APK ($description).\nThis app build is broken — reinstall a newer She APK.\nTermux is fine; do not re-run start.sh for this."
                } else {
                    splashText.text = "Could not reach She in Termux ($description). Open Termux and run: bash ~/.she/start.sh"
                }
                splash.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun startSheInTermux() {
        // Termux RUN_COMMAND: run bash -c "SHE_TOKEN=… bash $HOME/.she/start.sh"
        // via the Termux RunCommandService. This is the documented
        // com.termux.RUN_COMMAND mechanism; it requires the
        // com.termux.permission.RUN_COMMAND permission (declared in the
        // manifest) and the user to have run Termux at least once.
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "SHE_TOKEN=$shellToken bash \$HOME/.she/start.sh"))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Termux: ${e.message}. Open Termux and run bash ~/.she/start.sh", Toast.LENGTH_LONG).show()
        }
    }

    private fun waitForBridgeThenShow() {
        splashText.text = "Starting She…"
        lifecycleScope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            var up = false
            while (System.currentTimeMillis() < deadline) {
                if (isBridgeUp()) { up = true; break }
                delay(POLL_INTERVAL_MS)
            }
            withContext(Dispatchers.Main) {
                if (up) {
                    splash.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.loadUrl("file:///android_asset/she/index.html")
                } else {
                    splashText.text = "She did not start in 60s.\nOpen Termux and run:\nbash ~/.she/start.sh\nthen reopen this app.\n(Check ~/.she/she.log for errors.)"
                }
            }
        }
    }

    private fun isBridgeUp(): Boolean {
        return try {
            val conn = URL(BRIDGE_STATE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
