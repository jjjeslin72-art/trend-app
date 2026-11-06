package com.juya.trend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 하루 한 번: 알림 켠 브랜드의 새 상품을 구글 검색으로 확인 → 새로 나온 게 있으면 알림 */
class TrendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val CH = "news"
        private const val NAME = "trend-watch"

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CH, "새 상품 알림", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<TrendWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<TrendWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    override suspend fun doWork(): Result {
        val p = applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        val key = BuildConfig.GEMINI_KEY.ifBlank { p.getString("key", "") ?: "" }
        if (key.isBlank()) return Result.success()
        val watch = try { JSONArray(p.getString("watch", "[]")) } catch (_: Exception) { JSONArray() }
        val news = try { JSONArray(p.getString("news", "[]")) } catch (_: Exception) { JSONArray() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val found = ArrayList<String>()

        for (i in 0 until watch.length()) {
            val w = watch.optJSONObject(i) ?: continue
            val brand = w.optString("name")
            if (brand.isBlank()) continue
            val countries = w.optString("countries", "스페인, 미국, 한국")
            val info = w.optString("info", "")
            val prompt = """
                오늘은 $today 이야. 구글 검색으로 '$brand' ($info) 의 공식 온라인몰·인스타그램·왓츠앱 카탈로그에서
                최근 7일 안에 새로 올라온 여성복 신상품을 찾아줘. 볼 나라: $countries.
                지어내지 말고 검색에서 확인한 것만, 최대 10개.
                답은 JSON 배열만: [{"name":"상품 이름(원래 이름)","type":"종류(한국어, 예: 원피스)","url":"상품/게시물 주소 또는 빈칸"}]
            """.trimIndent()
            val arr = try { Net.jsonArray(Net.gemini(key, prompt).first) } catch (_: Exception) { null } ?: continue

            val seenKey = "seen_" + norm(brand)
            val first = !p.contains(seenKey)
            val seen = try { JSONArray(p.getString(seenKey, "[]")) } catch (_: Exception) { JSONArray() }
            val seenSet = HashSet<String>().apply { for (j in 0 until seen.length()) add(seen.optString(j)) }
            var newCount = 0
            for (j in 0 until arr.length()) {
                val o = arr.optJSONObject(j) ?: continue
                val n = norm(o.optString("name"))
                if (n.isBlank() || seenSet.contains(n)) continue
                seenSet.add(n); seen.put(n)
                if (!first) {
                    newCount++
                    news.put(JSONObject().put("brand", brand).put("name", o.optString("name"))
                        .put("type", o.optString("type")).put("url", o.optString("url")).put("at", today))
                }
            }
            // 너무 길어지지 않게 최근 300개만 기억
            val trimmed = JSONArray(); val from = maxOf(0, seen.length() - 300)
            for (j in from until seen.length()) trimmed.put(seen.optString(j))
            p.edit().putString(seenKey, trimmed.toString()).apply()
            if (newCount > 0) found.add("$brand ${newCount}개")
        }

        val keep = JSONArray(); val from = maxOf(0, news.length() - 150)
        for (j in from until news.length()) keep.put(news.get(j))
        p.edit().putString("news", keep.toString()).apply()

        if (found.isNotEmpty()) notify("🆕 새 상품이 올라왔어요", found.joinToString(" · "))
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        val i = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("news", true); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(applicationContext, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, CH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(applicationContext).notify(77, n) } catch (_: SecurityException) {}
    }
}
