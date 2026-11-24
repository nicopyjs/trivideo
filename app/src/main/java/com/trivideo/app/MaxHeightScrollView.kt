package com.trivideo.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * ScrollView que nunca supera [maxHeightPx] (0 = sin tope). Asi el panel de controles
 * deja siempre visible una franja de video arriba, donde se puede deslizar hacia abajo
 * para cerrarlo.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = heightMeasureSpec
        if (maxHeightPx > 0) {
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val capped = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeightPx
            else minOf(size, maxHeightPx)
            spec = View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
