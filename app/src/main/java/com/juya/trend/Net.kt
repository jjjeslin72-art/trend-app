package com.juya.trend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 인터넷에서 페이지·사진 가져오기 (앱 안에서는 브라우저 제한(CORS)이 없음) */
object Net {
    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    // 인스타·페이스북은 링크 미리보기용 이름으로 물어야 사진 정보(og:image)를 줌
    private const val UA_PREVIEW = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    class Result(val code: Int, val bytes: ByteArray, val type: String, val finalUrl: String)

    fun get(url: String, maxBytes: Int = 3_000_000, accept: String = "*/*"): Result {
        var u = url
        repeat(7) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12000
            c.readTimeout = 20000
            val host = URL(u).host.lowercase()
            val preview = host.contains("instagram.com") || host.contains("facebook.com") || host.contains("threads.net")
            c.setRequestProperty("User-Agent", if (preview) UA_PREVIEW else UA_MOBILE)
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,pt-BR;q=0.8,en;q=0.7,es;q=0.6")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return Result(code, ByteArray(0), "", u)
                u = URL(URL(u), loc).toString()
                c.disconnect()
                return@repeat
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { s ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0 || out.size() > maxBytes) break
                    out.write(buf, 0, n)
                }
            }
            val type = c.contentType ?: ""
            c.disconnect()
            return Result(code, out.toByteArray(), type, u)
        }
        return Result(310, ByteArray(0), "", u)
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 사진 (휴대폰 안에 저장해 두고 다시 씀) */
    fun image(ctx: Context, url: String): Pair<ByteArray, String>? {
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        val f = File(dir, hash(url))
        val t = File(dir, hash(url) + ".t")
        if (f.exists() && f.length() > 0) return f.readBytes() to (if (t.exists()) t.readText() else "image/jpeg")
        val r = get(url, 8_000_000, "image/avif,image/webp,image/*,*/*;q=0.8")
        if (r.code !in 200..299 || r.bytes.isEmpty()) return null
        val type = r.type.substringBefore(";").ifBlank { "image/jpeg" }
        if (!type.startsWith("image/")) return null
        try { f.writeBytes(r.bytes); t.writeText(type) } catch (_: Exception) {}
        return r.bytes to type
    }

    /** 구글 AI(Gemini) + 구글 검색. 답 글자와 출처 목록을 돌려줌 */
    fun gemini(key: String, prompt: String, search: Boolean = true): Pair<String, JSONArray> {
        var last = "AI"
        for (model in listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")) {
            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.3))
                if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 180000
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                if (code !in 200..299) {
                    val msg = try { JSONObject(txt).optJSONObject("error")?.optString("message") ?: "" } catch (_: Exception) { "" }
                    if (code == 400 && msg.contains("API key", true)) throw IllegalStateException("BADKEY")
                    last = if (msg.contains("billing", true) || msg.contains("FAILED_PRECONDITION")) "구글 AI 결제(카드) 연결이 필요해요"
                           else if (code == 429) "구글 AI 사용량이 잠깐 많아요. 조금 뒤에 다시 해 주세요"
                           else msg.ifBlank { "http $code" }
                    continue
                }
                val d = JSONObject(txt)
                val cand = d.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                val src = JSONArray()
                cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { ch ->
                    for (i in 0 until ch.length()) ch.optJSONObject(i)?.optJSONObject("web")?.let { src.put(it) }
                }
                if (sb.isNotBlank()) return sb.toString() to src
            } catch (e: IllegalStateException) { throw e
            } catch (e: Exception) { last = "인터넷 연결이 끊겼어요 (" + (e.javaClass.simpleName) + ")" }
        }
        throw RuntimeException(last)
    }

    /** 글 속에서 JSON 배열/객체 꺼내기 */
    fun jsonArray(text: String): JSONArray? {
        val a = text.indexOf('['); val b = text.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return try { JSONArray(text.substring(a, b + 1)) } catch (_: Exception) { null }
    }
}
