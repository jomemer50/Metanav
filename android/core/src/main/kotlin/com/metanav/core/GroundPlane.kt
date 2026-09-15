package com.metanav.core

import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/**
 * Pinhole ground-plane model: for a camera at a known height and pitch, every image row below
 * the horizon corresponds to a known distance along the ground. Anything appearing at that row
 * but closer than the ground must stick up from it, i.e. it is an obstacle.
 */
class GroundPlaneModel(val geometry: CameraGeometry, val rows: Int) {
    private val focalPx: Float
    private val pitchRad = Math.toRadians(geometry.pitchDegrees.toDouble())
    private val depthPerRow: FloatArray

    init {
        val halfFov = Math.toRadians(geometry.verticalFovDegrees.toDouble() / 2.0)
        focalPx = ((rows / 2.0) / tan(halfFov)).toFloat()
        depthPerRow = FloatArray(rows) { computeGroundDepth(it) }
    }

    /** Perpendicular distance (meters) to the ground point seen at [row], or +inf above the horizon. */
    fun groundDepth(row: Int): Float = depthPerRow[row.coerceIn(0, rows - 1)]

    /** Row index of the horizon (may be negative or beyond the frame). */
    val horizonRow: Int
        get() = (rows / 2.0 - focalPx * tan(pitchRad)).toInt()

    fun focalLengthPx(): Float = focalPx

    private fun computeGroundDepth(row: Int): Float {
        val v = (row + 0.5) - rows / 2.0
        val angleBelowHorizontal = pitchRad + atan(v / focalPx)
        if (angleBelowHorizontal <= 0.01) return Float.POSITIVE_INFINITY
        return (geometry.heightMeters / tan(angleBelowHorizontal)).toFloat()
    }
}

/**
 * Converts relative inverse depth into meters. Relative depth models are affine-invariant in
 * inverse-depth space: disp = a * (1/z) + b. We fit a and b every frame from the bottom of the
 * frame, which is almost always ground at a geometrically known distance, then smooth over time.
 */
class DepthScaleCalibrator {
    var a: Float = 0f
        private set
    var b: Float = 0f
        private set
    val isCalibrated: Boolean get() = a > 0f

    /** True when the latest frame produced a usable fit (ground visible, not blocked). */
    var lastFitAccepted: Boolean = false
        private set

    /**
     * Fit from the bottom rows of [depth]. Returns true when the fit was accepted. When the bottom
     * of the frame is not ground (an obstacle fills it) the fit is rejected and the previous
     * calibration is kept, which is exactly what makes that obstacle show up as an obstacle.
     */
    fun update(depth: DepthMap, ground: GroundPlaneModel): Boolean {
        val h = depth.height
        val w = depth.width
        val rowStart = (h * 0.80f).toInt()
        val rowEnd = (h * 0.985f).toInt().coerceAtMost(h - 1)
        val colStart = (w * 0.30f).toInt()
        val colEnd = (w * 0.70f).toInt().coerceAtLeast(colStart + 1)

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val scratch = FloatArray(colEnd - colStart)
        var row = rowStart
        while (row <= rowEnd) {
            val z = ground.groundDepth(row)
            if (z.isFinite()) {
                for (c in colStart until colEnd) scratch[c - colStart] = depth[c, row]
                xs.add(1f / z)
                ys.add(median(scratch))
            }
            row++
        }
        lastFitAccepted = false
        if (xs.size < 4) return false

        val n = xs.size
        var meanX = 0f
        var meanY = 0f
        for (i in 0 until n) { meanX += xs[i]; meanY += ys[i] }
        meanX /= n; meanY /= n
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx <= 1e-9f || syy <= 1e-12f) return false
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val r2 = (sxy * sxy) / (sxx * syy)

        // Ground shows a clear gradient toward the bottom of the frame. A flat profile means the
        // bottom of the frame is a wall, a table, a person: not ground. Keep the old calibration.
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (y in ys) { if (y < minY) minY = y; if (y > maxY) maxY = y }
        val spread = (maxY - minY) / max(kotlin.math.abs(meanY), 1e-6f)
        if (slope <= 0f || r2 < 0.6f || spread < 0.10f) return false

        if (!isCalibrated) {
            a = slope; b = intercept
        } else {
            a = 0.7f * a + 0.3f * slope
            b = 0.7f * b + 0.3f * intercept
        }
        lastFitAccepted = true
        return true
    }

    /** Meters for a relative disparity value; +inf when the value is at or beyond the horizon. */
    fun metricDepth(disparity: Float): Float {
        if (!isCalibrated) return Float.POSITIVE_INFINITY
        val d = disparity - b
        if (d <= 1e-6f) return Float.POSITIVE_INFINITY
        return a / d
    }

    fun reset() { a = 0f; b = 0f; lastFitAccepted = false }

    private fun median(values: FloatArray): Float {
        val copy = values.copyOf()
        copy.sort()
        val mid = copy.size / 2
        return if (copy.size % 2 == 0) (copy[mid - 1] + copy[mid]) / 2f else copy[mid]
    }
}
