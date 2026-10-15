package io.github.natusanima.slayerrevival

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * An on-screen joystick over the game that walks the player along the map without leaving the house.
 *
 * The game reads the phone's location, so this service takes the place of the phone's GPS: it registers Android test
 * location providers (this app must be chosen as the mock location app in Developer options) and publishes a position
 * that the joystick moves. The speed stays under the server's walking limit (DistanceIntegrity.MaxSpeed), so the
 * server's checks still pass; whether the distance counts toward walking tasks is the server's "Count walking with the
 * on-screen walk button" setting (Dashboard, Tuning). Stopping removes the test providers and the real GPS returns.
 * Coordinates are kept only in this app's private settings and are never logged.
 */
class WalkService : Service() {
    companion object {
        const val STOP = "io.github.natusanima.slayerrevival.WALK_STOP"
        /** Metres per second at full deflection: a brisk walk, under the server's 3.5 m/s limit. */
        const val MAX_SPEED = 2.5
        private const val ACCURACY = 5f
        private const val INTERVAL_MS = 1000L
        private const val METRES_PER_DEGREE = 111_320.0
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private const val PREFS = "walk"

        @Volatile
        var running = false
            private set

        /** What the main screen shows under the button: why the walk button is not on, or that it is. */
        @Volatile
        var status = ""
            private set

        /** Why the walk button cannot start yet, in words for the player, or null when it can. */
        fun problem(context: Context): String? = when {
            !Settings.canDrawOverlays(context) -> "Allow this app to display over other apps, so the joystick can sit on top of the game."
            !hasStart(context) -> "Allow this app to see the location, so the walk starts where you are."
            else -> null
        }

        fun hasLocationPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Forgets where the last walk ended: the next one starts from the phone's real location. */
        fun resetStart(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        private fun saved(context: Context): Pair<Double, Double>? {
            // Kept as text: a float would lose about a metre.
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return null
            return Pair(lat, lon)
        }

        private fun hasStart(context: Context) = saved(context) != null || hasLocationPermission(context)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manager: LocationManager? = null
    private var windows: WindowManager? = null
    private var pad: Joystick? = null
    private var lat = 0.0
    private var lon = 0.0
    private var lastTick = 0L
    private var lastSaved = 0L
    private var providers = emptyList<String>()

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || running) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(2, notification())
        val failure = begin()
        if (failure != null) {
            status = failure
            EventLog.write(this, "walk", "not started: $failure")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        status = "Walk button on"
        EventLog.write(this, "walk", "started")
        return START_NOT_STICKY
    }

    /** Takes over the location and shows the joystick; returns what went wrong, or null. */
    private fun begin(): String? {
        problem(this)?.let { return it }
        val manager = getSystemService(LocationManager::class.java)
        this.manager = manager
        val start = saved(this) ?: realLocation(manager) ?: return "No location is known yet. Open a maps app for a moment so the phone finds you, then try again."
        lat = start.first
        lon = start.second
        try {
            providers = PROVIDERS.filter { name ->
                try {
                    manager.removeTestProvider(name)
                } catch (_: Exception) {
                    // none was registered
                }
                @Suppress("DEPRECATION")
                manager.addTestProvider(name, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                manager.setTestProviderEnabled(name, true)
                true
            }
        } catch (_: SecurityException) {
            release()
            return "Choose this app as the mock location app: Settings > Developer options > Select mock location app."
        } catch (e: Exception) {
            release()
            return "The phone refused the walk button: ${e.message}"
        }
        val joystick = Joystick(this)
        val size = (160 * resources.displayMetrics.density).toInt()
        val margin = (24 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = margin
            y = margin * 2
        }
        try {
            windows = getSystemService(WindowManager::class.java).also { it.addView(joystick, params) }
        } catch (e: Exception) {
            release()
            return "The joystick could not be shown over the game: ${e.message}"
        }
        pad = joystick
        lastTick = SystemClock.elapsedRealtime()
        publish(0.0)
        handler.postDelayed(tick, INTERVAL_MS)
        return null
    }

    private fun realLocation(manager: LocationManager): Pair<Double, Double>? {
        if (!hasLocationPermission(this)) return null
        return listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { name -> try { manager.getLastKnownLocation(name) } catch (_: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos }?.let { Pair(it.latitude, it.longitude) }
    }

    /** One second of walking: moves the position by the joystick and publishes it. */
    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - lastTick) / 1000.0).coerceIn(0.0, 3.0)
        lastTick = now
        val joystick = pad ?: return
        val north = -joystick.pushY * MAX_SPEED * seconds // the screen's up is north
        val east = joystick.pushX * MAX_SPEED * seconds
        lat += north / METRES_PER_DEGREE
        lon += east / (METRES_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        lat = lat.coerceIn(-85.0, 85.0)
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        publish(hypot(north, east) / seconds.coerceAtLeast(0.001))
        if (now - lastSaved > 10_000) save()
    }

    private fun publish(metresPerSecond: Double) {
        val manager = manager ?: return
        for (name in providers) {
            val fix = Location(name).apply {
                latitude = lat
                longitude = lon
                accuracy = ACCURACY
                altitude = 0.0
                speed = metresPerSecond.toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try {
                manager.setTestProviderLocation(name, fix)
            } catch (_: Exception) {
                // the choice of mock location app was taken back: the next try says so, nothing to do here
            }
        }
    }

    private fun save() {
        lastSaved = SystemClock.elapsedRealtime()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
    }

    private fun release() {
        handler.removeCallbacks(tick)
        pad?.let { view -> try { windows?.removeView(view) } catch (_: Exception) {} }
        pad = null
        for (name in PROVIDERS) {
            try {
                manager?.removeTestProvider(name)
            } catch (_: Exception) {
                // it was never registered
            }
        }
        providers = emptyList()
    }

    override fun onDestroy() {
        if (running) {
            save()
            EventLog.write(this, "walk", "stopped")
            if (status == "Walk button on") status = ""
        }
        running = false
        release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("walk", "Walk button", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, WalkService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "walk")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Walk button")
            .setContentText("The joystick moves your character in the game")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** The joystick: [pushX] and [pushY] are the knob's push from -1 to 1 (up on the screen is negative pushY). */
    private class Joystick(context: Context) : View(context) {
        var pushX = 0.0
            private set
        var pushY = 0.0
            private set
        private var knobX = 0f
        private var knobY = 0f
        private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCD9A441.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCD9A441.toInt() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - ring.strokeWidth
            canvas.drawCircle(cx, cy, radius, base)
            canvas.drawCircle(cx, cy, radius, ring)
            canvas.drawCircle(cx + knobX * radius, cy + knobY * radius, radius * 0.32f, knob)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val radius = min(width, height) / 2f
                    var dx = (event.x - width / 2f) / radius
                    var dy = (event.y - height / 2f) / radius
                    val length = hypot(dx, dy)
                    if (length > 1f) {
                        dx /= length
                        dy /= length
                    }
                    knobX = dx
                    knobY = dy
                    // A small dead zone in the middle, so a resting thumb does not drift the character.
                    val push = min(1f, hypot(dx, dy))
                    val scale = if (push < 0.12f) 0f else (push - 0.12f) / 0.88f / push
                    pushX = (dx * scale).toDouble()
                    pushY = (dy * scale).toDouble()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knobX = 0f
                    knobY = 0f
                    pushX = 0.0
                    pushY = 0.0
                }
                else -> return false
            }
            invalidate()
            return true
        }
    }
}
