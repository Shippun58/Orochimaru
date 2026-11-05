package ani.sanin.home

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.connections.anilist.Anilist
import ani.sanin.R
import ani.sanin.connections.kitsu.Kitsu
import ani.sanin.connections.kitsu.KitsuDiscoveryItem
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.media.MediaDetailsActivity
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KitsuCategoryResultsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CATEGORY_SLUG = "kitsu_category_slug"
        const val EXTRA_CATEGORY_TITLE = "kitsu_category_title"
        const val EXTRA_CATEGORY_DESCRIPTION = "kitsu_category_description"
        private const val PAGE_SIZE = 20
    }

    private lateinit var adapter: KitsuAnimeAdapter
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private var categorySlug = ""
    private var categoryTitle = ""
    private var categoryDescription = ""
    private var resolvedCategorySlug: String? = null
    private lateinit var categoryHeading: TextView
    private lateinit var descriptionView: TextView
    private var offset = 0
    private var loading = false
    private var endReached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categorySlug = intent.getStringExtra(EXTRA_CATEGORY_SLUG).orEmpty()
        categoryTitle = intent.getStringExtra(EXTRA_CATEGORY_TITLE).orEmpty().ifBlank { "Anime category" }
        categoryDescription = intent.getStringExtra(EXTRA_CATEGORY_DESCRIPTION).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
            setOnClickListener { finish() }
        }
        FocusEffectUtil.applyFocusListener(back)
        header.addView(back)
        categoryHeading = TextView(this).apply {
            text = categoryTitle
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(Color.WHITE)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(categoryHeading)
        root.addView(header)

        descriptionView = TextView(this).apply {
            text = categoryDescription
            textSize = 15.5f
            gravity = Gravity.START
            setTextColor(Color.rgb(190, 190, 190))
            setPadding(dp(16), 0, dp(16), dp(16))
            isVisible = categoryDescription.isNotBlank()
        }
        root.addView(descriptionView)

        progress = ProgressBar(this).apply { isVisible = false }
        root.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isVisible = false
        }
        root.addView(status)
        adapter = KitsuAnimeAdapter { item ->
            val anilistId = item.anilistId
            if (anilistId == null || anilistId <= 0) {
                Toast.makeText(this, "AniList mapping unavailable for this title", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(android.content.Intent(this, MediaDetailsActivity::class.java)
                    .putExtra("mediaId", anilistId))
            }
        }
        recycler = RecyclerView(this).apply {
            setBackgroundColor(Color.BLACK)
            val spans = if (isTvDevice(this@KitsuCategoryResultsActivity)) 5 else 3
            layoutManager = GridLayoutManager(this@KitsuCategoryResultsActivity, spans)
            adapter = this@KitsuCategoryResultsActivity.adapter
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(20))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && !recyclerView.canScrollVertically(1) && !loading && !endReached) {
                        loadNextPage()
                    }
                }
            })
        }
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        if (categorySlug.isBlank()) {
            showStatus("This category is unavailable.")
            endReached = true
        } else {
            loadNextPage()
        }
    }

    private fun loadNextPage() {
        if (loading || endReached) return
        loading = true
        progress.isVisible = true
        status.isVisible = adapter.itemCount == 0
        if (adapter.itemCount == 0) status.text = "Loading anime…"
        lifecycleScope.launch {
            var added = false
            var attempts = 0
            do {

                if (resolvedCategorySlug == null && offset == 0 && adapter.itemCount == 0) {
                    val matchedCategory = withContext(Dispatchers.IO) {
                        val categories = Kitsu.allCategories()
                        val requestedSlug = normalizeCategoryKey(categorySlug)
                        val requestedTitle = normalizeCategoryKey(categoryTitle)
                        categories.firstOrNull {
                            it.slug.equals(categorySlug, ignoreCase = true)
                        } ?: categories.firstOrNull {
                            normalizeCategoryKey(it.title) == requestedTitle
                        } ?: categories.firstOrNull {
                            normalizeCategoryKey(it.slug) == requestedSlug
                        }
                    }
                    resolvedCategorySlug = matchedCategory?.slug ?: categorySlug
                    matchedCategory?.let { category ->
                        categoryHeading.text = "$categoryTitle (${category.mediaCount})"
                        if (categoryDescription.isBlank()) {
                            categoryDescription = category.description
                                ?.replace(Regex("<[^>]*>"), " ")
                                ?.replace(Regex("\\s+"), " ")
                                ?.trim().orEmpty()
                            descriptionView.text = categoryDescription
                            descriptionView.isVisible = categoryDescription.isNotBlank()
                        }
                    }
                }
                val page = withContext(Dispatchers.IO) {
                    Kitsu.categoryAnime(resolvedCategorySlug ?: categorySlug, offset, PAGE_SIZE)
                }
                if (!page.success) {
                    if (adapter.itemCount == 0) {
                        showStatus("Couldn't load this category. Tap to retry.")
                        status.setOnClickListener {
                            endReached = false
                            status.isVisible = false
                            loadNextPage()
                        }
                    }
                    endReached = true
                    break
                }
                offset += page.rawCount
                endReached = !page.hasMore
                if (page.items.isNotEmpty()) {

                    val anilistIds = page.items.mapNotNull { it.anilistId }.distinct()
                    val covers = if (anilistIds.isEmpty()) {
                        emptyMap()
                    } else {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                Anilist.query.getMediaList(anilistIds).orEmpty()
                                    .mapNotNull { media -> media.cover?.let { media.id to it } }
                                    .toMap()
                            }
                        }.getOrDefault(emptyMap())
                    }
                    val itemsWithAniListPosters = page.items.map { item ->
                        item.copy(posterUrl = item.anilistId?.let(covers::get))
                    }
                    adapter.append(itemsWithAniListPosters)
                    added = true
                }
                attempts++
            } while (!added && !endReached && attempts < 3)

            if (adapter.itemCount == 0 && endReached) {
                showStatus("No mapped anime found in this category.")
            } else if (adapter.itemCount > 0) {
                status.isVisible = false
            }
            progress.isVisible = false
            loading = false
        }
    }

    private fun normalizeCategoryKey(value: String): String =
        value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun showStatus(message: String) {
        status.text = message
        status.isVisible = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class KitsuAnimeAdapter(
    private val onClick: (KitsuDiscoveryItem) -> Unit,
) : RecyclerView.Adapter<KitsuAnimeAdapter.Holder>() {
    private val items = ArrayList<KitsuDiscoveryItem>()

    fun append(next: List<KitsuDiscoveryItem>) {
        val existing = items.mapTo(HashSet()) { it.id }
        val unique = next.filter { existing.add(it.id) }
        if (unique.isEmpty()) return
        val start = items.size
        items.addAll(unique)
        notifyItemRangeInserted(start, unique.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.BLACK)
                    setStroke(dp(context, 2), context.getThemeColor(com.google.android.material.R.attr.colorSecondary))
                    cornerRadius = dp(context, 14).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.BLACK)
                    setStroke(dp(context, 1), Color.rgb(42, 42, 42))
                    cornerRadius = dp(context, 14).toFloat()
                })
            }
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
            }
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_editorial_artwork_unavailable)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, if (isTvDevice(context)) 210 else 170)
            )
            contentDescription = "Anime poster"
        }
        val title = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(context, 3), dp(context, 7), dp(context, 3), 0)
        }
        card.addView(poster)
        card.addView(title)
        FocusEffectUtil.applyFocusListener(card)
        return Holder(card, poster, title)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.poster.setImageResource(R.drawable.ic_editorial_artwork_unavailable)
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { holder.poster.loadImage(it) }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.contentDescription = item.title
    }

    override fun getItemCount(): Int = items.size

    class Holder(root: View, val poster: ImageView, val title: TextView) : RecyclerView.ViewHolder(root)

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

class KitsuTrailerPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_VIDEO_KEY = "trailer_video_key"
        const val EXTRA_TITLE = "trailer_title"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_VIDEO_KEY).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Trailer" }
        if (key.isBlank()) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
            setOnClickListener { finish() }
        }
        FocusEffectUtil.applyFocusListener(back)
        header.addView(back)
        header.addView(TextView(this).apply {
            text = title
            textSize = 16f
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(header)
        val player = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        webView = player
        root.addView(player, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        val safeKey = android.net.Uri.encode(key)
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"></head>
            <body style="margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh">
            <iframe width="100%" height="100%" src="https://www.youtube.com/embed/$safeKey?autoplay=1&controls=1&playsinline=1&rel=0"
              title="Trailer player" frameborder="0"
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
              allowfullscreen></iframe></body></html>
        """.trimIndent()
        player.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
