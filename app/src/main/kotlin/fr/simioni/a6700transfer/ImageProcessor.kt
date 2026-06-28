package fr.simioni.a6700transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

object ImageProcessor {

    private const val MAX_DIMENSION = 4096
    private const val JPEG_QUALITY = 87

    private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    fun getExifDate(file: File): Long {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return 0L
            EXIF_DATE_FORMAT.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun process(input: File, output: File) {
        val opts = BitmapFactory.Options().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val original = BitmapFactory.decodeFile(input.absolutePath, opts)
            ?: throw IOException("Impossible de decoder ${input.name}")

        val (newW, newH) = scaledDimensions(original.width, original.height)
        val scaled = if (newW == original.width && newH == original.height) original
        else Bitmap.createScaledBitmap(original, newW, newH, true)
            .also { if (it !== original) original.recycle() }

        output.parentFile?.mkdirs()
        FileOutputStream(output).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== original) scaled.recycle()

        copyExif(input.absolutePath, output.absolutePath)
    }

    private fun scaledDimensions(w: Int, h: Int): Pair<Int, Int> {
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return Pair(w, h)
        return if (w >= h) Pair(MAX_DIMENSION, (h.toLong() * MAX_DIMENSION / w).toInt())
        else Pair((w.toLong() * MAX_DIMENSION / h).toInt(), MAX_DIMENSION)
    }

    private fun copyExif(src: String, dst: String) {
        val srcExif = ExifInterface(src)
        val dstExif = ExifInterface(dst)
        EXIF_TAGS.forEach { tag -> srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) } }
        dstExif.saveAttributes()
    }
}
