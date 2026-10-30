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
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
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
            rv.setImageViewBitmap(R.id.card, card(steps, kcal, minutes))
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

        // Весь виджет — одна квадратная картинка: всегда квадрат, как у Xiaomi.
        private fun card(steps: Int, kcal: Int, minutes: Int): Bitmap {
            val px = 600
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(px / 480f, px / 480f)
            val s = 480f
            val cx = s / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // стекло
            val rect = RectF(2f, 2f, s - 2f, s - 2f)
            p.shader = LinearGradient(0f, 0f, s, s, Color.parseColor("#5CFFFFFF"),
                Color.parseColor("#2EFFFFFF"), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, 72f, 72f, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.parseColor("#99FFFFFF")
            c.drawRoundRect(rect, 72f, 72f, p)

            // кольцо
            val accent = Color.parseColor("#FF6A3D")
            val stroke = 32f
            val r = 190f
            val oval = RectF(cx - r, cx - r, cx + r, cx + r)
            p.strokeWidth = stroke; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.parseColor("#55FF6A3D")
            c.drawOval(oval, p)
            val f = (steps.toFloat() / GOAL).coerceIn(0f, 1f)
            if (f > 0.005f) { p.color = accent; c.drawArc(oval, -90f, 360f * f, false, p) }
            val ang = Math.toRadians(-90.0 + 360.0 * f)
            p.style = Paint.Style.FILL; p.color = accent
            c.drawCircle(cx + r * cos(ang).toFloat(), cx + r * sin(ang).toFloat(), 19f, p)

            // строки: значок + число
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 56f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val values = listOf(steps.toString(), kcal.toString(), minutes.toString())
            val iconW = 46f; val gap = 14f; val step = 82f
            val block = iconW + gap + values.maxOf { tp.measureText(it) }
            val left = cx - block / 2f
            for (i in 0..2) {
                val cy = cx + (i - 1) * step
                c.save(); c.translate(left + iconW / 2f, cy)
                when (i) { 0 -> feet(c); 1 -> flame(c); else -> clock(c) }
                c.restore()
                c.drawText(values[i], left + iconW + gap, cy + 20f, tp)
            }
            return bmp
        }

        private fun feet(c: Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8A3D") }
            for ((dx, dy, rot) in listOf(Triple(-10f, 5f, -12f), Triple(10f, -5f, 12f))) {
                c.save(); c.translate(dx, dy); c.rotate(rot)
                c.drawOval(RectF(-7f, -2f, 7f, 18f), p)
                c.drawOval(RectF(-6f, -14f, 6f, -4f), p)
                c.restore()
            }
        }

        private fun flame(c: Canvas) {
            val path = Path().apply {
                moveTo(0f, -21f)
                cubicTo(5f, -11f, 17f, -4f, 16f, 8f)
                cubicTo(15f, 17f, 8f, 22f, 0f, 22f)
                cubicTo(-8f, 22f, -16f, 16f, -16f, 7f)
                cubicTo(-16f, -2f, -9f, -7f, -7f, -15f)
                cubicTo(-3f, -11f, -1f, -10f, 0f, -21f)
                close()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFC21A") }
            c.drawPath(path, p)
        }

        private fun clock(c: Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34C759") }
            c.drawCircle(0f, 0f, 21f, p)
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 3.6f
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(0f, 0f, 0f, -11f, p)
            c.drawLine(0f, 0f, 8f, 5f, p)
        }
    }
}
