package ani.sanin.settings

import android.content.res.Configuration
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemRepoCardBinding
import ani.sanin.getThemeColor
import ani.sanin.ui.GlassPill
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.Locale

data class RepoUi(
    val name: String,
    val url: String,
    val count: Int,
    val iconUrl: String? = null,
    val contentTypes: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val countText: String? = null,
    val scopeLabel: String = "languages",
    val description: String? = null,
    val watermarkRes: Int? = null,
    val watermarkLeft: Boolean = false,
    val hideCount: Boolean = false,
    val showInfoButton: Boolean = false,
    val toggleVisible: Boolean = false,
    val enabled: Boolean = true,
    val dragVisible: Boolean = false,
    val needsConfiguration: Boolean = false,
    val configUrl: String? = null,
) {
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        val needle = q.lowercase(Locale.ROOT)
        return name.lowercase(Locale.ROOT).contains(needle) ||
            url.lowercase(Locale.ROOT).contains(needle) ||
            description.orEmpty().lowercase(Locale.ROOT).contains(needle) ||
            contentTypes.any { it.lowercase(Locale.ROOT).contains(needle) } ||
            languages.any { it.lowercase(Locale.ROOT).contains(needle) }
    }
}

fun githubOwnerAvatar(repoUrl: String): String? {
    val raw = "raw.githubusercontent.com/"
    val idx = repoUrl.indexOf(raw)
    if (idx < 0) return null
    val rest = repoUrl.substring(idx + raw.length)
    val owner = rest.substringBefore('/')
    if (owner.isBlank() || owner == "index.json") return null
    return "https://github.com/$owner.png"
}

