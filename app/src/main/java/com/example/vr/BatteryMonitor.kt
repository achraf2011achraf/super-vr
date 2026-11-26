package com.example.vr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryTelemetry(
    val temperatureCelsius: Float = 32.0f,
    val levelPercent: Int = 85,
    val isCharging: Boolean = false
) {
    val isHighTempWarning: Boolean
        get() = temperatureCelsius > 42.0f

    val isLowBatteryWarning: Boolean
        get() = levelPercent < 15

    val hasWarning: Boolean
        get() = isHighTempWarning || isLowBatteryWarning
}

class BatteryMonitor(private val context: Context) {

    private val _telemetry = MutableStateFlow(BatteryTelemetry())
    val telemetry: StateFlow<BatteryTelemetry> = _telemetry.asStateFlow()

    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 85)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            _telemetry.value = BatteryTelemetry(
                temperatureCelsius = tempTenths / 10f,
                levelPercent = pct,
                isCharging = charging
            )
        }
    }

    fun start() {
        if (isRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        isRegistered = true
        sticky?.let { receiver.onReceive(context, it) }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        isRegistered = false
    }
}
