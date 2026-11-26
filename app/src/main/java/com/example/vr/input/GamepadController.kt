package com.example.vr.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class GamepadInputState(
    val moveX: Float = 0f,
    val moveZ: Float = 0f,
    val isConnected: Boolean = false,
    val deviceName: String = "None",
    val lastEventTimeMs: Long = 0L
)

/**
 * Handles Bluetooth / USB gamepads:
 * - Left Stick (AXIS_X, AXIS_Y) / D-Pad -> smooth XZ locomotion
 * - Right Stick (AXIS_Z / AXIS_RZ or RX / RY) / L1 & R1 -> snap turn (+/- 30 deg)
 * - Button A / X -> primary click or floor teleport
 * - Button Y / Start -> recenter head yaw & room position
 */
class GamepadController(
    private val onSnapTurn: (Float) -> Unit,
    private val onPrimaryAction: () -> Unit,
    private val onRecenter: () -> Unit
) {
    private val _state = MutableStateFlow(GamepadInputState())
    val state: StateFlow<GamepadInputState> = _state.asStateFlow()

    private var rightStickArmedLeft = true
    private var rightStickArmedRight = true
    private var lastSnapRepeatMs = 0L

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val source = event.source
        val isJoystick = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) {
            return false
        }

        val devName = event.device?.name ?: "Bluetooth Gamepad"
        val rawLX = applyDeadzone(getCenteredAxis(event, MotionEvent.AXIS_X), STICK_DEADZONE)
        val rawLY = applyDeadzone(getCenteredAxis(event, MotionEvent.AXIS_Y), STICK_DEADZONE)

        val dpadX = applyDeadzone(getCenteredAxis(event, MotionEvent.AXIS_HAT_X), 0.3f)
        val dpadY = applyDeadzone(getCenteredAxis(event, MotionEvent.AXIS_HAT_Y), 0.3f)

        val moveX = if (abs(rawLX) > 0.01f) rawLX else dpadX
        // Android Y axis is -1 up, +1 down -> invert so pushing stick forward gives +1 forward
        val moveZ = if (abs(rawLY) > 0.01f) -rawLY else -dpadY

        // Right stick horizontal axis varies across Android gamepads (AXIS_Z on Xbox/Standard, AXIS_RX on some BLE pads)
        val axisZ = getCenteredAxis(event, MotionEvent.AXIS_Z)
        val axisRx = getCenteredAxis(event, MotionEvent.AXIS_RX)
        val rightX = if (abs(axisZ) > abs(axisRx)) axisZ else axisRx

        val nowMs = System.currentTimeMillis()
        if (rightX > SNAP_TURN_THRESHOLD) {
            if (rightStickArmedRight || (nowMs - lastSnapRepeatMs > SNAP_REPEAT_MS)) {
                onSnapTurn(-30f)
                rightStickArmedRight = false
                lastSnapRepeatMs = nowMs
            }
        } else {
            rightStickArmedRight = true
        }

        if (rightX < -SNAP_TURN_THRESHOLD) {
            if (rightStickArmedLeft || (nowMs - lastSnapRepeatMs > SNAP_REPEAT_MS)) {
                onSnapTurn(30f)
                rightStickArmedLeft = false
                lastSnapRepeatMs = nowMs
            }
        } else {
            rightStickArmedLeft = true
        }

        _state.value = GamepadInputState(
            moveX = moveX.coerceIn(-1f, 1f),
            moveZ = moveZ.coerceIn(-1f, 1f),
            isConnected = true,
            deviceName = devName,
            lastEventTimeMs = nowMs
        )
        return true
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val source = event?.source ?: 0
        val isGamepadKey = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(keyCode)

        if (!isGamepadKey) return false

        val devName = event?.device?.name ?: "Bluetooth Gamepad"
        val nowMs = System.currentTimeMillis()
        _state.value = _state.value.copy(
            isConnected = true,
            deviceName = devName,
            lastEventTimeMs = nowMs
        )

        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if ((event?.repeatCount ?: 0) == 0) {
                    onPrimaryAction()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if ((event?.repeatCount ?: 0) == 0) onSnapTurn(30f)
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if ((event?.repeatCount ?: 0) == 0) onSnapTurn(-30f)
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if ((event?.repeatCount ?: 0) == 0) onRecenter()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                _state.value = _state.value.copy(moveZ = 1f)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                _state.value = _state.value.copy(moveZ = -1f)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                _state.value = _state.value.copy(moveX = -1f)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                _state.value = _state.value.copy(moveX = 1f)
                true
            }
            else -> false
        }
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                _state.value = _state.value.copy(moveZ = 0f)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                _state.value = _state.value.copy(moveX = 0f)
                true
            }
            else -> false
        }
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val device = event.device
        val range = device?.getMotionRange(axis, event.source)
        val value = event.getAxisValue(axis)
        return if (range != null && abs(value) <= range.flat) 0f else value
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        return if (abs(value) < deadzone) 0f else value
    }

    companion object {
        private const val STICK_DEADZONE = 0.14f
        private const val SNAP_TURN_THRESHOLD = 0.65f
        private const val SNAP_REPEAT_MS = 340L
    }
}
