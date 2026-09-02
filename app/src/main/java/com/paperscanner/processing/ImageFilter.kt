package com.paperscanner.processing

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.paperscanner.data.ImageFilterMode
import java.io.File
import java.io.FileOutputStream

object ImageFilter {

    fun applyFilter(
        sourcePath: String,
        destPath: String,
        mode: ImageFilterMode,
        quality: Int = 85,
        rotation: Int = 0
    ): Boolean {
        return try {
            val bitmap = loadBitmap(sourcePath) ?: return false
            val rotated = if (rotation != 0) rotateBitmap(bitmap, rotation.toFloat()) else bitmap
            val filtered = when (mode) {
                ImageFilterMode.COLOR -> rotated
                ImageFilterMode.GRAYSCALE -> toGrayscale(rotated)
                ImageFilterMode.BLACK_WHITE -> toBlackWhite(rotated)
            }
            saveBitmap(filtered, destPath, quality)
            if (filtered != rotated) filtered.recycle()
            if (rotated != bitmap) rotated.recycle()
            bitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadBitmap(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
    }

    fun toGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    fun toBlackWhite(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        // Apply adaptive thresholding for better B&W result
        val blockSize = 15
        val c = 10
        val result = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                val halfBlock = blockSize / 2

                for (dy in -halfBlock..halfBlock) {
                    for (dx in -halfBlock..halfBlock) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            val pixel = pixels[ny * width + nx]
                            val r = Color.red(pixel)
                            val g = Color.green(pixel)
                            val b = Color.blue(pixel)
                            sum += (r + g + b) / 3
                            count++
                        }
                    }
                }

                val threshold = sum / count - c
                val currentPixel = pixels[y * width + x]
                val currentGray = (Color.red(currentPixel) + Color.green(currentPixel) + Color.blue(currentPixel)) / 3
                result[y * width + x] = if (currentGray < threshold) Color.BLACK else Color.WHITE
            }
        }

        val bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bw.setPixels(result, 0, width, 0, 0, width, height)
        gray.recycle()
        return bw
    }

    fun enhance(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Auto contrast enhancement using histogram stretching
        var minVal = 255
        var maxVal = 0

        for (pixel in pixels) {
            val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (gray < minVal) minVal = gray
            if (gray > maxVal) maxVal = gray
        }

        val range = (maxVal - minVal).coerceAtLeast(1)
        val result = IntArray(width * height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((Color.red(pixel) - minVal) * 255 / range).coerceIn(0, 255)
            val g = ((Color.green(pixel) - minVal) * 255 / range).coerceIn(0, 255)
            val b = ((Color.blue(pixel) - minVal) * 255 / range).coerceIn(0, 255)
            result[i] = Color.rgb(r, g, b)
        }

        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(result, 0, width, 0, 0, width, height)
        return enhanced
    }

    fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun cropBitmap(src: Bitmap, cropRect: RectF): Bitmap {
        val left = cropRect.left.toInt().coerceIn(0, src.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, src.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, src.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    fun saveBitmap(bitmap: Bitmap, path: String, quality: Int): Boolean {
        return try {
            FileOutputStream(path).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getRotationFromExif(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
