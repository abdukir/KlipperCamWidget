package io.github.abdukir.klippercam.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.webrtc.VideoFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Frames {
    /** Decodes a JPEG, subsampling so the result is not much larger than [maxDim]. */
    fun decodeJpeg(data: ByteArray, length: Int = data.size, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, length, opts)
    }

    /** Converts a WebRTC I420 buffer (BT.601 limited range) to an ARGB bitmap. */
    fun i420ToBitmap(buf: VideoFrame.I420Buffer): Bitmap {
        val w = buf.width
        val h = buf.height
        val y = buf.dataY; val u = buf.dataU; val v = buf.dataV
        val sy = buf.strideY; val su = buf.strideU; val sv = buf.strideV
        val out = IntArray(w * h)
        var o = 0
        for (j in 0 until h) {
            val yRow = j * sy
            val uRow = (j shr 1) * su
            val vRow = (j shr 1) * sv
            for (i in 0 until w) {
                val c = (y.get(yRow + i).toInt() and 0xff) - 16
                val d = (u.get(uRow + (i shr 1)).toInt() and 0xff) - 128
                val e = (v.get(vRow + (i shr 1)).toInt() and 0xff) - 128
                val c298 = 298 * (if (c < 0) 0 else c)
                val r = (c298 + 409 * e + 128) shr 8
                val g = (c298 - 100 * d - 208 * e + 128) shr 8
                val b = (c298 + 516 * d + 128) shr 8
                out[o++] = -0x1000000 or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(x: Int) = if (x < 0) 0 else if (x > 255) 255 else x

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Applies camera rotation and flips (no-op when none are set). */
    fun orient(src: Bitmap, rotation: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        if (rotation % 360 == 0 && !flipH && !flipV) return src
        val m = Matrix().apply {
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Size that fits (w, h) inside a [maxDim] square, keeping even dimensions for YUV scalers. */
    fun fit(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val scale = min(1f, maxDim.toFloat() / max(w, h))
        val sw = ((w * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
        val sh = ((h * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
        return sw to sh
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Renders [src] into a new bitmap of exactly [w]x[h], applying the camera's rotation/flips and
     * either letterboxing or center-cropping.
     */
    fun renderTile(
        src: Bitmap, w: Int, h: Int,
        rotation: Int, flipH: Boolean, flipV: Boolean,
        crop: Boolean, config: Bitmap.Config = Bitmap.Config.RGB_565,
    ): Bitmap {
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), config)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val rot = ((rotation % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val srcW = if (swapped) src.height else src.width
        val srcH = if (swapped) src.width else src.height
        val scale = if (crop) max(w.toFloat() / srcW, h.toFloat() / srcH) else min(w.toFloat() / srcW, h.toFloat() / srcH)
        val m = Matrix()
        // Center the source on the origin, flip, rotate, scale, then move to the tile center.
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        m.postRotate(rot.toFloat())
        m.postScale(scale, scale)
        m.postTranslate(w / 2f, h / 2f)
        canvas.drawBitmap(src, m, paint)
        return out
    }

}
