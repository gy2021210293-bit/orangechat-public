/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.LinkedList

/**
 * 插件 WebView 预热与复用池
 *
 * 采用类似微信小程序的容器预热机制：
 * 1. 预先在主线程空闲时初始化空的 WebView 实例，提前启动 Chromium 渲染进程及 V8 运行时；
 * 2. 使用 MutableContextWrapper 包装 Context，避免因长期持有 Activity 引发内存泄漏；
 * 3. 插件页面进入时直接秒级复用，离开时清理状态、重置 URL 并回收到池中；
 * 4. 严格控制池容量（默认 1 个），兼顾极致的秒开速度与内存占用。
 */
object PluginWebViewPool {
    private const val TAG = "PluginWebViewPool"
    private const val MAX_POOL_SIZE = 1

    private val pool = LinkedList<WebView>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 预热 WebView 实例
     * @param context Application 上下文或任意 Context
     */
    fun preheat(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { preheat(context) }
            return
        }

        synchronized(pool) {
            if (pool.size >= MAX_POOL_SIZE) {
                return
            }
            try {
                val appContext = context.applicationContext
                val wrapper = MutableContextWrapper(appContext)
                val webView = createConfiguredWebView(wrapper)
                // 预加载 about:blank，促使 Chromium 内核彻底完成初始化并就绪
                webView.loadUrl("about:blank")
                pool.add(webView)
                Log.d(TAG, "Preheated 1 WebView instance, pool size: ${pool.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preheat WebView", e)
            }
        }
    }

    /**
     * 确保池中至少有一个预热的实例可用
     */
    fun ensurePreheated(context: Context) {
        synchronized(pool) {
            if (pool.isEmpty()) {
                preheat(context)
            }
        }
    }

    /**
     * 获取一个可用的 WebView 实例
     * @param context 当前页面所在的 Context (通常为 Activity)
     */
    fun acquire(context: Context): WebView {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("PluginWebViewPool.acquire must be called on Main Thread")
        }

        val webView = synchronized(pool) {
            if (pool.isNotEmpty()) {
                Log.d(TAG, "Reusing preheated WebView from pool")
                pool.removeFirst()
            } else {
                null
            }
        } ?: run {
            Log.d(TAG, "Pool empty, creating a new WebView on the fly")
            val wrapper = MutableContextWrapper(context.applicationContext)
            createConfiguredWebView(wrapper)
        }

        // 切换 Context 到当前页面所在的 Activity
        (webView.context as? MutableContextWrapper)?.baseContext = context

        // 重新确保基础设置生效
        applyBaseSettings(webView)
        webView.onResume()

        return webView
    }

    /**
     * 归还并重置 WebView 实例
     */
    fun release(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release(webView) }
            return
        }

        try {
            // 1. 剥离父布局
            (webView.parent as? ViewGroup)?.removeView(webView)

            // 2. 停止加载与解绑回调，暂停活动
            webView.stopLoading()
            webView.onPause()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()

            // 3. 清理页面与历史，防止跨插件状态与数据污染
            webView.loadUrl("about:blank")
            webView.clearHistory()

            // 4. 重置 Context 为 ApplicationContext，彻底切断对 Activity 的引用
            val wrapper = webView.context as? MutableContextWrapper
            val appContext = wrapper?.baseContext?.applicationContext
            if (wrapper != null && appContext != null) {
                wrapper.baseContext = appContext
            }

            synchronized(pool) {
                if (pool.size < MAX_POOL_SIZE) {
                    pool.add(webView)
                    Log.d(TAG, "Returned WebView to pool, pool size: ${pool.size}")
                } else {
                    Log.d(TAG, "Pool full, destroying surplus WebView")
                    webView.destroy()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WebView safely", e)
            try {
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * 内存紧张时清空池
     */
    fun clear() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { clear() }
            return
        }
        synchronized(pool) {
            while (pool.isNotEmpty()) {
                val wv = pool.removeFirst()
                try {
                    wv.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to destroy WebView on clear", e)
                }
            }
            Log.d(TAG, "PluginWebViewPool cleared")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(wrapper: MutableContextWrapper): WebView {
        return WebView(wrapper).apply {
            setBackgroundColor(Color.TRANSPARENT)
            applyBaseSettings(this)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyBaseSettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }
}
