package com.metanav.core

import kotlin.math.atan
import kotlin.math.tan

/**
 * Renders synthetic relative-depth maps from the same pinhole geometry the reasoner assumes:
 * flat ground plus optional upright boxes. Disparity is an arbitrary affine map of 1/z so the
 * tests exercise the calibration path exactly like a real depth model would.
 */
class SyntheticScene(
    val width: Int = 64,
    val height: Int = 96,
    val geometry: CameraGeometry = CameraGeometry(),
    private val scaleA: Float = 1000f,
    private val offsetB: Float = 5f,
) {
    data class Box(val left: Float, val right: Float, val distance: Float, val heightMeters: Float = 1.0f)

    private val ground = GroundPlaneModel(geometry, height)
    private val focal = ground.focalLengthPx()
    private val pitchRad = Math.toRadians(geometry.pitchDegrees.toDouble())

    fun render(boxes: List<Box> = emptyList(), noise: Float = 0f, seed: Int = 1): DepthMap {
        val values = FloatArray(width * height)
        val rnd = java.util.Random(seed.toLong())
        for (y in 0 until height) {
            for (x in 0 until width) {
                var z = ground.groundDepth(y)
                if (!z.isFinite()) z = 60f // "sky" / far wall
                val nx = (x + 0.5f) / width
                for (box in boxes) {
                    if (nx < box.left || nx >= box.right) continue
                    if (y < topRow(box) || box.distance > ground.groundDepth(y)) continue
                    if (box.distance < z) z = box.distance
                }
                var disp = scaleA / z + offsetB
                if (noise > 0f) disp += (rnd.nextFloat() * 2f - 1f) * noise * disp
                values[y * width + x] = disp
            }
        }
        return DepthMap(width, height, values)
    }

    /** First image row (from the top) covered by an upright box at its distance. */
    private fun topRow(box: Box): Int {
        val dh = geometry.heightMeters - box.heightMeters
        val angleBelowHorizontal = atan(dh / box.distance)
        val v = focal * tan(angleBelowHorizontal - pitchRad)
        return (height / 2.0 + v).toInt().coerceIn(0, height - 1)
    }
}
