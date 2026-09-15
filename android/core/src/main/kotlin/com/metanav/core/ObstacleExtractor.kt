package com.metanav.core

import kotlin.math.max
import kotlin.math.min

/** A vertical slab of "stuff closer than the ground" found in one frame. */
data class Blob(
    /** Normalized horizontal extent. */
    val left: Float,
    val right: Float,
    val distanceMeters: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val width: Float get() = right - left
}

data class Extraction(
    val blobs: List<Blob>,
    /** Nearest obstacle distance per column (meters, +inf when free). */
    val columnDistances: FloatArray,
    /** How far ahead the left / right thirds are free (meters, capped). */
    val leftFreeMeters: Float,
    val rightFreeMeters: Float,
)

/**
 * Turns a calibrated depth map into obstacle blobs. Per column we look for pixels that are both
 * within the alert distance and clearly closer than the ground would be at that row; enough such
 * pixels make the column "blocked" at the 20th-percentile distance. Adjacent blocked columns form
 * blobs.
 */
class ObstacleExtractor(private val config: ReasonerConfig) {

    fun extract(depth: DepthMap, calibrator: DepthScaleCalibrator, ground: GroundPlaneModel): Extraction {
        val w = depth.width
        val h = depth.height
        val columnDistances = FloatArray(w) { Float.POSITIVE_INFINITY }
        if (!calibrator.isCalibrated) {
            return Extraction(emptyList(), columnDistances, config.alertDistanceMeters, config.alertDistanceMeters)
        }
        val rowStart = (h * config.scanTop).toInt().coerceIn(0, h - 1)
        val rowEnd = (h * config.scanBottom).toInt().coerceIn(rowStart + 1, h)
        val rowsScanned = rowEnd - rowStart
        val minCount = max(2, (rowsScanned * config.minColumnFillRatio).toInt())
        val alert = config.alertDistanceMeters

        // Precompute per-row ground threshold in meters.
        val groundThreshold = FloatArray(h)
        for (y in rowStart until rowEnd) {
            val g = ground.groundDepth(y)
            groundThreshold[y] = if (g.isFinite()) g * config.groundMarginRatio else Float.POSITIVE_INFINITY
        }

        val scratch = FloatArray(rowsScanned)
        for (x in 0 until w) {
            var count = 0
            for (y in rowStart until rowEnd) {
                val z = calibrator.metricDepth(depth[x, y])
                if (z < alert && z < groundThreshold[y]) {
                    scratch[count++] = z
                }
            }
            if (count >= minCount) {
                columnDistances[x] = percentile(scratch, count, 0.20f)
            }
        }
        smoothColumns(columnDistances)

        val blobs = findBlobs(columnDistances, w)
        val leftFree = freeDistance(columnDistances, (w * 0.05f).toInt(), (w * 0.35f).toInt())
        val rightFree = freeDistance(columnDistances, (w * 0.65f).toInt(), (w * 0.95f).toInt())
        return Extraction(blobs, columnDistances, leftFree, rightFree)
    }

    private fun findBlobs(columns: FloatArray, w: Int): List<Blob> {
        val alert = config.alertDistanceMeters
        val minWidth = max(2, (w * config.minBlobWidthRatio).toInt())
        val blobs = ArrayList<Blob>()
        var runStart = -1
        var gap = 0
        var x = 0
        while (x <= w) {
            val blocked = x < w && columns[x] < alert
            if (blocked) {
                if (runStart < 0) runStart = x
                gap = 0
            } else if (runStart >= 0) {
                gap++
                if (gap > 2 || x == w) {
                    val runEnd = x - gap // exclusive
                    if (runEnd - runStart >= minWidth) {
                        blobs.add(makeBlob(columns, runStart, runEnd, w))
                    }
                    runStart = -1
                    gap = 0
                }
            }
            x++
        }
        return blobs
    }

    private fun makeBlob(columns: FloatArray, start: Int, end: Int, w: Int): Blob {
        val values = FloatArray(end - start)
        var n = 0
        for (x in start until end) if (columns[x].isFinite()) values[n++] = columns[x]
        val distance = if (n > 0) percentile(values, n, 0.25f) else config.alertDistanceMeters
        return Blob(start.toFloat() / w, end.toFloat() / w, distance)
    }

    private fun freeDistance(columns: FloatArray, start: Int, end: Int): Float {
        val cap = config.alertDistanceMeters + 1f
        var sum = 0f
        var n = 0
        for (x in start until min(end, columns.size)) {
            sum += min(columns[x], cap)
            n++
        }
        return if (n == 0) cap else sum / n
    }

    private fun smoothColumns(columns: FloatArray) {
        if (columns.size < 3) return
        val copy = columns.copyOf()
        for (x in 1 until columns.size - 1) {
            columns[x] = median3(copy[x - 1], copy[x], copy[x + 1])
        }
    }

    private fun median3(a: Float, b: Float, c: Float): Float = max(min(a, b), min(max(a, b), c))

    private fun percentile(values: FloatArray, count: Int, p: Float): Float {
        val copy = values.copyOf(count)
        copy.sort()
        val idx = (p * (count - 1)).toInt().coerceIn(0, count - 1)
        return copy[idx]
    }
}
