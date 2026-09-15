package com.metanav.app.frames

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Converts the uncompressed frames the DAT SDK delivers (YUV 4:2:0 planar, i.e. I420, the
 * MediaCodec COLOR_FormatYUV420Planar the SDK's decoder is configured with) into ARGB bitmaps.
 * Plain Kotlin, no RenderScript: a 360x640 frame converts in a couple of milliseconds.
 */
object YuvConverter {
    private var scratch = IntArray(0)

    @Synchronized
    fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int, reuse: Bitmap?): Bitmap? {
        val ySize = width * height
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val cSize = chromaW * chromaH
        val src = buffer.duplicate()
        src.rewind()
        val available = src.remaining()
        val bytes: ByteArray
        val offset: Int
        if (src.hasArray()) {
            bytes = src.array(); offset = src.arrayOffset() + src.position()
        } else {
            bytes = ByteArray(available); src.get(bytes); offset = 0
        }

        val pixels = if (scratch.size == ySize) scratch else IntArray(ySize).also { scratch = it }
        when {
            available >= ySize + 2 * cSize -> convertPlanar(bytes, offset, width, height, chromaW, cSize, pixels)
            available >= ySize * 4 -> convertRgba(bytes, offset, ySize, pixels)
            else -> return null
        }
        val bitmap = if (reuse != null && reuse.width == width && reuse.height == height && reuse.isMutable) reuse
        else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun convertPlanar(bytes: ByteArray, offset: Int, width: Int, height: Int, chromaW: Int, cSize: Int, out: IntArray) {
        val ySize = width * height
        val uBase = offset + ySize
        val vBase = uBase + cSize
        var idx = 0
        for (y in 0 until height) {
            val cRow = (y shr 1) * chromaW
            val yRow = offset + y * width
            for (x in 0 until width) {
                val yy = (bytes[yRow + x].toInt() and 0xFF) - 16
                val cIdx = cRow + (x shr 1)
                val u = (bytes[uBase + cIdx].toInt() and 0xFF) - 128
                val v = (bytes[vBase + cIdx].toInt() and 0xFF) - 128
                val y1192 = 1192 * (if (yy < 0) 0 else yy)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun convertRgba(bytes: ByteArray, offset: Int, count: Int, out: IntArray) {
        var p = offset
        for (i in 0 until count) {
            val r = bytes[p].toInt() and 0xFF
            val g = bytes[p + 1].toInt() and 0xFF
            val b = bytes[p + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
        }
    }
}
