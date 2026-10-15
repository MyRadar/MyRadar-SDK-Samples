package com.acmeaom.myradar.sdkdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap

/** Draws the aircraft marker: a white airplane pointing north, so the SDK can rotate it by heading. */
object PlaneIcon {

    /** Material Symbols "flight", in a 24×24 viewport, nose up. */
    private const val FLIGHT_PATH =
        "M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z"

    fun create(context: Context, sizeDp: Float = 34f, glyphDp: Float = 28f): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        // createBitmap uses the display density, so the SDK keeps the icon at sizeDp on screen.
        val bitmap = createBitmap(size, size)
        val path = PathParser.createPathFromPathData(FLIGHT_PATH)
        val scale = glyphDp * density / 24f
        val offset = (size - 24f * scale) / 2
        path.transform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(offset, offset)
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }
}
