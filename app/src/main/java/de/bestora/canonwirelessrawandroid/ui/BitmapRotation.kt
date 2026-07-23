package de.bestora.canonwirelessrawandroid.ui

import android.graphics.Bitmap
import android.graphics.Matrix

/** EXIF orientation (1..8) → clockwise degrees. Mirror flips (2/4/5/7) map to their nearest
 *  rotation; real cameras only emit 1/3/6/8. */
private fun degreesFor(orientation: Int): Int = when (orientation) {
    3, 4 -> 180
    5, 6 -> 90
    7, 8 -> 270
    else -> 0
}

/** Returns [bmp] rotated for the given EXIF [orientation]; the original if no rotation is needed.
 *  Canon's embedded thumbnail/preview JPEGs carry no EXIF, so we apply the CR3's orientation. */
fun rotateForOrientation(bmp: Bitmap, orientation: Int): Bitmap {
    val degrees = degreesFor(orientation)
    if (degrees == 0) return bmp
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
