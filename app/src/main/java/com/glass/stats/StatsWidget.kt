package com.glass.stats

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class StatsWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK -> refresh(context)
            Intent.ACTION_BOOT_COMPLETED -> { schedule(context); refresh(context) }
        }
    }

    override fun onEnabled(context: Context) = schedule(context)

    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(context))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        schedule(context)
        val pending = goAsync()
        readSteps(context) { steps ->
            val views = buildViews(context, steps)
            ids.forEach { manager.updateAppWidget(it, views) }
            pending.finish()
        }
    }

    companion object {
        const val GOAL = 8000
        private const val PREFS = "stats"
        private const val ACTION_TICK = "com.glass.stats.TICK"
        private const val INTERVAL = 5 * 60 * 1000L // обновление каждые ~5 минут

        private fun tickIntent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, StatsWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC, System.currentTimeMillis() + INTERVAL, INTERVAL, tickIntent(ctx)
            )
        }

        fun refresh(ctx: Context) {
            val ids = AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setClass(ctx, StatsWidget::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        private fun today() = LocalDate.now().toString()

        // Считает шаги за день по счётчику шагов (значение с момента перезагрузки).
        private fun update(p: SharedPreferences, raw: Float): Int {
            var base = p.getFloat("base", -1f)
            var carry = p.getInt("carry", 0)
            val last = p.getFloat("last", -1f)
            if (p.getString("day", "") != today() || base < 0f) {
                base = raw; carry = 0
            } else if (raw < last) { // перезагрузка телефона
                carry += (last - base).toInt(); base = 0f
            }
            val steps = carry + (raw - base).toInt()
            p.edit().putString("day", today()).putFloat("base", base).putInt("carry", carry)
                .putFloat("last", raw).putInt("steps", steps).apply()
            return steps
        }

        private fun readSteps(ctx: Context, done: (Int) -> Unit) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = { if (p.getString("day", "") == today()) p.getInt("steps", 0) else 0 }
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val granted = ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            if (sensor == null || !granted) { done(cached()); return }

            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            lateinit var listener: SensorEventListener
            fun finish(value: Int) {
                if (finished.compareAndSet(false, true)) {
                    sm.unregisterListener(listener)
                    handler.removeCallbacksAndMessages(null)
                    done(value)
                }
            }
            listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { finish(update(p, e.values[0])) }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            handler.postDelayed({ finish(cached()) }, 4000)
        }

        private fun buildViews(ctx: Context, steps: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_stats)
            val kcal = (steps * 0.06).roundToInt()
            val minutes = if (steps > 0) ceil(steps / 110.0).toInt() else 0
            rv.setTextViewText(R.id.steps, steps.toString())
            rv.setTextViewText(R.id.kcal, kcal.toString())
            rv.setTextViewText(R.id.minutes, minutes.toString())
            rv.setImageViewBitmap(R.id.ring, ring(steps.toFloat() / GOAL))
            rv.setOnClickPendingIntent(R.id.root, openHealth(ctx))
            return rv
        }

        // Открывает встроенное приложение Xiaomi «Здоровье».
        private fun openHealth(ctx: Context): PendingIntent {
            val pm = ctx.packageManager
            val intent = listOf("com.mi.health", "com.xiaomi.wearable")
                .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                ?: Intent(ctx, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun ring(frac: Float): Bitmap {
            val s = 400
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = 34f
            val cx = s / 2f
            val r = cx - stroke / 2 - 22f
            val oval = RectF(cx - r, cx - r, cx + r, cx + r)
            val accent = Color.parseColor("#FF6A3D")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
            }
            paint.color = Color.parseColor("#40FF6A3D")
            c.drawOval(oval, paint)
            val f = frac.coerceIn(0f, 1f)
            if (f > 0.005f) {
                paint.color = accent
                c.drawArc(oval, -90f, 360f * f, false, paint)
            }
            val a = Math.toRadians(-90.0 + 360.0 * f)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            c.drawCircle(cx + r * cos(a).toFloat(), cx + r * sin(a).toFloat(), 22f, dot)
            return bmp
        }
    }
}
