package com.chin.stockanalysis.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 简易 FlowLayout — 子 View 自动换行排列
 * 用于板块标签 chips 等场景
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        var currentLineWidth = 0
        var totalHeight = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val lp = child.layoutParams as LayoutParams
            val childWidthWithMargins = childWidth + lp.leftMargin + lp.rightMargin
            val childHeightWithMargins = childHeight + lp.topMargin + lp.bottomMargin

            if (currentLineWidth + childWidthWithMargins > maxWidth && currentLineWidth > 0) {
                // 换行
                totalHeight += lineHeight
                currentLineWidth = childWidthWithMargins
                lineHeight = childHeightWithMargins
            } else {
                currentLineWidth += childWidthWithMargins
                lineHeight = maxOf(lineHeight, childHeightWithMargins)
            }
        }
        totalHeight += lineHeight
        totalHeight += paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(maxWidth, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l
        var currentX = paddingLeft
        var currentY = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val lp = child.layoutParams as LayoutParams
            val childWidthWithMargins = childWidth + lp.leftMargin + lp.rightMargin
            val childHeightWithMargins = childHeight + lp.topMargin + lp.bottomMargin

            if (currentX + childWidthWithMargins > maxWidth && currentX > paddingLeft) {
                // 换行
                currentY += lineHeight
                currentX = paddingLeft
                lineHeight = 0
            }

            child.layout(
                currentX + lp.leftMargin,
                currentY + lp.topMargin,
                currentX + lp.leftMargin + childWidth,
                currentY + lp.topMargin + childHeight
            )

            currentX += childWidthWithMargins
            lineHeight = maxOf(lineHeight, childHeightWithMargins)
        }
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet): LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams): LayoutParams {
        return LayoutParams(lp)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean {
        return p is LayoutParams
    }

    class LayoutParams : ViewGroup.MarginLayoutParams {
        constructor(c: Context, attrs: android.util.AttributeSet) : super(c, attrs)
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: ViewGroup.LayoutParams) : super(source)
        constructor(source: LayoutParams) : super(source)
    }
}
