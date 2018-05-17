package com.geckour.findout.util

import android.graphics.Bitmap
import android.graphics.Matrix
import timber.log.Timber

fun Bitmap.centerFit(width: Int, height: Int, rotation: Float = 0.0f): Bitmap? {
    val isDstLandscape = width > height
    if (isDstLandscape) {
        if (this.height * width / this.width < height) return null
    } else {
        if (this.width * height / this.height < width) return null
    }

    val matrix = Matrix().apply {
        if (rotation != 0.0f) postRotate(rotation)
    }

    return try {
        if (isDstLandscape) {
            Bitmap.createScaledBitmap(this, width, this.height * width / this.width, false)
                    .let { Bitmap.createBitmap(it, 0, it.height / 2 - height / 2, height, width, matrix, false) }
        } else {
            Bitmap.createScaledBitmap(this, this.width * height / this.height, height, false)
                    .let { Bitmap.createBitmap(it, it.width / 2 - width / 2, 0, height, width, matrix, false) }
        }
    } catch (t: Throwable) {
        Timber.e(t)
        null
    }
}