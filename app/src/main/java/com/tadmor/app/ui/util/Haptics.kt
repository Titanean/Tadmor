package com.tadmor.app.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin haptics helper built on the platform [Vibrator]. Compose's
 * `LocalHapticFeedback` only exposes `LongPress` and `TextHandleMove` —
 * the latter is imperceptible or silent on many devices (which is why the
 * pull-to-refresh commit "buzz" wasn't felt), and neither gives any control
 * over the pitch/character of the pulse. Going direct to [Vibrator] lets us
 * pick a reliably-felt light tap and a distinctly lower-pitched selection
 * pulse.
 *
 * Requires the `VIBRATE` permission (declared in the manifest). All calls
 * are no-ops when the device has no vibrator.
 *
 * On API 31+ we use `VibrationEffect.Composition` primitives, which carry
 * genuine per-primitive frequency profiles — `PRIMITIVE_LOW_TICK` is an
 * actual low-frequency tick, not just a longer buzz. On API 26–30 (no
 * composition API) we fall back to `createOneShot`, where a longer pulse
 * at slightly reduced amplitude is the closest available approximation of
 * a "lower pitch" feel.
 */
object Haptics {

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Light, short tick — pull-to-refresh commit. */
    fun light(context: Context) = perform(context, Tone.LIGHT)

    /** Standard crisp selection tap — catalog disposition tabs. */
    fun select(context: Context) = perform(context, Tone.SELECT)

    /** Lower-pitched selection pulse — bottom navigation bar. */
    fun selectLow(context: Context) = perform(context, Tone.SELECT_LOW)

    private enum class Tone { LIGHT, SELECT, SELECT_LOW }

    private fun perform(context: Context, tone: Tone) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val primitive = when (tone) {
                Tone.LIGHT -> VibrationEffect.Composition.PRIMITIVE_TICK
                Tone.SELECT -> VibrationEffect.Composition.PRIMITIVE_CLICK
                Tone.SELECT_LOW -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            if (v.areAllPrimitivesSupported(primitive)) {
                val scale = when (tone) {
                    Tone.LIGHT -> 0.45f
                    Tone.SELECT -> 0.75f
                    Tone.SELECT_LOW -> 0.85f
                }
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(primitive, scale)
                        .compose(),
                )
                return
            }
            // Primitive unsupported on this device — fall through to oneShot.
        }

        // API 26–30, or a device without composition-primitive support.
        // Longer duration reads as a deeper/lower pulse; shorter as a
        // lighter tick. Amplitude is ignored on devices without amplitude
        // control, which is fine — the duration still differentiates them.
        val (durationMs, amplitude) = when (tone) {
            Tone.LIGHT -> 12L to 90
            Tone.SELECT -> 18L to 150
            Tone.SELECT_LOW -> 34L to 120
        }
        v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }
}
