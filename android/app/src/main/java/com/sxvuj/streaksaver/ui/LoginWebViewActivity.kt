package com.sxvuj.streaksaver.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sxvuj.streaksaver.data.Reply
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.rust.RustCore

/**
 * TikTok login. Enforces the Rust custom-scheme guard (`RustCore.guardLoginUrl`) so `snssdk://`
 * and `intent://` redirects reload the web login instead of closing the page, and records the
 * session in the app as soon as TikTok hands out a session cookie.
 */
class LoginWebViewActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = RustCore.resolveUserAgent("")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                val data = Reply.parse(RustCore.guardLoginUrl(target)).obj ?: return false
                return when (data.optString("action")) {
                    "allow" -> false
                    "block_and_reload" -> {
                        val reloadUrl = data.optString("target_url")
                        if (reloadUrl.isNotEmpty()) view?.loadUrl(reloadUrl)
                        true
                    }
                    else -> true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty()
                if (RustCore.hasValidSessionCookie(cookies)) {
                    CookieManager.getInstance().flush()
                    StreakRepo(this@LoginWebViewActivity).updateSession(valid = true)
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        webView = view
        setContentView(view)

        // Keep the page out from under the status and navigation bars (edge-to-edge is forced on Android 15).
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        view.loadUrl("https://www.tiktok.com/login")
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
