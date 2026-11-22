package com.sxvuj.streaksaver.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.sxvuj.streaksaver.rust.RustCore
import org.json.JSONObject

/**
 * TikTok Login WebView enforcing the Rust custom-scheme guard (`RustCore.guardLoginUrl`)
 * so `snssdk://` and `intent://` redirects reload `params_url` instead of closing the app.
 */
class LoginWebViewActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = RustCore.resolveUserAgent("")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    val resp = JSONObject(RustCore.guardLoginUrl(target))
                    val data = resp.optJSONObject("data") ?: return false
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
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
        webView = wv
        setContentView(wv)
        wv.loadUrl("https://www.tiktok.com/login")
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
