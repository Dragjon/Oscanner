package dev.olaf.oscanner.core.translate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Translates text on the phone with Mozilla's Bergamot models, which [TranslationModels]
 * downloads. The engine is bergamot-translator built for WebAssembly (the one Firefox runs),
 * so it runs in a WebView that's never shown: its page and the models are served from the app
 * itself, and nothing it does goes online.
 */
class Translator(context: Context, private val models: TranslationModels) : Closeable {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<List<String>>>()
    private val nextId = AtomicInteger(1)
    private var webView: WebView? = null
    /** Completes when the current page's engine has started. */
    private var loaded = CompletableDeferred<Unit>()

    /** The bridge's half on one page: called on a WebView thread. */
    private inner class Bridge(private val loaded: CompletableDeferred<Unit>) {
        @JavascriptInterface
        fun loaded() {
            loaded.complete(Unit)
        }

        @JavascriptInterface
        fun done(id: Int, json: String) {
            val texts = JSONArray(json).let { array -> List(array.length()) { array.getString(it) } }
            requests.remove(id)?.complete(texts)
        }

        @JavascriptInterface
        fun failed(id: Int, message: String) {
            val error = TranslationException(message)
            if (id == 0) loaded.completeExceptionally(error) else requests.remove(id)?.completeExceptionally(error)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(): CompletableDeferred<Unit> {
        if (webView != null) return loaded
        loaded = CompletableDeferred()
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetHandler())
            .addPathHandler("/models/", FileHandler(models.root))
            .build()
        WebView(appContext).apply {
            settings.javaScriptEnabled = true
            // The page needs nothing but its own files.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            addJavascriptInterface(Bridge(loaded), "Oscanner")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", null, 404, "Not found", null, null)
            }
            loadUrl("https://$HOST/assets/bergamot/index.html")
            webView = this
        }
        return loaded
    }

    /**
     * [texts] from [from] into [to], in order. Through English when there's no direct pair.
     * Throws [TranslationException] when a pair isn't downloaded or the engine fails.
     */
    suspend fun translate(from: String, to: String, texts: List<String>): List<String> {
        if (texts.isEmpty() || from == to) return texts
        val route = models.route(from, to) ?: throw TranslationException("No downloaded models from $from to $to")
        val request = JSONObject()
            .put("route", JSONArray(route.map { it.toJson() }))
            .put("texts", JSONArray(texts))
            .toString()
        withContext(Dispatchers.Main) { start() }.await()
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<List<String>>()
        requests[id] = result
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("oscannerTranslate($id, ${JSONObject.quote(request)})", null)
        }
        return result.await()
    }

    private fun InstalledModel.toJson() = JSONObject()
        .put("from", from)
        .put("to", to)
        .put("files", JSONObject(files.mapValues { (_, name) -> "https://$HOST/models/${dir.name}/$name" }))

    /**
     * Stops the engine and frees the memory its models hold (a few hundred MB). The next
     * translation starts it again.
     */
    override fun close() {
        main.post {
            webView?.destroy()
            webView = null
        }
        requests.values.forEach { it.completeExceptionally(TranslationException("Translator closed")) }
        requests.clear()
    }

    /** The engine's page and worker, from the APK's assets, with the types WebAssembly insists on. */
    private inner class AssetHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? = runCatching {
            WebResourceResponse(mimeOf(path), null, appContext.assets.open(path))
        }.onFailure { Log.w(TAG, "No asset $path") }.getOrNull()
    }

    /** Downloaded models, from [root] only. */
    private class FileHandler(private val root: File) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file = File(root, path).canonicalFile
            if (!file.path.startsWith(root.canonicalPath + File.separator) || !file.isFile) return null
            return WebResourceResponse("application/octet-stream", null, FileInputStream(file))
        }
    }

    private companion object {
        const val TAG = "Translator"
        const val HOST = WebViewAssetLoader.DEFAULT_DOMAIN

        fun mimeOf(path: String) = when (path.substringAfterLast('.')) {
            "wasm" -> "application/wasm"
            "js" -> "text/javascript"
            "html" -> "text/html"
            else -> "application/octet-stream"
        }
    }
}

class TranslationException(message: String) : Exception(message)
