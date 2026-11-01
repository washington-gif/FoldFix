package io.github.washingtongif.foldfix

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("foldfix", Context.MODE_PRIVATE)

    /** "hinge" = use the hinge-angle sensor, "input" = use a kernel input event (hall/lid switch). */
    var mode: String
        get() = sp.getString("mode", "sensor")!!
        set(v) = sp.edit().putString("mode", v).apply()

    /** Hinge mode: treat as closed below this angle, open above [openAbove] (gap = hysteresis). */
    var closeBelow: Float
        get() = sp.getFloat("closeBelow", 8f)
        set(v) = sp.edit().putFloat("closeBelow", v).apply()

    var openAbove: Float
        get() = sp.getFloat("openAbove", 25f)
        set(v) = sp.edit().putFloat("openAbove", v).apply()

    /** Input mode: text that identifies the event line, e.g. "SW_LID" or "/dev/input/event5: EV_SW". */
    var inputPattern: String
        get() = sp.getString("inputPattern", "SW_LID")!!
        set(v) = sp.edit().putString("inputPattern", v).apply()

    /** Input mode: by default a non-zero value means closed; invert flips that. */
    var inputInvert: Boolean
        get() = sp.getBoolean("inputInvert", false)
        set(v) = sp.edit().putBoolean("inputInvert", v).apply()

    /** Device-state id to force when closed; -1 = look up "CLOSED" automatically. */
    var closedStateId: Int
        get() = sp.getInt("closedStateId", -1)
        set(v) = sp.edit().putInt("closedStateId", v).apply()

    /** When open: false = hand control back to Android, true = force the OPENED state too. */
    var forceOpen: Boolean
        get() = sp.getBoolean("forceOpen", false)
        set(v) = sp.edit().putBoolean("forceOpen", v).apply()

    /** Sensor mode: part of the sensor's name, e.g. "hall". */
    var sensorName: String
        get() = sp.getString("sensorName", "hall")!!
        set(v) = sp.edit().putString("sensorName", v).apply()

    /** Sensor mode: which value in the reading to use (usually 0). */
    var sensorIndex: Int
        get() = sp.getInt("sensorIndex", 0)
        set(v) = sp.edit().putInt("sensorIndex", v).apply()

    var sensorThreshold: Float
        get() = sp.getFloat("sensorThreshold", 0.5f)
        set(v) = sp.edit().putFloat("sensorThreshold", v).apply()

    /** Sensor mode: true = closed when value is ABOVE the threshold, false = BELOW. */
    var sensorClosedAbove: Boolean
        get() = sp.getBoolean("sensorClosedAbove", true)
        set(v) = sp.edit().putBoolean("sensorClosedAbove", v).apply()

    /** Tent: screen rotation to lock to (0-3, quarter turns). */
    var tentRotation: Int
        get() = sp.getInt("tentRotation", 2)
        set(v) = sp.edit().putInt("tentRotation", v).apply()

    /** Rotation settings saved before Tent locked them ("" = nothing saved). */
    var savedRotation: String
        get() = sp.getString("savedRotation", "")!!
        set(v) = sp.edit().putString("savedRotation", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()
}
