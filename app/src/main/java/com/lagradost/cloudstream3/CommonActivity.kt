package com.lagradost.cloudstream3

import android.app.Activity
import android.os.Build
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.UiText
import java.lang.ref.WeakReference

enum class FocusDirection {
    Start,
    End,
    Up,
    Down,
}

object CommonActivity {

    private var _activity: WeakReference<Activity>? = null
    var activity: Activity?
        get() = _activity?.get()
        private set(value) { _activity = WeakReference(value) }

    var isInPIPMode: Boolean = false
    var isPipDesired: Boolean = false
    var keyEventListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null

    val onColorSelectedEvent = Event<Pair<Int, Int>>()
    val onDialogDismissedEvent = Event<Int>()
    var appliedTheme: Int = 0

    val screenHeight: Int get() {
        val act = activity ?: return 0
        return act.resources.displayMetrics.heightPixels
    }
    val screenWidth: Int get() {
        val act = activity ?: return 0
        return act.resources.displayMetrics.widthPixels
    }
    val screenHeightWithOrientation: Int get() {
        val act = activity ?: return 0
        return act.resources.displayMetrics.heightPixels
    }
    val screenWidthWithOrientation: Int get() {
        val act = activity ?: return 0
        return act.resources.displayMetrics.widthPixels
    }

    fun setActivityInstance(newActivity: Activity?) { activity = newActivity }

    fun init(act: Activity) {
        setActivityInstance(act)
    }

    fun loadThemes(act: Activity?) {

    }

    fun setLocale(context: android.content.Context?, languageTag: String?) {
        if (context == null || languageTag == null) return
        val locale = java.util.Locale.forLanguageTag(languageTag)
        val resources = context.resources
        val config = resources.configuration
        java.util.Locale.setDefault(locale)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.createConfigurationContext(config)
    }

    fun onKeyDown(act: Activity?, keyCode: Int, event: KeyEvent?): Boolean? {
        return null
    }

    fun dispatchKeyEvent(act: Activity?, event: KeyEvent?): Boolean? {
        return keyEventListener?.invoke(Pair(event, false)) == true
    }

    fun onUserLeaveHint(act: Activity) {

    }

    fun continueGetNextFocus(
        root: Any?,
        view: View,
        direction: FocusDirection,
        nextId: Int,
        depth: Int = 0
    ): View? {
        if (nextId == View.NO_ID) return null
        var next = when (root) {
            is Activity -> root.findViewById(nextId)
            is View -> root.rootView.findViewById<View?>(nextId)
            else -> null
        } ?: return null
        return next
    }

    private var currentToast: Toast? = null

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, act.getString(message), duration) }
    }

    fun showToast(message: String?, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, message, duration) }
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        val act = activity ?: return
        if (message == null) return
        act.runOnUiThread { showToast(act, message.asString(act), duration) }
    }

    fun showToast(act: Activity?, text: UiText, duration: Int) {
        if (act == null) return
        text.asStringNull(act)?.let { showToast(act, it, duration) }
    }

    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        if (act == null) return
        showToast(act, act.getString(message), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (act == null || message == null) return
        try { currentToast?.cancel() } catch (_: Exception) {}
        val toast = Toast.makeText(act, message, duration ?: Toast.LENGTH_SHORT)
        currentToast = toast
        toast.show()
    }

    fun <T> Activity.setKey(path: String, value: T) {
        com.lagradost.cloudstream3.CloudStreamApp.setKey(path, value)
    }

    fun hideSystemUI(activity: Activity?) {}
    fun showSystemUI(activity: Activity?) {}
}
