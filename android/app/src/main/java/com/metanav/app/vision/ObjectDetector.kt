package com.metanav.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.metanav.core.Detection
import com.metanav.core.NormRect
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TaskObjectDetector
import java.io.Closeable

/**
 * COCO object detection with EfficientDet-Lite0 via the TFLite Task Library. Used only to put a
 * name on obstacles ("Person", "Chair") and to sanity-check distances; the depth path decides
 * what counts as an obstacle.
 */
class ObjectDetector(context: Context, assetName: String = "efficientdet_lite0.tflite") : Closeable {
    private val detector: TaskObjectDetector

    init {
        val options = TaskObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(8)
            .setScoreThreshold(0.35f)
            .build()
        detector = TaskObjectDetector.createFromFileAndOptions(context, assetName, options)
        Log.i(TAG, "Object detector ready")
    }

    fun detect(frame: Bitmap): List<Detection> {
        val image = TensorImage.fromBitmap(frame)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return detector.detect(image).mapNotNull { det ->
            val cat = det.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            val label = cat.label.ifBlank { cat.displayName }
            if (label.isBlank() || label == "???") return@mapNotNull null
            val b = det.boundingBox
            Detection(
                label = label,
                confidence = cat.score,
                box = NormRect(
                    x = (b.left / w).coerceIn(0f, 1f),
                    y = (b.top / h).coerceIn(0f, 1f),
                    width = (b.width() / w).coerceIn(0f, 1f),
                    height = (b.height() / h).coerceIn(0f, 1f),
                ),
            )
        }
    }

    override fun close() = detector.close()

    companion object {
        private const val TAG = "Metanav:Detector"

        fun isAvailable(context: Context, assetName: String = "efficientdet_lite0.tflite"): Boolean =
            runCatching { context.assets.openFd(assetName).close(); true }.getOrDefault(false)
    }
}
