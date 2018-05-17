package com.geckour.findout.ui.view

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.view.TextureView
import android.view.View

class AutoFitTextureView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    var ratio: Size = Size(0, 0)
        set(value) {
            if (value.width < 0 || value.height < 0) {
                throw IllegalArgumentException("Size cannot be negative.")
            }

            field = value
            measure(width, height)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)

        if (ratio.width == 0 || ratio.height == 0) {
            setMeasuredDimension(width, height)
        } else {
            setMeasuredDimension(width, width * ratio.width / ratio.height)
        }
    }

}