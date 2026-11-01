package io.github.washingtongif.foldfix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Watches a fold signal you trust (hinge angle or a raw input event) and, when it says
 * "closed", forces Android's device state to CLOSED via `cmd device_state` (Shizuku).
 * When it says "open", it releases the override (or forces OPENED if configured).
 */
class FoldService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "io.github.washingtongif.foldfix.STOP"
        const val ACTION_POSTURE = "io.github.washingtongif.foldfix.POSTURE"
        const val EXTRA_POSTURE = "posture"
        const val ACTION_ROTATE = "io.github.washingtongif.foldfix.ROTATE"

        /** "auto" = follow the sensor, "tabletop" = force HALF_OPENED, "tent" = force outer screen. */
        @Volatile var posture = "auto"
        private const val CHANNEL = "foldfix"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var lastAngle: Float? = null
        private val logLines = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        /** Set by the service so log lines also go to a file that survives restarts. */
        @Volatile var logFile: java.io.File? = null

        fun log(msg: String) = synchronized(logLines) {
            if (logLines.isEmpty()) loadLogFile()
            val line = "${fmt.format(Date())}  $msg"
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
            try { logFile?.writeText(logLines.joinToString("\n")) } catch (e: Throwable) {}
        }

        private fun loadLogFile() = try {
            logFile?.takeIf { it.exists() }?.readLines()?.takeLast(200)?.forEach { logLines.addLast(it) }
        } catch (e: Throwable) {}

        fun logText(ctx: Context? = null): String = synchronized(logLines) {
            if (logFile == null && ctx != null) logFile = java.io.File(ctx.filesDir, "foldfix-log.txt")
            if (logLines.isEmpty()) loadLogFile()
            logLines.reversed().joinToString("\n")
        }

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, FoldService::class.java))

        fun setPosture(ctx: Context, p: String) {
            val i = Intent(ctx, FoldService::class.java).setAction(ACTION_POSTURE).putExtra(EXTRA_POSTURE, p)
            if (running) ctx.startService(i) else ctx.startForegroundService(i)
        }

        fun nextPosture(p: String) = when (p) { "auto" -> "tabletop"; "tabletop" -> "tent"; else -> "auto" }

        fun label(p: String) = when (p) { "tabletop" -> "Tabletop"; "tent" -> "Tent"; else -> "Auto" }

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, FoldService::class.java).setAction(ACTION_STOP))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var sensorManager: SensorManager? = null
    private var inputProc: Process? = null
    private var inputThread: Thread? = null

    /** null = unknown, true = closed, false = open */
    @Volatile private var applied: Boolean? = null
    /** Last open/closed reading from the sensor, kept even while a manual posture is active. */
    @Volatile private var sensorClosed: Boolean? = null
    private var closedId = 0
    private var halfId = 1
    private var tentId = 0

    /** Escape hatch: pressing the power button (screen off) always ends a manual posture. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (posture != "auto") {
                log("Power button — manual posture ended")
                posture = "auto"
                applyPosture()
            }
        }
    }
    private var openedId = -1
    private var started = false

    private val binderListener = Shizuku.OnBinderReceivedListener { onShizukuMaybeReady() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> onShizukuMaybeReady() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker.execute { releaseOverride("stopped by user") }
            worker.execute { stopSelf() }
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            prefs = Prefs(this)
            logFile = java.io.File(filesDir, "foldfix-log.txt")
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            startForeground(
                NOTIF_ID, notification("Waiting for Shizuku…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addRequestPermissionResultListener(permListener)
        }
        if (intent?.action == ACTION_ROTATE && posture == "tent") {
            prefs.tentRotation = (prefs.tentRotation + 1) % 4
            worker.execute { lockTentRotation(); updateNotification("Posture: Tent") }
        }
        if (intent?.action == ACTION_POSTURE) {
            posture = intent.getStringExtra(EXTRA_POSTURE) ?: "auto"
            applyPosture()
        }
        return START_STICKY
    }

    private fun onShizukuMaybeReady() {
        if (started || !Shell.ready()) return
        started = true
        worker.execute {
            val p = prefs
            closedId = if (p.closedStateId >= 0) p.closedStateId else DeviceStates.idFor("CLOSED", 0)
            openedId = DeviceStates.idFor("OPENED", -1)
            halfId = DeviceStates.idFor("HALF_OPENED", 1)
            tentId = DeviceStates.idFor("REAR_DISPLAY_STATE", closedId)
            restoreRotation()
            log("Shizuku ready. CLOSED id=$closedId, OPENED id=$openedId, mode=${p.mode}")
            when (p.mode) {
                "input" -> startInputWatch(p)
                "sensor" -> startSensorWatch(p)
                else -> startHingeWatch(p)
            }
            updateNotification("Active (${p.mode} mode)")
            if (posture != "auto") applyPosture()
        }
    }

    /** Applies the manual posture, or hands back to the sensor when it is "auto". */
    private fun applyPosture() {
        if (!started) return
        worker.execute {
            when (posture) {
                "tabletop" -> {
                    restoreRotation()
                    val r = Shell.run("cmd device_state state $halfId")
                    log("Manual: Tabletop → state $halfId ${if (r.isNotEmpty()) "[$r]" else ""}")
                }
                "tent" -> {
                    val r = Shell.run("cmd device_state state $tentId")
                    log("Manual: Tent → state $tentId (outer screen; power button to exit) ${if (r.isNotEmpty()) "[$r]" else ""}")
                    lockTentRotation()
                }
                else -> {
                    restoreRotation()
                    log("Manual: Auto — following the sensor again")
                    applied = null
                    sensorClosed?.let { decide(it, "back to auto") }
                }
            }
            updateNotification("Posture: ${label(posture)}")
            TileUpdater.refresh(this)
        }
    }

    // ---------- hinge-angle source ----------

    private fun startHingeWatch(p: Prefs) {
        val sm = getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (sensor == null) {
            log("No hinge-angle sensor found — switch to input mode.")
            return
        }
        sensorManager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        log("Watching ${sensor.name}: closed < ${p.closeBelow}°, open > ${p.openAbove}°")
    }

    // ---------- any-sensor source (e.g. a hall / magnet sensor) ----------

    private fun startSensorWatch(p: Prefs) {
        val sm = getSystemService(SensorManager::class.java)
        val needle = p.sensorName.trim().lowercase()
        val matches = sm.getSensorList(Sensor.TYPE_ALL).filter {
            needle.isNotEmpty() && it.name.lowercase().contains(needle)
        }
        val sensor = matches.firstOrNull { it.isWakeUpSensor } ?: matches.firstOrNull()
        if (sensor == null) {
            log("No sensor name contains \"${p.sensorName}\".")
            return
        }
        sensorManager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        val rule = if (p.sensorClosedAbove) ">" else "<"
        log("Watching ${sensor.name}: closed when value[${p.sensorIndex}] $rule ${p.sensorThreshold}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (prefs.mode == "sensor") {
            val v = event.values.getOrNull(prefs.sensorIndex) ?: return
            val closed = if (prefs.sensorClosedAbove) v > prefs.sensorThreshold else v < prefs.sensorThreshold
            decide(closed, "${event.sensor.name} = ${"%.2f".format(v)}")
            return
        }
        val angle = event.values[0]
        lastAngle = angle
        when {
            angle < prefs.closeBelow -> decide(true, "hinge ${"%.1f".format(angle)}°")
            angle > prefs.openAbove -> decide(false, "hinge ${"%.1f".format(angle)}°")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- raw input-event source (hall sensor / lid switch) ----------

    private fun startInputWatch(p: Prefs) {
        val pattern = p.inputPattern.trim()
        if (pattern.isEmpty()) { log("Input pattern is empty."); return }
        val proc = try { Shell.start("getevent -lq") } catch (e: Throwable) {
            log("Could not start getevent: $e"); return
        }
        inputProc = proc
        inputThread = Thread {
            log("Watching input events matching \"$pattern\"")
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.contains(pattern)) return@forEachLine
                    val value = line.trim().split(Regex("\\s+")).lastOrNull()?.toLongOrNull(16)
                        ?: return@forEachLine
                    val closed = (value != 0L) != p.inputInvert
                    decide(closed, "input value $value")
                }
            } catch (e: Throwable) {
                if (running) log("Input watch ended: $e")
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- applying the state ----------

    private fun decide(closed: Boolean, why: String) {
        val wasClosed = sensorClosed
        sensorClosed = closed
        if (posture != "auto") {
            // Closing the phone fully always ends a manual posture.
            if (closed && wasClosed != true) {
                posture = "auto"
                log("Phone closed — manual posture ended")
                applied = null
                TileUpdater.refresh(this)
            } else return
        }
        if (applied == closed) return
        applied = closed
        worker.execute {
            if (closed) {
                val r = Shell.run("cmd device_state state $closedId")
                log("CLOSED ($why) → state $closedId ${if (r.isNotEmpty()) "[$r]" else ""}")
                updateNotification("Forced CLOSED")
            } else if (prefs.forceOpen && openedId >= 0) {
                val r = Shell.run("cmd device_state state $openedId")
                log("OPEN ($why) → state $openedId ${if (r.isNotEmpty()) "[$r]" else ""}")
                updateNotification("Forced OPENED")
            } else {
                releaseOverride("OPEN ($why)")
                updateNotification("Open — Android in control")
            }
        }
    }

    /** Tent: save the user's rotation settings once, then lock to the chosen rotation. */
    private fun lockTentRotation() {
        if (prefs.savedRotation.isEmpty()) {
            val auto = Shell.run("settings get system accelerometer_rotation").ifEmpty { "1" }
            val user = Shell.run("settings get system user_rotation").let { if (it == "null" || it.isEmpty()) "0" else it }
            prefs.savedRotation = "$auto,$user"
        }
        val rot = prefs.tentRotation
        Shell.run("settings put system accelerometer_rotation 0; settings put system user_rotation $rot")
        log("Tent rotation locked to ${rot * 90}°")
    }

    /** Puts back whatever rotation settings were in place before Tent. */
    private fun restoreRotation() {
        val saved = prefs.savedRotation
        if (saved.isEmpty()) return
        val (auto, user) = saved.split(",").let { (it.getOrNull(0) ?: "1") to (it.getOrNull(1) ?: "0") }
        Shell.run("settings put system user_rotation $user; settings put system accelerometer_rotation $auto")
        prefs.savedRotation = ""
        log("Rotation settings restored")
    }

    private fun releaseOverride(why: String) {
        if (!Shell.ready()) return
        restoreRotation()
        val r = Shell.run("cmd device_state state reset")
        log("$why → override released ${if (r.isNotEmpty()) "[$r]" else ""}")
    }

    // ---------- notification ----------

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Fold correction", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("FoldFix")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(postureAction("tabletop", 2))
            .addAction(if (posture == "tent") rotateAction() else postureAction("tent", 3))
            .addAction(postureAction("auto", 4))
            .build()
    }

    private fun postureAction(p: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, FoldService::class.java).setAction(ACTION_POSTURE).putExtra(EXTRA_POSTURE, p),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (posture == p) "• ${label(p)}" else label(p)
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, title, pi).build()
    }

    private fun rotateAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 5, Intent(this, FoldService::class.java).setAction(ACTION_ROTATE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Rotate ↻", pi).build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(screenOffReceiver) } catch (e: Throwable) {}
        sensorManager?.unregisterListener(this)
        inputProc?.destroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        worker.shutdown()
        log("Service stopped")
        super.onDestroy()
    }
}
