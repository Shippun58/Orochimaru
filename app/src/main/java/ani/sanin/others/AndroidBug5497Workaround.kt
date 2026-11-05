package ani.sanin.others

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout

class AndroidBug5497Workaround private constructor(
    activity: Activity,
    private val callback: (Boolean) -> Unit
) {
    private val mChildOfContent: View
    private var usableHeightPrevious = 0
    private val frameLayoutParams: FrameLayout.LayoutParams

    init {
        val content: FrameLayout = activity.findViewById(android.R.id.content)
        mChildOfContent = content.getChildAt(0)
        mChildOfContent.viewTreeObserver.addOnGlobalLayoutListener { possiblyResizeChildOfContent() }
        frameLayoutParams = mChildOfContent.layoutParams as FrameLayout.LayoutParams
    }

    private fun possiblyResizeChildOfContent() {
        val usableHeightNow = computeUsableHeight()
        if (usableHeightNow != usableHeightPrevious) {
            val usableHeightSansKeyboard = mChildOfContent.rootView.height
            val heightDifference = usableHeightSansKeyboard - usableHeightNow
            if (heightDifference > usableHeightSansKeyboard / 4) {

                callback.invoke(true)
                frameLayoutParams.height = usableHeightSansKeyboard - heightDifference
            } else {

                callback.invoke(false)
                frameLayoutParams.height = usableHeightSansKeyboard
            }
            mChildOfContent.requestLayout()
            usableHeightPrevious = usableHeightNow
        }
    }

    private fun computeUsableHeight(): Int {
        val r = Rect()
        mChildOfContent.getWindowVisibleDisplayFrame(r)
        return r.bottom
    }

    companion object {

        fun assistActivity(activity: Activity, callback: (Boolean) -> Unit) {
            AndroidBug5497Workaround(activity, callback)
        }
    }
}

