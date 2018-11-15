package com.geckour.findout.util

import android.view.MotionEvent

class DragGestureDetector(dragGestureListener: DragGestureListener) {

    interface DragGestureListener {
        fun onDragGestureListener(dragGestureDetector: DragGestureDetector)
    }

    var deltaX = 0.0f
        private set
    var deltaY = 0.0f
        private set

    private var originIndex = 0

    private val pointMap = hashMapOf<Int, TouchPoint>()

    private var dragGestureListener: DragGestureListener? = dragGestureListener

    init {
        pointMap[0] = createPoint(0f, 0f)
        originIndex = 0
    }

    @Synchronized
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 3) {
            return false
        }
        val eventX = event.getX(originIndex)
        val eventY = event.getY(originIndex)
        val action = event.action and MotionEvent.ACTION_MASK
        val actionPointer = event.action and MotionEvent.ACTION_POINTER_INDEX_MASK
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                var downPoint = pointMap[0]
                if (downPoint != null) {
                    downPoint.setXY(eventX, eventY)
                } else {
                    downPoint = createPoint(eventX, eventY)
                    pointMap[0] = downPoint
                }
                originIndex = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val originalPoint = pointMap[originIndex]
                if (originalPoint != null) {
                    deltaX = eventX - originalPoint.x
                    deltaY = eventY - originalPoint.y
                    if (dragGestureListener != null) {
                        dragGestureListener?.onDragGestureListener(this)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val downId = actionPointer shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                val multiTouchX = event.getX(downId)
                val multiTouchY = event.getY(downId)
                val p = pointMap[downId]
                if (p != null) {
                    p.x = multiTouchX
                    p.y = multiTouchY
                } else {
                    pointMap[downId] = createPoint(multiTouchX, multiTouchY)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upId = actionPointer shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                if (originIndex == upId) {
                    pointMap.remove(upId)
                    var secondPoint: TouchPoint?
                    for (index in 0 until pointMap.size) {
                        if (originIndex != index) {
                            secondPoint = pointMap[index]
                            if (secondPoint != null) {
                                secondPoint.setXY(event.getX(index), event.getY(index))
                                originIndex = index
                                break
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    private fun createPoint(x: Float, y: Float) = TouchPoint(x, y)
    class TouchPoint(var x: Float, var y: Float) {
        fun setXY(x: Float, y: Float): TouchPoint {
            this.x = x
            this.y = y
            return this
        }
    }
}