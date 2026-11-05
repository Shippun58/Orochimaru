package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.content.withStyledAttributes
import ani.sanin.R
import com.lagradost.cloudstream3.mvvm.logError

class PercentageCropImageView : androidx.appcompat.widget.AppCompatImageView {
    private var mCropYCenterOffsetPct: Float? = null
    private var mCropXCenterOffsetPct: Float? = null

    constructor(context: Context?) : super(context!!)

    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs) {
        initAttrs(context, attrs)
    }

    constructor(
        context: Context?, attrs: AttributeSet?,
        defStyle: Int
    ) : super(context!!, attrs, defStyle) {
        initAttrs(context, attrs)
    }

    var cropYCenterOffsetPct: Float
        get() = mCropYCenterOffsetPct!!
        set(cropYCenterOffsetPct) {
            require(cropYCenterOffsetPct <= 1.0) { "Value too large: Must be <= 1.0" }
            mCropYCenterOffsetPct = cropYCenterOffsetPct
        }
    var cropXCenterOffsetPct: Float
        get() = mCropXCenterOffsetPct!!
        set(cropXCenterOffsetPct) {
            require(cropXCenterOffsetPct <= 1.0) { "Value too large: Must be <= 1.0" }
            mCropXCenterOffsetPct = cropXCenterOffsetPct
        }

    private fun myConfigureBounds() {
        if (this.scaleType == ScaleType.MATRIX) {

            val d = this.drawable
            if (d != null) {
                val dWidth = d.intrinsicWidth
                val dHeight = d.intrinsicHeight
                val m = Matrix()
                val vWidth = width - this.paddingLeft - this.paddingRight
                val vHeight = height - this.paddingTop - this.paddingBottom
                val scale: Float
                var dx = 0f
                var dy = 0f
                if (dWidth * vHeight > vWidth * dHeight) {
                    val cropXCenterOffsetPct =
                        if (mCropXCenterOffsetPct != null) mCropXCenterOffsetPct!! else 0.5f
                    scale = vHeight.toFloat() / dHeight.toFloat()
                    dx = (vWidth - dWidth * scale) * cropXCenterOffsetPct
                } else {
                    val cropYCenterOffsetPct =
                        if (mCropYCenterOffsetPct != null) mCropYCenterOffsetPct!! else 0f
                    scale = vWidth.toFloat() / dWidth.toFloat()
                    dy = (vHeight - dHeight * scale) * cropYCenterOffsetPct
                }
                m.setScale(scale, scale)
                m.postTranslate((dx + 0.5f).toInt().toFloat(), (dy + 0.5f).toInt().toFloat())
                this.imageMatrix = m
            }
        }
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        myConfigureBounds()
        return changed
    }

    override fun setImageDrawable(d: Drawable?) {
        super.setImageDrawable(d)
        myConfigureBounds()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        myConfigureBounds()
    }

    fun redraw() {
        val d = this.drawable
        if (d != null) {

            setImageDrawable(null)
            setImageDrawable(d)
        }
    }

    private fun initAttrs(context: Context, attrs: AttributeSet?) {
        attrs ?: return
        context.withStyledAttributes(attrs, R.styleable.PercentageCropImageView) {
            try {
                if (hasValue(R.styleable.PercentageCropImageView_cropYCenterOffsetPct)) {
                    mCropYCenterOffsetPct = getFloat(
                        R.styleable.PercentageCropImageView_cropYCenterOffsetPct,
                        0.5f
                    )
                }
                if (hasValue(R.styleable.PercentageCropImageView_cropXCenterOffsetPct)) {
                    mCropXCenterOffsetPct = getFloat(
                        R.styleable.PercentageCropImageView_cropXCenterOffsetPct,
                        0.5f
                    )
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }
}