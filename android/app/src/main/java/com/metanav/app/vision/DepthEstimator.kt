package com.metanav.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.metanav.core.DepthMap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Monocular relative depth with MiDaS v2.1 small (TFLite, 256x256 RGB in [0,1] -> 256x256
 * inverse depth). Runs on CPU with XNNPACK; ~40-90 ms on a recent phone.
 */
class DepthEstimator(context: Context, assetName: String = "midas.tflite") : Closeable {
    private val interpreter: Interpreter
    private val inputSize: Int
    private val inputBuffer: ByteBuffer
    private val output: Array<Array<Array<FloatArray>>>
    private val pixels: IntArray
    private var scaled: Bitmap? = null

    init {
        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadModel(context, assetName), options)
        val shape = interpreter.getInputTensor(0).shape() // [1, H, W, 3]
        inputSize = shape[1]
        require(shape[2] == inputSize) { "Expected a square depth model input, got ${shape.toList()}" }
        inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val outShape = interpreter.getOutputTensor(0).shape() // [1, H, W, 1]
        output = Array(1) { Array(outShape[1]) { Array(outShape[2]) { FloatArray(outShape.getOrElse(3) { 1 }) } } }
        pixels = IntArray(inputSize * inputSize)
        Log.i(TAG, "Depth model ready: input ${shape.toList()} output ${outShape.toList()}")
    }

    /** Whole-frame resize (no crop) so rows of the depth map line up with rows of the frame. */
    fun estimate(frame: Bitmap): DepthMap {
        val bmp = Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)
        val h = output[0].size
        val w = output[0][0].size
        val values = FloatArray(w * h)
        for (y in 0 until h) {
            val row = output[0][y]
            for (x in 0 until w) values[y * w + x] = row[x][0]
        }
        if (bmp !== frame) bmp.recycle()
        return DepthMap(w, h, values)
    }

    override fun close() {
        interpreter.close()
        scaled?.recycle()
    }

    companion object {
        private const val TAG = "Metanav:Depth"

        fun loadModel(context: Context, assetName: String): ByteBuffer {
            context.assets.openFd(assetName).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        fun isAvailable(context: Context, assetName: String = "midas.tflite"): Boolean =
            runCatching { context.assets.openFd(assetName).close(); true }.getOrDefault(false)
    }
}
