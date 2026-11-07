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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 매주 월요일 아침: 이번 주 바뀐 점 3줄 알림 */
class DigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val CH = "digest"
        private const val NAME = "weekly-digest"

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CH, "월요일 요점 알림", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        /** 다음 월요일 아침 8시까지 남은 시간 */
        private fun delayToMonday(): Long {
            val now = Calendar.getInstance()
            val t = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            if (!t.after(now)) t.add(Calendar.WEEK_OF_YEAR, 1)
            return t.timeInMillis - now.timeInMillis
        }

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<DigestWorker>(7, TimeUnit.DAYS, 3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayToMonday(), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<DigestWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val p = applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        val key = BuildConfig.GEMINI_KEY.ifBlank { p.getString("key", "") ?: "" }
        if (key.isBlank()) return Result.success()
        val ctx = p.getString("digestCtx", "") ?: ""
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val prompt = """
            오늘은 $today (월요일). 상파울루 봉헤치로 여성복 도매 사장님에게 보낼 '이번 주 요점'을 만들어.
            구글 검색으로 최근 7일 안의 브라질 여성복·원단 소식(원단 회사 신상, 브라질 쇼핑몰·인스타 인기, 세계 패션 흐름)을 찾아서
            아래 사장님 상황에 맞게 바뀐 점·할 일 3줄만.
            규칙: 줄마다 단답 20자 안팎, 문장으로 길게 쓰지 마. 원단 이름은 포르투갈어 그대로. 확인된 것만.
            파티복은 빼.
            [사장님 상황]
            $ctx
            답은 JSON 하나만: {"lines":["...","...","..."]}
        """.trimIndent()
        val text = try { Net.gemini(key, prompt).first } catch (_: Exception) { return Result.retry() }
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        val lines = try { JSONObject(text.substring(a, b + 1)).optJSONArray("lines") } catch (_: Exception) { null } ?: return Result.success()
        val list = ArrayList<String>()
        for (i in 0 until minOf(3, lines.length())) lines.optString(i).trim().takeIf { it.isNotBlank() }?.let { list.add(it) }
        if (list.isEmpty()) return Result.success()

        val all = try { JSONArray(p.getString("digests", "[]")) } catch (_: Exception) { JSONArray() }
        all.put(JSONObject().put("at", today).put("lines", JSONArray(list)))
        val keep = JSONArray(); for (j in maxOf(0, all.length() - 12) until all.length()) keep.put(all.get(j))
        p.edit().putString("digests", keep.toString()).apply()

        val i = Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(applicationContext, 8, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = list.joinToString("\n") { "• $it" }
        val n = NotificationCompat.Builder(applicationContext, CH)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("📅 이번 주 요점").setContentText(list.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(applicationContext).notify(78, n) } catch (_: SecurityException) {}
        return Result.success()
    }
}
