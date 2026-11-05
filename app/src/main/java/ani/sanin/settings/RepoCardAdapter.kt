package ani.sanin.settings

import android.content.res.Configuration
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.DiffUtil
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
    val languages: List<String> = emptyList()
) {
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        return name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
            url.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
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
    private val onExitTop: (() -> Boolean)? = null
) : ListAdapter<RepoUi, RepoCardAdapter.VH>(DIFF) {

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

        holder.binding.repoCount.text = "${item.count} $countLabel"

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
            text = languageCount.toString()
            contentDescription = "$languageCount languages"
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

        with(holder.binding.repoBrowseButton) {
            text = "Browse"
            contentDescription = "Browse ${item.name}"
            setOnClickListener { onOpen(item) }
            isFocusable = true

            backgroundTintList = null
            val onPrimary = ctx.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
            val borderWidth = (3f * resources.displayMetrics.density).toInt()

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

        holder.binding.repoCardRoot.isFocusable = false
        holder.binding.repoCardRoot.setOnLongClickListener { onLongClick(item); true }
    }

    private fun moveBrowseFocus(holder: VH, delta: Int): Boolean {
        val rv = holder.itemView.parent as? RecyclerView ?: return false
        val current = holder.absoluteAdapterPosition
        if (current == RecyclerView.NO_POSITION) return false
        val target = current + delta
        if (target < 0) return onExitTop?.invoke() ?: false
        if (target >= itemCount) return false
        val vh = rv.findViewHolderForAdapterPosition(target) as? VH ?: return false
        if (vh.itemView.parent == null) return false
        return vh.binding.repoBrowseButton.requestFocus()
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
