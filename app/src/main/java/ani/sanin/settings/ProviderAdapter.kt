package ani.sanin.settings

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemProviderBinding
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.ui.GlassPill
import ani.sanin.getThemeColor

class ProviderAdapter(
    private val items: MutableList<ProviderItem>,
    private val onStateChanged: () -> Unit,
    private val onReorder: (List<String>) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

    private val downloading = mutableSetOf<String>()
    var dragActivePosition: Int? = null
    var reorderMessage: TextView? = null
    var itemTouchHelper: ItemTouchHelper? = null
    var onDragModeChanged: ((Boolean) -> Unit)? = null

    class ViewHolder(val binding: ItemProviderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.providerName.text = item.name
        bindLanguageBadge(b, item)
        bindReorder(holder)

        if (item.saveName in downloading) {
            showDownloading(b)
        } else if (item.isEnabled) {
            showEnabled(b, position)
        } else {
            showIdle(b, position)
        }
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun disarmDrag(persist: Boolean) {
        dragActivePosition = null
        reorderMessage?.visibility = View.GONE
        if (persist) onReorder(items.map { it.saveName })
        onDragModeChanged?.invoke(false)
        notifyDataSetChanged()
    }

    private fun bindLanguageBadge(b: ItemProviderBinding, item: ProviderItem) {
        val badge = b.providerLanguage
        badge.text = item.language
        val color = Color.parseColor(if (item.isEnglish) "#4CAF50" else "#FF9800")
        badge.backgroundTintList = ColorStateList.valueOf(
            Color.argb(0x26, Color.red(color), Color.green(color), Color.blue(color))
        )
        badge.setTextColor(color)
    }

    private fun tintArrows(b: ItemProviderBinding, active: Boolean) {
        val themedContext = GlassPill.themedContext(b.root.context)
        val color = if (active) {
            FocusEffectUtil.getPrimaryColor(themedContext)
        } else {
            themedContext.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        }
        b.providerDragUpArrow.setColorFilter(color)
        b.providerDragDownArrow.setColorFilter(color)
    }

    private fun bindReorder(holder: ViewHolder) {
        val b = holder.binding
        val isDragging = dragActivePosition == holder.absoluteAdapterPosition
        tintArrows(b, isDragging)

        b.providerDragHandle.setOnClickListener {
            val currentPos = holder.absoluteAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (dragActivePosition == currentPos) {
                disarmDrag(persist = true)
            } else {
                dragActivePosition = currentPos
                reorderMessage?.visibility = View.VISIBLE
                onDragModeChanged?.invoke(true)
                tintArrows(b, true)
                b.providerDragHandle.post { b.providerDragHandle.requestFocus() }
            }
        }

        holder.itemView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                dragActivePosition == holder.absoluteAdapterPosition
            ) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }

        b.providerDragHandle.setOnLongClickListener {
            val currentPos = holder.absoluteAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            itemTouchHelper?.startDrag(holder)
            true
        }

        b.providerDragHandle.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val currentPos = holder.absoluteAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            if (dragActivePosition != currentPos) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentPos > 0) {
                        items.add(currentPos - 1, items.removeAt(currentPos))
                        dragActivePosition = currentPos - 1
                        notifyItemMoved(currentPos, currentPos - 1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (currentPos < items.size - 1) {
                        items.add(currentPos + 1, items.removeAt(currentPos))
                        dragActivePosition = currentPos + 1
                        notifyItemMoved(currentPos, currentPos + 1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    disarmDrag(persist = true)
                    false
                }
                else -> false
            }
        }

        FocusEffectUtil.applyFocusListener(holder.itemView)
        FocusEffectUtil.applyFocusListener(b.providerDragHandle)
        FocusEffectUtil.applyFocusListener(b.providerActionIcon)
    }

    private fun showIdle(b: ItemProviderBinding, pos: Int) {
        b.providerActionIcon.setImageResource(R.drawable.ic_download_24)
        val primary = FocusEffectUtil.getPrimaryColor(b.providerActionIcon.context)
        b.providerActionIcon.imageTintList = ColorStateList.valueOf(primary)
        b.providerActionIcon.visibility = View.VISIBLE
        b.providerProgress.visibility = View.GONE
        b.providerProgress.progress = 0
        b.providerProgress.progressTintList = ColorStateList.valueOf(primary)
        b.providerActionIcon.setOnClickListener { startDownload(b, pos) }
    }

    private fun showDownloading(b: ItemProviderBinding) {
        b.providerActionIcon.visibility = View.GONE
        b.providerProgress.visibility = View.VISIBLE
    }

    private fun showEnabled(b: ItemProviderBinding, pos: Int) {
        b.providerActionIcon.setImageResource(R.drawable.ic_round_delete_24)
        b.providerActionIcon.rotation = 0f
        b.providerActionIcon.imageTintList = ColorStateList.valueOf(
            FocusEffectUtil.getPrimaryColor(b.providerActionIcon.context)
        )
        b.providerActionIcon.visibility = View.VISIBLE
        b.providerProgress.visibility = View.GONE
        b.providerProgress.progress = 0
        b.providerActionIcon.setOnClickListener { v ->
            v.isEnabled = false
            val it = items[pos]
            it.isEnabled = false
            val current = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders)
            PrefManager.setVal(PrefName.EnabledProviders, current - it.saveName)
            downloading.remove(it.saveName)
            notifyItemChanged(pos)
            onStateChanged()
            v.postDelayed({ v.isEnabled = true }, 300)
        }
    }

    private fun startDownload(b: ItemProviderBinding, pos: Int) {
        val item = items[pos]
        downloading.add(item.saveName)
        notifyItemChanged(pos)

        b.providerProgress.progress = 0
        b.providerProgress.visibility = View.VISIBLE

        if (!AnimUtils.enabled(PrefName.InstallSpinnerAnimations)) {
            b.providerProgress.progress = 100
            b.providerProgress.visibility = View.GONE
            downloading.remove(item.saveName)
            item.isEnabled = true
            val current = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders)
            PrefManager.setVal(PrefName.EnabledProviders, current + item.saveName)
            notifyItemChanged(pos)
            onStateChanged()
            return
        }
        val animator = ObjectAnimator.ofInt(b.providerProgress, "progress", 0, 100)
        animator.duration = 2500
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationEnd(a: Animator) {
                downloading.remove(item.saveName)
                item.isEnabled = true
                val current = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders)
                PrefManager.setVal(PrefName.EnabledProviders, current + item.saveName)
                notifyItemChanged(pos)
                onStateChanged()
            }
            override fun onAnimationCancel(a: Animator) {
                downloading.remove(item.saveName)
                notifyItemChanged(pos)
            }
            override fun onAnimationRepeat(a: Animator) {}
        })
        animator.start()
    }

    override fun getItemCount(): Int = items.size
}
