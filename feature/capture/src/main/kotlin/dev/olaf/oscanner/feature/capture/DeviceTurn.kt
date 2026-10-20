package dev.olaf.oscanner.feature.capture

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Holds the screen upright in portrait while it's shown, as camera apps do: the controls under
 * the thumb stay put when the phone is turned. Restores what was there before on leaving.
 */
@Composable
internal fun LockPortrait() {
    val activity = LocalContext.current.findActivityOrNull()
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (activity != null && before != null) activity.requestedOrientation = before }
    }
}

/**
 * How far the phone is turned from upright, clockwise, in quarter turns: 0, 90, 180 or 270.
 * It only changes once the phone is well past halfway, so it doesn't flicker near 45°.
 */
@Composable
internal fun rememberDeviceTurn(): State<Int> {
    val context = LocalContext.current
    val turn = remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val current = turn.intValue
                val away = abs(((orientation - current + 540) % 360) - 180)
                if (away > HYSTERESIS) turn.intValue = ((orientation + 45) / 90 * 90) % 360
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return turn
}

/**
 * The rotation, in degrees for [androidx.compose.ui.graphics.graphicsLayer], that keeps
 * something upright for someone holding the phone turned by [turn]. Takes the short way round
 * (270 → 0 turns a quarter, not three quarters back).
 */
@Composable
internal fun rememberUprightRotation(turn: Int): Float {
    // The running angle, stepped by the shortest difference to the wanted one. A plain holder
    // rather than state: it only changes along with [turn].
    val running = remember { floatArrayOf(0f) }
    val target = remember(turn) {
        val delta = ((-turn - running[0]) % 360 + 540) % 360 - 180
        running[0] += delta
        running[0]
    }
    val angle by animateFloatAsState(target, tween(250), label = "upright")
    return angle
}

/** Clockwise degrees from the back camera's frames to a portrait screen: its mounting angle. */
internal fun Context.backCameraToPortrait(): Int = runCatching {
    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    manager.cameraIdList
        .map { manager.getCameraCharacteristics(it) }
        .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
}.getOrNull() ?: 90

private fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

/** Degrees past the halfway mark before the turn changes. */
private const val HYSTERESIS = 60
