package com.exmachina.ndicamerastreamer

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    var aspectRatio: Float = 16f / 9f  // width / height; update before requestLayout()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val measuredW: Int
        val measuredH: Int
        if (w.toFloat() / h > aspectRatio) {
            measuredW = (h * aspectRatio).toInt()
            measuredH = h
        } else {
            measuredW = w
            measuredH = (w / aspectRatio).toInt()
        }
        setMeasuredDimension(measuredW, measuredH)
    }
}
