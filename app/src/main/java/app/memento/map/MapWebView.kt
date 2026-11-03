package app.memento.map

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The one WebView showing the shared MapLibre page, created once (warmed up shortly after the app starts)
 * and moved between screens. Calls are `SMap.<fn>(json)`; the last value of each setter is kept, so the
 * page can be brought back to the same state after it reloads (renderer killed) or while it was paused.
 * While no screen shows the map, or the screen is off, the WebView is paused and nothing runs in it.
 * Main thread only.
 */
object MapHost {
    private const val TAG = "MapHost"
    /** Calls that only add to what a setter already said: dropped while paused, the screen resends a full state on return. */
    private val TRANSIENT = setOf("appendLive", "fit", "reset", "locFail")
    private val VIEW_SETTINGS = setOf("setMode", "setTheme", "setExag")

    private var web: WebView? = null
    private var ready = false
    private var paused = false
    private var started = false
    private var container: ViewGroup? = null
    private val state = LinkedHashMap<String, String>()
    private val dirty = LinkedHashSet<String>()
    private var fitPending = false
    private val main = Handler(Looper.getMainLooper())

    /** Receives the page's events (`ready`, `mode`, `follow`, `error`). One screen at a time. */
    var listener: ((type: String, event: Map<String, Any?>) -> Unit)? = null

    /** What the page currently shows, so a screen recreated by a rotation does not push everything again. */
    var session: Any? = null
        private set

    /** Bumped when the WebView had to be recreated; the views holding it rebuild. */
    var generation by mutableIntStateOf(0)
        private set

    val isReady: Boolean get() = ready

    fun warmUp(context: Context, dark: Boolean, mode3d: Boolean) {
        if (web == null) create(context.applicationContext, dark, mode3d)
    }

    fun attach(target: FrameLayout, dark: Boolean, mode3d: Boolean) {
        val w = web ?: create(target.context.applicationContext, dark, mode3d)
        (w.parent as? ViewGroup)?.removeView(w)
        target.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container = target
        updatePause()
    }

    fun detach(target: ViewGroup) {
        if (container !== target) return
        web?.let { target.removeView(it) }
        container = null
        updatePause()
    }

    /** The screen holding the map is started (visible) or stopped (screen off, app in the background). */
    fun setStarted(v: Boolean) {
        started = v
        updatePause()
    }

    /** Starts a new content: clears what the previous screen drew. False when [key] is already shown. */
    fun begin(key: Any): Boolean {
        if (session == key) return false
        session = key
        // the view settings belong to the map, not to the content: they survive a new content
        state.keys.retainAll(VIEW_SETTINGS)
        dirty.retainAll(VIEW_SETTINGS)
        fitPending = false
        call("setTrack", "null")
        call("setLines", "[]")
        call("setRoute", "null")
        call("setLive", "null")
        call("setDots", "[]")
        call("setLiveSquare", "null")
        call("setSquare", "null")
        call("setCursor", "null")
        call("setFollow", "false")
        call("setPos", "null")
        return true
    }

    /** `SMap.fn(arg)`; [arg] is already JSON (several arguments: comma-separated). */
    fun call(fn: String, arg: String = "") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { call(fn, arg) }
            return
        }
        if (fn == "fit") fitPending = true
        if (fn !in TRANSIENT) {
            state.remove(fn)
            state[fn] = arg
        }
        if (!ready || paused) {
            if (fn !in TRANSIENT) dirty.add(fn)
            return
        }
        eval(fn, arg)
        if (fn == "fit") fitPending = false
    }

    private fun eval(fn: String, arg: String) {
        web?.evaluateJavascript("window.SMap&&window.SMap.$fn($arg)", null)
    }

    private fun flush(all: Boolean) {
        val keys = if (all) state.keys.toList() else state.keys.filter { it in dirty }
        for (k in keys) eval(k, state.getValue(k))
        dirty.clear()
        if (fitPending) { eval("fit", ""); fitPending = false }
    }

    private fun updatePause() {
        val w = web ?: return
        val visible = container != null && started
        if (visible && paused) {
            paused = false
            w.onResume()
            w.resumeTimers()
            if (ready) flush(all = false)
        } else if (!visible && !paused && ready) {
            // before "ready" the page is still loading: pausing would stall it
            paused = true
            w.onPause()
            w.pauseTimers()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(app: Context, dark: Boolean, mode3d: Boolean): WebView {
        MapRuntime.init(app)
        val w = WebView(app)
        w.setBackgroundColor(if (dark) Color.rgb(0x1b, 0x1b, 0x1f) else Color.rgb(0xf4, 0xf1, 0xea))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        // the map takes every gesture, the screen's own scroll must not steal a vertical pan
        w.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        w.addJavascriptInterface(Bridge(), "SMapHost")
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                TileServer.handle(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == TileServer.HOST) return false
                // attribution links and the like open in the browser, never inside the map
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no browser for ${request.url}: $e")
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "map renderer gone (crash=${detail.didCrash()})")
                if (web === view) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    web = null
                    ready = false
                    paused = false
                    container = null
                    dirty.addAll(state.keys)
                    generation++
                }
                view.destroy()
                return true
            }
        }
        web = w
        ready = false
        paused = false
        // pauseTimers() is process-wide and outlives the WebView it was called on: after a renderer lost
        // while paused, the new page would otherwise never run a timer and never say "ready"
        w.resumeTimers()
        val url = TileServer.PAGE + "?mode=" + (if (mode3d) "3d" else "2d") + "&theme=" + (if (dark) "dark" else "light") + "&interactive=1"
        w.loadUrl(url)
        return w
    }

    private fun onPost(json: String) {
        val e = try {
            Json.parse(json).asMap()
        } catch (ex: Json.JsonException) {
            Log.w(TAG, "bad event $json: ${ex.message}")
            null
        } ?: return
        val type = e["type"] as? String ?: return
        when (type) {
            "ready" -> {
                ready = true
                if (paused || container == null || !started) {
                    dirty.addAll(state.keys)
                    updatePause()
                } else {
                    flush(all = true)
                }
            }
            "error" -> Log.w(TAG, "page: ${e["message"]}")
        }
        listener?.invoke(type, e)
    }

    private class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            main.post { onPost(json) }
        }
    }
}