class RepoCardAdapter(
    private val onOpen: (RepoUi) -> Unit,
    private val onLongClick: (RepoUi) -> Unit,
    private val countLabel: String = "plugins",
    private val onExitTop: (() -> Boolean)? = null,
    private val actionLabel: String = "Browse",
    private val onConfigure: ((RepoUi) -> Unit)? = null,
    private val onToggle: ((RepoUi, Boolean) -> Unit)? = null,
    private val onReorder: ((Int, Int) -> Unit)? = null,
) : ListAdapter<RepoUi, RepoCardAdapter.VH>(DIFF) {

    private var reorderAnchor = RecyclerView.NO_POSITION
    var reorderMessage: TextView? = null
    var itemTouchHelper: ItemTouchHelper? = null
    var onDragModeChanged: ((Boolean) -> Unit)? = null

    fun disarmDrag(persist: Boolean) {
        if (reorderAnchor == RecyclerView.NO_POSITION) return
        val anchor = reorderAnchor
        reorderAnchor = RecyclerView.NO_POSITION
        reorderMessage?.visibility = View.GONE
        onDragModeChanged?.invoke(false)
        if (anchor != RecyclerView.NO_POSITION) notifyItemChanged(anchor)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRepoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val isDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        holder.binding.repoName.text = item.name

        if (item.hideCount) {
            holder.binding.repoCount.visibility = View.GONE
        } else {
            holder.binding.repoCount.visibility = View.VISIBLE
            holder.binding.repoCount.text = item.countText ?: "${item.count} $countLabel"
        }
        holder.binding.repoWatermark.setImageResource(item.watermarkRes ?: R.drawable.ic_github)
        (holder.binding.repoWatermark.layoutParams as android.widget.FrameLayout.LayoutParams).let { lp ->
            if (item.watermarkLeft) {
                lp.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                lp.marginStart = (12 * ctx.resources.displayMetrics.density).toInt()
                lp.marginEnd = 0
                lp.bottomMargin = 0
            } else {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                val d = ctx.resources.displayMetrics.density
                lp.marginStart = 0
                lp.marginEnd = (12 * d).toInt()
                lp.bottomMargin = (12 * d).toInt()
            }
            holder.binding.repoWatermark.layoutParams = lp
        }

        holder.binding.repoContentTypes.removeAllViews()
        val chipTextColor = ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        item.contentTypes.forEach { type ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = type
                isClickable = false
                isFocusable = false
                textSize = 11f
                setTextColor(chipTextColor)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#1A000000")
                )
                chipCornerRadius = 10f * ctx.resources.displayMetrics.density
                chipMinHeight = 24f * ctx.resources.displayMetrics.density
                setPadding(
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0,
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0
                )
            }
            holder.binding.repoContentTypes.addView(chip)
        }

        holder.binding.repoLanguages.removeAllViews()
        val languageCount = item.languages.size
        holder.binding.repoLanguages.addView(com.google.android.material.chip.Chip(ctx).apply {
            text = if (item.scopeLabel == "languages") languageCount.toString()
                else "$languageCount ${item.scopeLabel}"
            contentDescription = "$languageCount ${item.scopeLabel}"
            isClickable = false
            isFocusable = false
            textSize = 11f
            setTextColor(chipTextColor)
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                if (isDark) Color.parseColor("#30FFFFFF") else Color.parseColor("#1A000000")
            )
            chipCornerRadius = 10f * ctx.resources.displayMetrics.density
            chipMinHeight = 24f * ctx.resources.displayMetrics.density
            setPadding(
                (8 * ctx.resources.displayMetrics.density).toInt(),
                0,
                (8 * ctx.resources.displayMetrics.density).toInt(),
                0
            )
        })

        val primaryColor = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val defaultTop = if (isDark) Color.BLACK else Color.WHITE
        val defaultBot = blendWithSurface(primaryColor, if (isDark) 0.55f else 0.35f)
        applyGradient(holder.binding.repoCardRoot, defaultTop, defaultBot)

        val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
        if (!skipIcons && !item.iconUrl.isNullOrBlank()) {
            holder.binding.repoLogo.setImageResource(R.drawable.ic_extension)
            Glide.with(ctx)
                .asBitmap()
                .load(item.iconUrl)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                        Palette.from(resource).generate { palette ->
                            val vibrant = palette?.lightVibrantSwatch?.rgb
                                ?: palette?.vibrantSwatch?.rgb
                                ?: palette?.dominantSwatch?.rgb
                            if (vibrant != null) {
                                val topColor = if (isDark) Color.BLACK else Color.WHITE
                                val botColor = blendWithSurface(vibrant, if (isDark) 0.6f else 0.4f)
                                applyGradient(holder.binding.repoCardRoot, topColor, botColor)
                            }
                        }
                        holder.binding.repoLogo.setImageBitmap(resource)
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        } else {
            holder.binding.repoLogo.setImageResource(R.drawable.ic_extension)
        }

        val onPrimary = ctx.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val borderWidth = (3f * ctx.resources.displayMetrics.density).toInt()

        with(holder.binding.repoBrowseButton) {
            text = actionLabel
            contentDescription = "$actionLabel ${item.name}"
            setOnClickListener { onOpen(item) }
            isFocusable = true

            backgroundTintList = null

            fun styleBrowse(focused: Boolean) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(if (focused) ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary) else Color.WHITE)
                    cornerRadius = 24f * resources.displayMetrics.density
                }

                foreground = if (focused) {
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(Color.TRANSPARENT)
                        cornerRadius = 24f * resources.displayMetrics.density
                        setStroke(borderWidth, onPrimary)
                    }
                } else {
                    null
                }
                setTextColor(if (focused) onPrimary else Color.BLACK)
            }
            styleBrowse(false)
            setOnFocusChangeListener { _, hasFocus ->
                styleBrowse(hasFocus)
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveBrowseFocus(holder, 1)
                        KeyEvent.KEYCODE_DPAD_UP -> moveBrowseFocus(holder, -1)
                        else -> false
                    }
                }
            }
        }

        with(holder.binding.repoInfoButton) {
            visibility = if (item.showInfoButton) View.VISIBLE else View.GONE
            contentDescription = "Info ${item.name}"
            setOnClickListener { onLongClick(item) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveBrowseFocus(holder, 1) { it.binding.repoInfoButton }
                        KeyEvent.KEYCODE_DPAD_UP -> moveBrowseFocus(holder, -1) { it.binding.repoInfoButton }
                        else -> false
                    }
                }
            }
        }

        holder.binding.repoCardRoot.alpha = if (item.enabled) 1f else 0.45f

        with(holder.binding.repoEnableToggle) {
            visibility = if (item.toggleVisible && onToggle != null) View.VISIBLE else View.GONE
            isChecked = item.enabled
            jumpDrawablesToCurrentState()
            contentDescription = if (item.enabled) "Disable ${item.name}" else "Enable ${item.name}"
            setOnClickListener { onToggle?.invoke(item, !item.enabled) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveBrowseFocus(holder, 1) { it.binding.repoEnableToggle }
                        KeyEvent.KEYCODE_DPAD_UP -> moveBrowseFocus(holder, -1) { it.binding.repoEnableToggle }
                        else -> false
                    }
                }
            }
        }

        with(holder.binding.repoDragHandle) {
            visibility = if (item.dragVisible && onReorder != null) View.VISIBLE else View.GONE
            contentDescription = "Reorder ${item.name}"
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (holder.absoluteAdapterPosition == reorderAnchor) primaryColor else chipTextColor
            )
            setOnClickListener { v ->
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (reorderAnchor == currentPos) {
                    disarmDrag(persist = true)
                } else {
                    reorderAnchor = currentPos
                    reorderMessage?.visibility = View.VISIBLE
                    onDragModeChanged?.invoke(true)
                    v.imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                    v.post { v.requestFocus() }
                }
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                    reorderAnchor == holder.absoluteAdapterPosition
                ) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }
            setOnLongClickListener {
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                itemTouchHelper?.startDrag(holder)
                true
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    val currentPos = holder.absoluteAdapterPosition
                    if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                    val armed = reorderAnchor == currentPos
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (armed) {
                                if (currentPos > 0) {
                                    onReorder?.invoke(currentPos, currentPos - 1)
                                    reorderAnchor = currentPos - 1
                                }
                                true
                            } else {
                                moveBrowseFocus(holder, -1) { it.binding.repoDragHandle }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (armed) {
                                if (currentPos < itemCount - 1) {
                                    onReorder?.invoke(currentPos, currentPos + 1)
                                    reorderAnchor = currentPos + 1
                                }
                                true
                            } else {
                                moveBrowseFocus(holder, 1) { it.binding.repoDragHandle }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (armed) {
                                disarmDrag(persist = true)
                            }
                            false
                        }
                        else -> false
                    }
                }
            }
        }

        with(holder.binding.repoSettingsButton) {
            visibility = if (onConfigure != null) View.VISIBLE else View.GONE
            contentDescription = "Configure ${item.name}"

            fun styleSettings(focused: Boolean) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(if (focused) ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary) else Color.WHITE)
                    cornerRadius = 24f * ctx.resources.displayMetrics.density
                }
                foreground = if (focused) {
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(Color.TRANSPARENT)
                        cornerRadius = 24f * ctx.resources.displayMetrics.density
                        setStroke(borderWidth, onPrimary)
                    }
                } else {
                    null
                }
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (focused) onPrimary else Color.BLACK
                )
            }
            styleSettings(false)
            setOnFocusChangeListener { _, hasFocus ->
                styleSettings(hasFocus)
            }
            setOnClickListener {
                onConfigure?.invoke(item)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveBrowseFocus(holder, 1) { it.binding.repoSettingsButton }
                        KeyEvent.KEYCODE_DPAD_UP -> moveBrowseFocus(holder, -1) { it.binding.repoSettingsButton }
                        else -> false
                    }
                }
            }
        }

        holder.binding.repoCardRoot.isFocusable = false
        holder.binding.repoCardRoot.setOnLongClickListener { onLongClick(item); true }
    }

    private fun moveBrowseFocus(
        holder: VH,
        delta: Int,
        request: (VH) -> View = { it.binding.repoBrowseButton },
    ): Boolean {
        val rv = holder.itemView.parent as? RecyclerView ?: return false
        val current = holder.absoluteAdapterPosition
        if (current == RecyclerView.NO_POSITION) return false
        val target = current + delta
        if (target < 0) return onExitTop?.invoke() ?: false
        if (target >= itemCount) return false
        val vh = rv.findViewHolderForAdapterPosition(target) as? VH ?: return false
        if (vh.itemView.parent == null) return false
        return request(vh).requestFocus()
    }

    private fun applyGradient(view: View, topColor: Int, bottomColor: Int) {
        view.background = GlassPill.topLit(
            view.context,
            intArrayOf(topColor, bottomColor),
            16f * view.resources.displayMetrics.density,
        )
    }

    private fun blendWithSurface(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1 - factor) + 26 * factor).toInt()
        val g = (Color.green(color) * (1 - factor) + 26 * factor).toInt()
        val b = (Color.blue(color) * (1 - factor) + 26 * factor).toInt()
        return Color.rgb(r, g, b)
    }

    class VH(val binding: ItemRepoCardBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RepoUi>() {
            override fun areItemsTheSame(oldItem: RepoUi, newItem: RepoUi) =
                oldItem.url == newItem.url
            override fun areContentsTheSame(oldItem: RepoUi, newItem: RepoUi) = oldItem == newItem
        }
    }
}
