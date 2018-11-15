package com.geckour.findout.ui.view

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.geckour.findout.util.DragGestureDetector
import com.geckour.findout.util.PinchGestureDetector

class GesturableImageView : ImageView, View.OnTouchListener {

    constructor(context: Context, attrs: AttributeSet, defStyle: Int)
            : super(context, attrs, defStyle) {
        init(GESTURE_DRAGGABLE or GESTURE_SCALABLE)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(GESTURE_DRAGGABLE or GESTURE_SCALABLE)
    }

    constructor(context: Context) : super(context) {
        init(GESTURE_DRAGGABLE or GESTURE_SCALABLE)
    }

    constructor(context: Context, gestureFlag: Int) : super(context) {
        init(gestureFlag)
    }

    companion object {
        const val GESTURE_DRAGGABLE = 1
        const val GESTURE_SCALABLE = 2

        const val DEFAULT_LIMIT_SCALE_MAX = 10f
        const val DEFAULT_LIMIT_SCALE_MIN = 1f
    }

    private var limitScaleMax = DEFAULT_LIMIT_SCALE_MAX
    private var limitScaleMin = DEFAULT_LIMIT_SCALE_MIN

    var scaleFactor = 1f
        private set

    private var dragGestureDetector: DragGestureDetector? = null
    private var pinchGestureDetector: PinchGestureDetector? = null

    private var onTouchStateChanged: (MotionEvent) -> Unit = {}

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (dragGestureDetector != null) {
            dragGestureDetector?.onTouchEvent(event)
        }
        if (pinchGestureDetector != null) {
            pinchGestureDetector?.onTouchEvent(event)
        }
        onTouchStateChanged(event)
        return true
    }

    private fun init(gestureFlag: Int) {
        setOnTouchListener(this)
        if (gestureFlag and GESTURE_DRAGGABLE == GESTURE_DRAGGABLE) {
            dragGestureDetector = DragGestureDetector(DragListener())
        }
        if (gestureFlag and GESTURE_SCALABLE == GESTURE_SCALABLE) {
            pinchGestureDetector = PinchGestureDetector(ScaleListener())
        }
    }

    fun setOnTouchStateChangeListener(listener: (MotionEvent) -> Unit) {
        this.onTouchStateChanged = listener
    }

    private inner class DragListener : DragGestureDetector.DragGestureListener {
        @Synchronized
        override fun onDragGestureListener(dragGestureDetector: DragGestureDetector) {
            val dx = dragGestureDetector.deltaX * scaleFactor
            val dy = dragGestureDetector.deltaY * scaleFactor

            translationX += dx
            translationY += dy

            val bounds = RectF(Rect().apply { getDrawingRect(this) })
            matrix.mapRect(bounds)

            if (bounds.left > 0f) translationX -= bounds.left
            if (bounds.right < width) translationX += (width - bounds.right)
            if (bounds.top > 0f) translationY -= bounds.top
            if (bounds.bottom < height) translationY += (height - bounds.bottom)
        }
    }

    private inner class ScaleListener : PinchGestureDetector.PinchGestureListener {
        override fun onPinchGestureListener(dragGestureDetector: PinchGestureDetector) {
            val scale = dragGestureDetector.distance / dragGestureDetector.preDistance
            val tmpScale = scaleFactor * scale
            if (tmpScale in limitScaleMin..limitScaleMax) {
                scaleFactor = tmpScale
                scaleX = scaleFactor
                scaleY = scaleFactor
                return
            }
        }
    }
}