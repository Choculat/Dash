package sh.margot.dash.services.smspool

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import sh.margot.dash.databinding.ActivitySmspoolLoginBinding
import sh.margot.dash.security.CredentialStore

/**
 * SMSPool's login page is protected by a captcha, so it can't be automated with a plain HTTP
 * POST. Instead this renders the real login page in a WebView — the user signs in (and solves
 * the captcha) exactly as they would in a browser — and we try to grab the personal API key
 * SMSPool exposes once authenticated through two independent channels, since we don't know
 * exactly how the site surfaces it:
 *  1. DOM/inline-script scraping, polled repeatedly (covers a key baked into rendered HTML,
 *     even if it appears after the page's own JS finishes rendering).
 *  2. A fetch/XHR hook injected before each page's scripts run, which forwards every network
 *     response body the page itself makes to Android — this catches a key that's only ever
 *     returned by an API call and never written into the DOM/global scope.
 */
class SmsPoolLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmspoolLoginBinding
    private var keyFound = false
    private var polling = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (keyFound) return
            tryExtractKeyFromDom()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val keyRegex = Regex(
        """(?i)\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|\bkey)["']?\s*[:=]\s*["']([A-Za-z0-9_-]{16,64})["']"""
    )

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmspoolLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

        // Always start from a clean slate — a stale/half-authenticated cookie from a previous
        // attempt can otherwise leave the WebView stuck on a broken or looping page.
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.addJavascriptInterface(NetworkBridge(), "DashNetworkBridge")
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Injected as early as possible so it wraps fetch/XHR before the page's own
                // scripts get a chance to issue requests we'd otherwise miss.
                view.evaluateJavascript(NETWORK_HOOK_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!keyFound && url?.contains("/login") != true) startPolling()
            }
        }
        binding.webView.loadUrl("https://www.smspool.net/login")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun tryExtractKeyFromDom() {
        val script = """
            (function() {
                var names = ['key', 'apiKey', 'api_key', 'token', 'accessToken', 'access_token', 'secretKey', 'secret_key'];
                for (var i = 0; i < names.length; i++) {
                    try { var v = window[names[i]]; if (v) return String(v); } catch (e) {}
                }
                var html = document.documentElement.innerHTML;
                var m = html.match(/\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|key)["']?\s*[:=]\s*["']([A-Za-z0-9_-]{16,64})["']/i);
                return m ? m[1] : ('nomatch:' + html.length);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            val value = result?.trim('"')
            if (!value.isNullOrBlank() && value != "null" && !value.startsWith("nomatch:")) onKeyFound(value)
        }
    }

    /** Called from the JS network hook with the body of every fetch/XHR response on the page. */
    private inner class NetworkBridge {
        @JavascriptInterface
        fun onResponseBody(body: String?) {
            if (body.isNullOrBlank()) return
            val match = keyRegex.find(body)?.groupValues?.getOrNull(1) ?: return
            handler.post { onKeyFound(match) }
        }
    }

    private fun onKeyFound(key: String) {
        if (keyFound) return
        keyFound = true
        handler.removeCallbacksAndMessages(null)
        CredentialStore.saveSecret(this, SmsPoolApiClient.SERVICE_ID, key)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MANUAL_KEY, 0, "Enter key manually")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_MANUAL_KEY) {
            showManualKeyDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showManualKeyDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Enter your SMSPool API key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { onKeyFound(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        const val MENU_MANUAL_KEY = 1
        const val POLL_INTERVAL_MS = 1500L

        // Wraps window.fetch and XMLHttpRequest so every response body the page itself receives
        // is also handed to Android, regardless of whether the page ever renders it into the DOM.
        const val NETWORK_HOOK_SCRIPT = """
            (function() {
                if (window.__dashNetHooked) return;
                window.__dashNetHooked = true;

                var report = function(body) {
                    try { if (window.DashNetworkBridge) window.DashNetworkBridge.onResponseBody(body); } catch (e) {}
                };

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(res) {
                            try { res.clone().text().then(report); } catch (e) {}
                            return res;
                        });
                    };
                }

                var OrigXHR = window.XMLHttpRequest;
                if (OrigXHR) {
                    var origSend = OrigXHR.prototype.send;
                    OrigXHR.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try { report(this.responseText); } catch (e) {}
                        });
                        return origSend.apply(this, arguments);
                    };
                }
            })();
        """
    }
}
