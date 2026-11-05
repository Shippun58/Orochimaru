package ani.sanin.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.widget.ListView
import android.widget.ListAdapter
import ani.sanin.R

class AlertDialogBuilder(private val context: Context) {
    private var title: String? = null
    private var message: String? = null
    private var posButtonTitle: String? = null
    private var negButtonTitle: String? = null
    private var neutralButtonTitle: String? = null
    private var onPositiveButtonClick: (() -> Unit)? = null
    private var onNegativeButtonClick: (() -> Unit)? = null
    private var onNeutralButtonClick: (() -> Unit)? = null
    private var items: Array<String>? = null
    private var adapter: ListAdapter? = null
    private var checkedItems: BooleanArray? = null
    private var onItemsSelected: ((BooleanArray) -> Unit)? = null
    private var selectedItemIndex: Int = -1
    private var onItemSelected: ((Int) -> Unit)? = null
    private var customView: View? = null
    private var onShow: (() -> Unit)? = null
    private var attach: ((dialog: AlertDialog) -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var cancelable: Boolean = true
    private var dismissOnSelect: Boolean = true
    private var fixedWidthPx: Int? = null

    fun setWidthPx(widthPx: Int): AlertDialogBuilder {
        this.fixedWidthPx = widthPx
        return this
    }
    fun setCancelable(cancelable: Boolean): AlertDialogBuilder {
        this.cancelable = cancelable
        return this
    }

    fun setOnShowListener(onShow: () -> Unit): AlertDialogBuilder {
        this.onShow = onShow
        return this
    }

    fun setOnCancelListener(onCancel: () -> Unit): AlertDialogBuilder {
        this.onCancel = onCancel
        return this
    }

    fun setTitle(title: String?): AlertDialogBuilder {
        this.title = title
        return this
    }

    fun setTitle(int: Int, formatArgs: Int? = null): AlertDialogBuilder {
        this.title = context.getString(int, formatArgs)
        return this
    }

    fun setMessage(message: String?): AlertDialogBuilder {
        this.message = message
        return this
    }

    fun setMessage(stringInt: Int, vararg formatArgs: Any): AlertDialogBuilder {
        this.message = context.getString(stringInt, *formatArgs)
        return this
    }

    fun setCustomView(view: View): AlertDialogBuilder {
        this.customView = view
        return this
    }

    fun setCustomView(layoutResId: Int): AlertDialogBuilder {
        this.customView = View.inflate(context, layoutResId, null)
        return this
    }

    fun setPosButton(title: String?, onClick: (() -> Unit)? = null): AlertDialogBuilder {
        this.posButtonTitle = title
        this.onPositiveButtonClick = onClick
        return this
    }

    fun setPosButton(
        int: Int,
        formatArgs: Int? = null,
        onClick: (() -> Unit)? = null
    ): AlertDialogBuilder {
        this.posButtonTitle = context.getString(int, formatArgs)
        this.onPositiveButtonClick = onClick
        return this
    }

    fun setNegButton(title: String?, onClick: (() -> Unit)? = null): AlertDialogBuilder {
        this.negButtonTitle = title
        this.onNegativeButtonClick = onClick
        return this
    }

    fun setNegButton(
        int: Int,
        formatArgs: Int? = null,
        onClick: (() -> Unit)? = null
    ): AlertDialogBuilder {
        this.negButtonTitle = context.getString(int, formatArgs)
        this.onNegativeButtonClick = onClick
        return this
    }

    fun setNeutralButton(title: String?, onClick: (() -> Unit)? = null): AlertDialogBuilder {
        this.neutralButtonTitle = title
        this.onNeutralButtonClick = onClick
        return this
    }

    fun setNeutralButton(
        int: Int,
        formatArgs: Int? = null,
        onClick: (() -> Unit)? = null
    ): AlertDialogBuilder {
        this.neutralButtonTitle = context.getString(int, formatArgs)
        this.onNeutralButtonClick = onClick
        return this
    }

    fun attach(attach: ((dialog: AlertDialog) -> Unit)?): AlertDialogBuilder {
        this.attach = attach
        return this
    }

    fun onDismiss(onDismiss: (() -> Unit)? = null): AlertDialogBuilder {
        this.onDismiss = onDismiss
        return this
    }

    fun singleChoiceItems(
        items: Array<String>,
        selectedItemIndex: Int = -1,
        dismissOnSelect: Boolean = true,
        onItemSelected: (Int) -> Unit,

    ): AlertDialogBuilder {
        this.items = items
        this.selectedItemIndex = selectedItemIndex
        this.onItemSelected = onItemSelected
        this.dismissOnSelect = dismissOnSelect
        return this
    }

    fun singleChoiceAdapter(
        adapter: ListAdapter,
        selectedItemIndex: Int,
        dismissOnSelect: Boolean = true,
        onItemSelected: (Int) -> Unit,
    ): AlertDialogBuilder {
        this.adapter = adapter
        this.selectedItemIndex = selectedItemIndex
        this.onItemSelected = onItemSelected
        this.dismissOnSelect = dismissOnSelect
        return this
    }

    fun multiChoiceItems(
        items: Array<String>,
        checkedItems: BooleanArray? = null,
        onItemsSelected: (BooleanArray) -> Unit
    ): AlertDialogBuilder {
        this.items = items
        this.checkedItems = checkedItems ?: BooleanArray(items.size) { false }
        this.onItemsSelected = onItemsSelected
        return this
    }

    fun show() {
        if (context is Activity && context.isFinishing) return

        val builder = AlertDialog.Builder(context, R.style.MyPopup)
        if (title != null) builder.setTitle(title)
        if (message != null) builder.setMessage(message)
        if (customView != null) builder.setView(customView)
        if (adapter != null) {
            builder.setSingleChoiceItems(adapter, selectedItemIndex) { dialog, which ->
                selectedItemIndex = which
                onItemSelected?.invoke(which)
                if (dismissOnSelect) {
                    dialog.dismiss()
                }
            }
        } else if (items != null) {
            if (onItemSelected != null) {
                builder.setSingleChoiceItems(items, selectedItemIndex) { dialog, which ->
                    selectedItemIndex = which
                    onItemSelected?.invoke(which)
                    if (dismissOnSelect) {
                        dialog.dismiss()
                    }
                }
            } else if (checkedItems != null && onItemsSelected != null) {
                builder.setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                    checkedItems?.set(which, isChecked)
                    onItemsSelected?.invoke(checkedItems!!)
                }
            }
        }
        if (posButtonTitle != null) {
            builder.setPositiveButton(posButtonTitle) { dialog, _ ->
                onPositiveButtonClick?.invoke()
                dialog.dismiss()
            }
        }
        if (negButtonTitle != null) {
            builder.setNegativeButton(negButtonTitle) { dialog, _ ->
                onNegativeButtonClick?.invoke()
                dialog.dismiss()
            }
        }
        if (neutralButtonTitle != null) {
            builder.setNeutralButton(neutralButtonTitle) { dialog, _ ->
                onNeutralButtonClick?.invoke()
                dialog.dismiss()
            }
        }
        if (onCancel != null) {
            builder.setOnCancelListener {
                onCancel?.invoke()
            }
        }
        builder.setCancelable(cancelable)
        val dialog = builder.create()
        attach?.invoke(dialog)
        dialog.setOnDismissListener {
            onDismiss?.invoke()
        }
        dialog.setOnShowListener {
            onShow?.invoke()
        }
        dialog.window?.apply {
            setDimAmount(0.5f)
            attributes.windowAnimations = android.R.style.Animation_Dialog
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val params = attributes
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.setBlurBehindRadius(20)
                attributes = params
            }
        }
        dialog.show()

        if (adapter != null) {
            dialog.listView?.let { lv ->
                val listAdapter = adapter
                lv.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val dir = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> 1
                        KeyEvent.KEYCODE_DPAD_UP -> -1
                        else -> return@setOnKeyListener false
                    }
                    val la = listAdapter ?: return@setOnKeyListener false
                    val count = lv.count
                    if (count == 0) return@setOnKeyListener false
                    val cur = lv.selectedItemPosition
                    val start = if (cur >= 0 && cur < count && la.isEnabled(cur)) cur + dir
                    else if (dir == 1) 0 else count - 1
                    var target = -1
                    var i = start
                    while (i in 0 until count) {
                        if (la.isEnabled(i)) { target = i; break }
                        i += dir
                    }
                    if (target >= 0) lv.setSelection(target)
                    true
                }
            }
        }

        fixedWidthPx?.let { widthPx ->
            dialog.window?.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }
}

fun Context.customAlertDialog(): AlertDialogBuilder {
    return AlertDialogBuilder(this)
}
