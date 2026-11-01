package io.github.washingtongif.foldfix

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var angleView: TextView
    private lateinit var output: TextView
    private lateinit var serviceLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            serviceLog.text = FoldService.logText(this@MainActivity).ifEmpty { "(no service events yet)" }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }
        fun header(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
        })
        fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        })
        fun number(label: String, value: String, signed: Boolean = false): EditText {
            root.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            }.also { root.addView(it) }
        }

        // --- Status ---
        header("Status")
        status = TextView(this).also { root.addView(it) }
        angleView = TextView(this).apply { textSize = 22f; text = "Hinge angle: no reading yet" }.also { root.addView(it) }
        button("Grant Shizuku permission") {
            try {
                if (Shizuku.pingBinder()) Shizuku.requestPermission(1)
                else toast("Shizuku isn't running — start it in the Shizuku app first.")
            } catch (e: Throwable) { toast("Shizuku error: $e") }
        }

        // --- Diagnostics ---
        header("1 · Diagnose")
        root.addView(TextView(this).apply {
            text = "Find a signal that is always right. Watch the hinge angle above while you fold, " +
                "or record input events and fold/unfold during the 10 seconds."
        })
        button("Record ALL sensors for 10 s (fold & unfold now)") { recordSensors() }
        button("Show device states") {
            sh("echo '-- defined states --'; cmd device_state print-states; " +
                "echo; echo '-- current --'; cmd device_state print-state")
        }
        button("Record input events for 10 s (fold & unfold now)") {
            output.text = "Recording… fold and unfold the phone."
            sh("timeout 10 getevent -lq | grep -v -E 'EV_SYN|EV_ABS|EV_REL'")
        }
        button("List input devices") { sh("getevent -pl | grep -E 'add device|name:'") }
        button("Full device_state dump") { sh("dumpsys device_state") }
        root.addView(TextView(this).apply { text = "Run a shell command (as ADB shell):" })
        val cmdBox = EditText(this).apply {
            hint = "e.g. dumpsys sensorservice"; typeface = Typeface.MONOSPACE; textSize = 13f
        }.also { root.addView(it) }
        button("Run command") { cmdBox.text.toString().takeIf { it.isNotBlank() }?.let { sh(it) } }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        }.also { root.addView(it) }

        // --- Settings ---
        header("2 · Configure")
        val group = RadioGroup(this)
        val rbHinge = RadioButton(this).apply { text = "Hinge-angle sensor"; id = View.generateViewId() }
        val rbInput = RadioButton(this).apply { text = "Input event (hall / lid switch)"; id = View.generateViewId() }
        val rbSensor = RadioButton(this).apply { text = "Any sensor by name (e.g. hall)"; id = View.generateViewId() }
        group.addView(rbHinge); group.addView(rbInput); group.addView(rbSensor)
        group.check(when (prefs.mode) { "input" -> rbInput.id; "sensor" -> rbSensor.id; else -> rbHinge.id })
        root.addView(group)

        val closeBelow = number("Hinge: closed below (°)", prefs.closeBelow.toString())
        val openAbove = number("Hinge: open above (°)", prefs.openAbove.toString())
        root.addView(TextView(this).apply {
            text = "Input: text identifying the event line (e.g. SW_LID, or '/dev/input/event4: EV_SW')"
        })
        val pattern = EditText(this).apply { setText(prefs.inputPattern) }.also { root.addView(it) }
        val invert = CheckBox(this).apply {
            text = "Input: value 0 means closed (invert)"; isChecked = prefs.inputInvert
        }.also { root.addView(it) }
        root.addView(TextView(this).apply { text = "Sensor: part of the sensor's name" })
        val sensorName = EditText(this).apply { setText(prefs.sensorName); hint = "e.g. hall" }.also { root.addView(it) }
        val sensorIndex = number("Sensor: value index (usually 0)", prefs.sensorIndex.toString())
        val sensorThreshold = number("Sensor: threshold", prefs.sensorThreshold.toString(), signed = true)
        val sensorAbove = CheckBox(this).apply {
            text = "Sensor: closed when value is ABOVE threshold (untick = below)"; isChecked = prefs.sensorClosedAbove
        }.also { root.addView(it) }
        val closedId = number("CLOSED state id (-1 = auto)", prefs.closedStateId.toString(), signed = true)
        val forceOpen = CheckBox(this).apply {
            text = "Also force OPENED when unfolded (otherwise let Android decide)"; isChecked = prefs.forceOpen
        }.also { root.addView(it) }
        val onBoot = CheckBox(this).apply {
            text = "Start after reboot (waits for Shizuku)"; isChecked = prefs.startOnBoot
        }.also { root.addView(it) }

        fun save() {
            prefs.mode = when (group.checkedRadioButtonId) {
                rbInput.id -> "input"; rbSensor.id -> "sensor"; else -> "hinge"
            }
            prefs.sensorName = sensorName.text.toString()
            sensorIndex.text.toString().toFloatOrNull()?.let { prefs.sensorIndex = it.toInt() }
            sensorThreshold.text.toString().toFloatOrNull()?.let { prefs.sensorThreshold = it }
            prefs.sensorClosedAbove = sensorAbove.isChecked
            closeBelow.text.toString().toFloatOrNull()?.let { prefs.closeBelow = it }
            openAbove.text.toString().toFloatOrNull()?.let { prefs.openAbove = it }
            prefs.inputPattern = pattern.text.toString()
            prefs.inputInvert = invert.isChecked
            closedId.text.toString().toFloatOrNull()?.let { prefs.closedStateId = it.toInt() }
            prefs.forceOpen = forceOpen.isChecked
            prefs.startOnBoot = onBoot.isChecked
        }

        // --- Run ---
        header("3 · Run")
        button("Save & start correction") {
            save()
            if (prefs.mode == "hinge" && prefs.closeBelow >= prefs.openAbove) { toast("'Closed below' must be less than 'open above'."); return@button }
            if (FoldService.running) FoldService.stop(this)
            handler.postDelayed({ FoldService.start(this) }, 600)
            toast("Started")
        }
        button("Stop & release override") { FoldService.stop(this) }
        button("Emergency: release override now") { sh("cmd device_state state reset; cmd device_state print-state") }

        header("Service log")
        serviceLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        }.also { root.addView(it) }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Listens to every sensor for 10 s and reports which ones changed. */
    private fun recordSensors() {
        val sm = getSystemService(SensorManager::class.java)
        class Stat { var n = 0; var min = FloatArray(3) { Float.MAX_VALUE }; var max = FloatArray(3) { -Float.MAX_VALUE }; var last = FloatArray(0) }
        val stats = LinkedHashMap<Sensor, Stat>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val s = stats.getOrPut(e.sensor) { Stat() }
                s.n++; s.last = e.values.copyOf()
                for (i in 0 until minOf(3, e.values.size)) {
                    s.min[i] = minOf(s.min[i], e.values[i]); s.max[i] = maxOf(s.max[i], e.values[i])
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        all.forEach { try { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } catch (e: Throwable) {} }
        output.text = "Recording ${all.size} sensors for 10 s… fold fully, wait, unfold, repeat."
        handler.postDelayed({
            sm.unregisterListener(listener)
            val key = Regex("hall|hinge|fold|lid|posture|angle|magnet|cover|device.?state", RegexOption.IGNORE_CASE)
            fun fmt(x: Float) = "%.2f".format(x)
            val sb = StringBuilder()
            sb.append("== Fold-related sensors ==\n")
            all.filter { key.containsMatchIn(it.name) || key.containsMatchIn(it.stringType) }.forEach { sn ->
                val s = stats[sn]
                sb.append("${sn.name}  [${sn.stringType}]${if (sn.isWakeUpSensor) " wake" else ""}\n")
                if (s == null) sb.append("   SILENT (no readings)\n")
                else sb.append("   n=${s.n}  v0 ${fmt(s.min[0])}..${fmt(s.max[0])}  last=${s.last.take(4).joinToString { fmt(it) }}\n")
            }
            sb.append("\n== Other sensors that reported ==\n")
            stats.filterKeys { !key.containsMatchIn(it.name) && !key.containsMatchIn(it.stringType) }
                .entries.sortedBy { it.key.name }.forEach { (sn, s) ->
                    sb.append("${sn.name}: n=${s.n} v0 ${fmt(s.min[0])}..${fmt(s.max[0])}\n")
                }
            val silent = all.filter { it !in stats }.map { it.name }
            sb.append("\n== Silent (${silent.size}) ==\n").append(silent.joinToString("\n"))
            output.text = sb.toString()
        }, 10_000)
    }

    private fun refreshStatus() {
        val shizuku = try {
            when {
                !Shizuku.pingBinder() -> "not running"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "running, permission needed"
                else -> "ready"
            }
        } catch (e: Throwable) { "unavailable" }
        status.text = "Shizuku: $shizuku\nCorrection service: ${if (FoldService.running) "running" else "stopped"}"
    }

    private fun sh(cmd: String) {
        if (!Shell.ready()) { toast("Shizuku isn't ready yet."); return }
        thread {
            val r = try { Shell.run(cmd) } catch (e: Throwable) { "Error: $e" }
            runOnUiThread { output.text = r.ifEmpty { "(no output)" } }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensorManager = sm
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run { angleView.text = "Hinge angle: no sensor exposed" }
        handler.post(tick)
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        angleView.text = "Hinge angle: ${"%.1f".format(event.values[0])}°"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
