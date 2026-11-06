package com.juya.trend

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(8)

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#F6F3EE")
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        web = WebView(this)
        web.setBackgroundColor(bg)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") {
                    when (u.path) {
                        "/img" -> return imageResponse(u.getQueryParameter("u") ?: "")
                        "/fetch" -> return pageResponse(u.getQueryParameter("u") ?: "")
                    }
                }
                return assetLoader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openExternal(u.toString())
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__appBack ? window.__appBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        TrendWorker.channel(this)
        WorkService.channels(this)
        val open = intent?.getStringExtra("open")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html" +
            when {
                intent?.getBooleanExtra("news", false) == true -> "#news"
                !open.isNullOrBlank() -> "#open=" + Uri.encode(open)
                else -> ""
            })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("news", false)) web.evaluateJavascript("window.__openNews && window.__openNews()", null)
        val open = intent.getStringExtra("open")
        if (!open.isNullOrBlank()) web.evaluateJavascript("window.__openRep && window.__openRep(${JSONObject.quote(open)})", null)
    }

    /** 사진을 앱이 대신 받아서 화면에 넘김 (사이트가 막아 둔 사진도 보임) */
    private fun imageResponse(url: String): WebResourceResponse {
        return try {
            val r = if (url.startsWith("http")) Net.image(this, url) else null
            if (r == null) WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
            else WebResourceResponse(r.second, null, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=604800"), ByteArrayInputStream(r.first))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** 상품 페이지 글(HTML)을 가져옴 → 화면에서 사진 주소를 찾음 */
    private fun pageResponse(url: String): WebResourceResponse {
        val o = JSONObject()
        try {
            val r = Net.get(url, 2_500_000, "text/html,application/xhtml+xml,*/*;q=0.8")
            o.put("status", r.code).put("url", r.finalUrl).put("html", String(r.bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            o.put("status", 0).put("url", url).put("html", "").put("error", e.message ?: "net")
        }
        return WebResourceResponse("application/json", "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(o.toString().toByteArray()))
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { toast("열 수 있는 앱이 없어요") }
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun js(code: String) = ui.post { web.evaluateJavascript(code, null) }

    // 글자로만 읽으면 사진이 안 나오는 사이트(자라 등)를 위해, 보이지 않는 브라우저로 페이지를 열어 사진을 모음
    private val extractJs = """
        (function(){
          var out=[],seen={};
          function add(u){ if(!u||typeof u!=='string')return; try{u=new URL(u,location.href).href;}catch(e){return;}
            if(!/^https?:/.test(u)||seen[u])return; if(/\.svg(\?|$)|logo|icon|sprite|pixel|blank|transparent|placeholder/i.test(u))return;
            seen[u]=1; out.push(u); }
          document.querySelectorAll('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="og:image:secure_url"]').forEach(function(m){add(m.content);});
          function walk(o){ if(!o)return; if(Array.isArray(o)){o.forEach(walk);return;} if(typeof o==='object'){
            for(var k in o){ var v=o[k]; if(k==='image'||k==='images'||k==='contentUrl'){ if(typeof v==='string')add(v); else if(Array.isArray(v))v.forEach(function(x){ typeof x==='string'?add(x):x&&add(x.url||x.contentUrl); }); else if(v&&typeof v==='object')add(v.url||v.contentUrl);} else if(typeof v==='object')walk(v);} } }
          document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){ try{walk(JSON.parse(s.textContent));}catch(e){} });
          document.querySelectorAll('img,source').forEach(function(im){
            var w=im.naturalWidth||parseInt(im.getAttribute('width')||'0',10);
            var ss=im.getAttribute('srcset')||im.getAttribute('data-srcset');
            var src=im.currentSrc||im.getAttribute('src')||im.getAttribute('data-src');
            if(ss){ var best=ss.split(',').map(function(x){x=x.trim().split(/\s+/);return {u:x[0],w:parseInt(x[1]||'0',10)};}).sort(function(a,b){return b.w-a.w;})[0]; if(best&&best.w>=400){add(best.u);return;} }
            if(src&&w>=350)add(src);
          });
          return JSON.stringify(out.slice(0,14));
        })()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun render(id: String, url: String) {
        val w = WebView(this)
        var done = false
        fun finish(result: String) {
            if (done) return
            done = true
            js("window.__rendered && window.__rendered(${JSONObject.quote(id)}, $result)")
            ui.post { try { root.removeView(w); w.destroy() } catch (_: Exception) {} }
        }
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")
            blockNetworkImage = false
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) {
                view.evaluateJavascript("window.scrollTo(0,900)", null)
                ui.postDelayed({
                    if (!done) view.evaluateJavascript(extractJs) { r -> finish(if (r.isNullOrBlank() || r == "null") "\"[]\"" else r) }
                }, 3000)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !(request.url.scheme ?: "").startsWith("http")
        }
        // 화면 뒤에 숨겨서 염 (보이지 않음)
        root.addView(w, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        w.loadUrl(url)
        ui.postDelayed({ finish("\"[]\"") }, 22000)
    }

    /** 사진을 파일로 받아 두기 (공유·저장용) */
    private fun cacheImage(url: String): File? {
        val r = Net.image(this, url) ?: return null
        val ext = when { r.second.contains("png") -> "png"; r.second.contains("webp") -> "webp"; else -> "jpg" }
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "trend_${System.currentTimeMillis()}.$ext")
        f.writeBytes(r.first)
        return f
    }

    inner class Bridge {
        @JavascriptInterface
        fun aiConfig(): String {
            val p = getSharedPreferences("app", MODE_PRIVATE)
            val key = BuildConfig.GEMINI_KEY.ifBlank { p.getString("key", "") ?: "" }
            return JSONObject().put("gemini", key).put("built", BuildConfig.GEMINI_KEY.isNotBlank())
                .put("version", BuildConfig.VERSION_CODE).toString()
        }

        /** 구글 AI 검색을 휴대폰이 직접 함 (앱에서 나가도 끊기지 않게). 결과 → window.__ask(id, ok, 글) */
        @JavascriptInterface
        fun ask(id: String, prompt: String, search: Boolean) {
            io.execute {
                val p = getSharedPreferences("app", MODE_PRIVATE)
                val key = BuildConfig.GEMINI_KEY.ifBlank { p.getString("key", "") ?: "" }
                val out = try {
                    if (key.isBlank()) throw IllegalStateException("NOKEY")
                    val (text, src) = Net.gemini(key, prompt, search)
                    "true, " + JSONObject.quote(JSONObject().put("text", text).put("sources", src).toString())
                } catch (e: Exception) {
                    "false, " + JSONObject.quote(e.message ?: "AI 오류")
                }
                js("window.__ask && window.__ask(${JSONObject.quote(id)}, $out)")
            }
        }

        @JavascriptInterface
        fun setKey(k: String) { getSharedPreferences("app", MODE_PRIVATE).edit().putString("key", k.trim()).apply() }

        @JavascriptInterface
        fun render(id: String, url: String) { ui.post { this@MainActivity.render(id, url) } }

        @JavascriptInterface
        fun open(url: String) { ui.post { openExternal(url) } }

        @JavascriptInterface
        fun toast(s: String) { this@MainActivity.toast(s) }

        /** 옷 합성 앱(Vitrine AI)으로 사진 보내기 */
        @JavascriptInterface
        fun sendToVitrine(url: String) {
            io.execute {
                val f = try { cacheImage(url) } catch (_: Exception) { null }
                if (f == null) { toast("사진을 받지 못했어요"); return@execute }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val has = try { packageManager.getPackageInfo("com.juya.vitrine", 0); true } catch (_: Exception) { false }
                ui.post {
                    if (has) { send.setPackage("com.juya.vitrine"); startActivity(send) }
                    else { toast("Vitrine AI 앱이 없어서 공유 창을 열어요"); startActivity(Intent.createChooser(send, "보내기")) }
                }
            }
        }

        @JavascriptInterface
        fun shareImage(url: String) {
            io.execute {
                val f = try { cacheImage(url) } catch (_: Exception) { null }
                if (f == null) { toast("사진을 받지 못했어요"); return@execute }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ui.post { startActivity(Intent.createChooser(send, "공유")) }
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            ui.post {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "공유"))
            }
        }

        /** 사진첩(Pictures/트렌드레이더)에 저장 */
        @JavascriptInterface
        fun saveImage(url: String) {
            io.execute {
                try {
                    val r = Net.image(this@MainActivity, url) ?: run { toast("사진을 받지 못했어요"); return@execute }
                    if (Build.VERSION.SDK_INT >= 29) {
                        val cv = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "trend_${System.currentTimeMillis()}")
                            put(MediaStore.Images.Media.MIME_TYPE, r.second)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrendRadar")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(r.first) }
                        toast("사진첩에 저장했어요")
                    } else {
                        shareImage(url)
                    }
                } catch (e: Exception) { toast("저장하지 못했어요") }
            }
        }

        /** 새 상품 알림 받을 브랜드 목록 저장 → 하루 한 번 확인 */
        @JavascriptInterface
        fun setWatch(json: String) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putString("watch", json).apply()
            val n = try { org.json.JSONArray(json).length() } catch (_: Exception) { 0 }
            TrendWorker.schedule(this@MainActivity, n > 0)
            if (n > 0 && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ui.post { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }

        @JavascriptInterface
        fun getWatch(): String = getSharedPreferences("app", MODE_PRIVATE).getString("watch", "[]") ?: "[]"

        @JavascriptInterface
        fun getNews(): String = getSharedPreferences("app", MODE_PRIVATE).getString("news", "[]") ?: "[]"

        @JavascriptInterface
        fun setNews(json: String) { getSharedPreferences("app", MODE_PRIVATE).edit().putString("news", json).apply() }

        /** 조사 시작: 알림을 띄우고 앱에서 나가도 계속 돌게 함 */
        @JavascriptInterface
        fun workStart(text: String) {
            ui.post {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                try {
                    val i = Intent(this@MainActivity, WorkService::class.java).putExtra("text", text)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun workUpdate(text: String) {
            try { androidx.core.app.NotificationManagerCompat.from(this@MainActivity).notify(WorkService.ID, WorkService.progress(this@MainActivity, text)) } catch (_: SecurityException) {}
        }

        @JavascriptInterface
        fun workStop() {
            try { stopService(Intent(this@MainActivity, WorkService::class.java)) } catch (_: Exception) {}
        }

        /** 끝났다는 알림 (소리·진동) — 누르면 그 리포트가 열림 */
        @JavascriptInterface
        fun notifyDone(title: String, text: String, key: String) {
            try { androidx.core.app.NotificationManagerCompat.from(this@MainActivity).notify(5000 + (key.hashCode() and 0xfff), WorkService.done(this@MainActivity, title, text, key)) } catch (_: SecurityException) {}
        }

        @JavascriptInterface
        fun checkNow() { TrendWorker.now(this@MainActivity); toast("새 상품을 확인하고 있어요. 끝나면 알림이 와요") }
    }
}
