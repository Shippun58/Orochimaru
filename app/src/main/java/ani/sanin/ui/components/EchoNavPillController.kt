package ani.sanin.ui.components

import ani.sanin.settings.AnimUtils

import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager
import ani.sanin.util.NavPillCustomizer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

private const val TAG = "EchoNavPill"

class EchoNavPillController(
    private val container: FrameLayout,
    private val backgroundPill: View?,
    private val row: LinearLayout?,
    private val pillList: LinearLayout?,
    private val pills: List<ImageButton>,
    private val labels: List<String>,
    private val onSearch: (() -> Unit)?,
    private val floatToStartOnCollapse: Boolean = false
) {
    private val density = container.resources.displayMetrics.density
    private val thresholdPx = 52f * density
    private val searchGapPx = (8f * density).roundToInt()
    private val tightPaddingPx = (3f * density).roundToInt()

    private val labelGapPx = (2f * density).roundToInt()

    private val indicatorInsetPx = (3f * density).roundToInt()
    private val pillH = NavPillCustomizer.getHeightDp()
    private val iconRadiusPx = pillH * density / 2f
    private val indicatorRadiusPx = pillH * density / 2f

    private val indicatorColor: Int
        get() = if (NavPillCustomizer.isDarkTheme()) 0x40FFFFFF.toInt() else 0x33000000

    private val surfaceColor: Int get() = NavPillCustomizer.getPillFillColor()

    private var selectedIndex = 0
    private var collapsed = false
    private var accumulated = 0f
    private var savedPillPadding: IntArray? = null
    private var morphVersion = 0
    private var containerWidthAnimator: ValueAnimator? = null
    private var containerXAnimator: ValueAnimator? = null
    private var indicatorAnimator: ValueAnimator? = null
    private val scrollViews = LinkedHashSet<View>()
    private val trackedLists = mutableSetOf<RecyclerView>()
    private val lastScrollY = HashMap<View, Int>()
    private var observedRoot: View? = null
    private var recollect: Runnable? = null
    private var geometryWatcher: View.OnLayoutChangeListener? = null
    private var repositioning = false
    private var lastAppliedKey: String? = null
    
    
    
    private var indicatorAnimating = false
    private var slideAnimating = false
    
    
    
    
    
    
    
    private var positionDefers = 0

    private var widthAnimating = false
    private val treeScrollListener = ViewTreeObserver.OnScrollChangedListener {
        dispatchScrollDeltas()
    }

    val isCollapsed: Boolean get() = collapsed
    val isScrollCapable: Boolean get() = pillList != null

    private val springInterpolator = object : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            return 1f - exp(-t * 5f) * cos(t * 9f)
        }
    }

    private val indicator: View by lazy {
        View(container.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = indicatorRadiusPx
                setColor(indicatorColor)
            }
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private val labelView: TextView? by lazy {
        if (labels.isEmpty()) return@lazy null

        if (!NavPillCustomizer.getShowLabel()) return@lazy null

        if (isVerticalRail()) return@lazy null
        TextView(container.context).apply {
            setTextColor(NavPillCustomizer.getIconColor())
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, container.resources.displayMetrics
            )
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                container.context, R.font.poppins_semi_bold
            )
            includeFontPadding = false
            isSingleLine = true

            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = labelGapPx
            }
        }
    }

    private var labelWidthPx = 0

    private val searchButton: ImageButton? by lazy {
        if (row == null || onSearch == null) return@lazy null
        ImageButton(container.context).apply {
            setImageResource(R.drawable.ic_round_search_24)
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = container.context.getString(R.string.search)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { onSearch() }
        }
    }

    private fun applyIconSettingsToSearch() {
        val sb = searchButton ?: return
        val sizePx = (NavPillCustomizer.getHeightDp() * density).roundToInt()
        val lp = sb.layoutParams
        if (lp != null && (lp.width != sizePx || lp.height != sizePx)) {
            lp.width = sizePx
            lp.height = sizePx
            sb.layoutParams = lp
        }
        NavPillCustomizer.applyIconSettings(sb, NavPillCustomizer.getIconPaddingPx(sizePx))
    }

    fun attach(initialIndex: Int) {
        selectedIndex = initialIndex

        normaliseContainerBox()
        installGeometryWatchers()
        if (indicator.parent == null) {
            container.addView(indicator, 1.coerceIn(0, container.childCount))
        }
        labelView?.let { lbl ->
            if (lbl.parent == null) {

                val anchor = pillList?.indexOfChild(pills.getOrNull(selectedIndex)) ?: -1
                if (pillList != null && anchor >= 0) {
                    pillList.addView(lbl, (anchor + 1).coerceIn(0, pillList.childCount))
                } else {
                    pillList?.addView(lbl)
                }
            }
        }
        searchButton?.let { sb ->
            if (sb.parent == null) {
                val pillSize = (pillH * density).roundToInt()
                val lp = LinearLayout.LayoutParams(pillSize, pillSize)
                lp.leftMargin = searchGapPx
                row?.addView(sb, lp)

                applyIconSettingsToSearch()
            }
        }

        syncGlass()
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in pills.indices) return
        val oldIndex = selectedIndex
        selectedIndex = index

        observedRoot?.let { collectScrollables(it, 0) }
        if (collapsed) {
            if (index != oldIndex) refreshCollapsedSelection(oldIndex, index)
            return
        }
        if (index == oldIndex) {
            positionLabelOverlay()
            repinContainerWidth()

            container.post { positionIndicator(index, animate) }
        } else {
            container.post {
                positionLabelOverlay()
                repinContainerWidth()

                container.post { positionIndicator(index, animate) }
            }
        }
    }

    private fun settledExpandedWidthPx(): Int {
        val pl = pillList ?: return container.measuredWidth

        val scaled = pills.map { it.scaleX }
        pills.forEach { it.scaleX = 1f }

        val sb = searchButton
        val sbVisible = sb?.visibility
        sb?.visibility = View.GONE
        try {
            pl.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val padding = container.paddingLeft + container.paddingRight
            return (pl.measuredWidth + padding).coerceAtLeast(1)
        } finally {
            pills.forEachIndexed { i, p -> p.scaleX = scaled.getOrElse(i) { 1f } }
            if (sb != null && sbVisible != null) sb.visibility = sbVisible
        }
    }

    private fun isVerticalRail(): Boolean {
        pillList?.let { return it.orientation == LinearLayout.VERTICAL }

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is LinearLayout && pills.any { it.parent === child }) {
                return child.orientation == LinearLayout.VERTICAL
            }
        }

        return true
    }

    private fun repinContainerWidth() {
        if (collapsed) return

        if (isVerticalRail()) return

        val lp = container.layoutParams ?: return
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            container.layoutParams = lp
        }
    }

    private fun fixedChildWidthPx(parent: LinearLayout, exclude: View): Int {
        var total = parent.paddingLeft + parent.paddingRight
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === exclude) continue
            val lp = child.layoutParams
            val width = if (lp != null && lp.width > 0) lp.width else child.width
            val margin = if (lp is LinearLayout.LayoutParams) lp.leftMargin else 0
            total += width + margin
        }
        return total
    }

    fun setCollapsed(collapsed: Boolean) {
        if (!isScrollCapable) return

        if (collapsed && !NavPillCustomizer.getShrinkOnScroll()) return
        if (this.collapsed == collapsed) return
        if (collapsed) shrink() else expand()
    }

    fun startScrollTracking(root: View) {
        if (!isScrollCapable) return
        if (observedRoot === root) {

            collectScrollables(root, 0)
            return
        }
        stopScrollTracking()
        observedRoot = root
        root.viewTreeObserver.addOnScrollChangedListener(treeScrollListener)
        collectScrollables(root, 0)
        root.postDelayed({ collectScrollables(root, 0) }, 150)
        root.postDelayed({ collectScrollables(root, 0) }, 450)
        root.postDelayed({ collectScrollables(root, 0) }, 1000)
        root.postDelayed({ collectScrollables(root, 0) }, 2000)

        recollect?.let { root.removeCallbacks(it) }
        val rewalk = object : Runnable {
            override fun run() {
                if (observedRoot !== root) return
                collectScrollables(root, 0)
                root.postDelayed(this, 400)
            }
        }
        recollect = rewalk
        root.postDelayed(rewalk, 400)
    }

    fun stopScrollTracking() {
        val root = observedRoot ?: return
        recollect?.let { root.removeCallbacks(it) }
        recollect = null
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnScrollChangedListener(treeScrollListener)
        observedRoot = null
        scrollViews.clear()
        lastScrollY.clear()
        trackedLists.clear()
    }

    private fun collectScrollables(v: View, depth: Int) {

        if (depth > 30) return
        when {
            v is RecyclerView -> if (trackedLists.add(v)) {
                v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        forwardScroll(rv, dy)
                    }
                })
            }

            v is NestedScrollView || v is android.widget.ScrollView -> {
                scrollViews.add(v)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectScrollables(v.getChildAt(i), depth + 1)
        }
    }

    private fun dispatchScrollDeltas() {
        for (view in scrollViews) {
            if (!view.isShown) {

                lastScrollY.remove(view)
                continue
            }
            val y = view.scrollY
            val previous = lastScrollY.put(view, y) ?: y
            forwardScroll(view, y - previous)
        }
    }

    private fun forwardScroll(scroller: View, dy: Int) {
        if (dy == 0) return

        if (dy > 0 && !scroller.canScrollVertically(1)) return
        if (dy < 0 && !scroller.canScrollVertically(-1)) return
        onScroll(dy.toFloat())
    }

    fun onScroll(dyPixels: Float) {
        if (!isScrollCapable) return
        if (dyPixels == 0f) return

        if (!NavPillCustomizer.getShrinkOnScroll()) {

            accumulated = 0f
            return
        }

        accumulated += dyPixels
        if (collapsed) {
            if (accumulated <= -thresholdPx) {
                accumulated = 0f
                setCollapsed(false)
            }
        } else {
            if (accumulated >= thresholdPx) {
                accumulated = 0f
                setCollapsed(true)
            }
        }

        accumulated = accumulated.coerceIn(-thresholdPx, thresholdPx)
    }

    fun refreshLabelTint() {
        labelView?.setTextColor(NavPillCustomizer.getIconColor())
    }

    fun syncGlass() {

        applyIconSettingsToSearch()

        syncLabelAttachment()

        if (collapsed && isScrollCapable && !NavPillCustomizer.getShrinkOnScroll()) {
            expand()
            return
        }
        if (!isScrollCapable) {
            applySharedContainerGlass()
            return
        }
        if (collapsed) applyCollapsedGlass() else applyExpandedGlass()
        if (collapsed) {
            val want = container.measuredWidth.coerceAtMost(maxContainerWidthPx())
            val lp = container.layoutParams
            if (want > 0 && lp.width != want) {
                lp.width = want
                container.layoutParams = lp
            }
        } else {
            positionLabelOverlay()
            repinContainerWidth()

            container.post {
                if (collapsed) return@post
                positionIndicator(selectedIndex, animate = false)
            }
        }
    }

    private fun applySharedContainerGlass() {
        if (GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)) {
            GlassEffectManager.applyGlass(
                container,
                GlassComponent.NavPills,
                NavPillCustomizer.getCornerRadiusDp().toFloat()
            )
        } else {

            GlassEffectManager.removeGlass(container)
            NavPillCustomizer.applyPillBackground(container)
        }
    }

    private fun applyCollapsedGlass() {

        GlassEffectManager.removeGlass(container)
        container.background = null

        container.clipToOutline = false
        listOfNotNull(pills.getOrNull(selectedIndex), searchButton)
            .forEach { applyButtonSurface(it) }
    }

    private fun applyExpandedGlass() {
        pills.forEach {
            GlassEffectManager.removeGlass(it)
            it.setBackgroundResource(android.R.color.transparent)
        }
        searchButton?.let {
            GlassEffectManager.removeGlass(it)
            it.setBackgroundResource(android.R.color.transparent)
        }

        container.clipToOutline = !isVerticalRail()
        applySharedContainerGlass()
    }

    private fun applyButtonSurface(btn: ImageButton) {
        GlassEffectManager.removeGlass(btn)
        if (GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)) {
            GlassEffectManager.applyGlass(btn, GlassComponent.NavPills, pillH / 2f)
        } else {
            btn.updateSurface(surfaceColor)
        }
    }

    private fun normaliseContainerBox() {
        val list = pillList ?: return
        
        
        
        
        
        
        
        
        val lp = container.layoutParams
        if (lp != null && !isVerticalRail()) {
            val contentH = if (list.height > 0) list.height else {
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                list.measuredHeight
            }
            if (contentH > 0 && lp.height != contentH) {
                val heightWas = lp.height
                lp.height = contentH
                container.layoutParams = lp
                Log.i(
                    TAG,
                    "box: pre-pinned container height $heightWas -> $contentH " +
                        "(container ${container.width}x${container.height}, list ${list.width}x${list.height})"
                )
            }
        } else {
            val heightWas = lp?.height
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                container.layoutParams = lp
                Log.i(
                    TAG,
                    "box: re-pinned container height ${heightWas} -> WRAP_CONTENT " +
                        "(container ${container.width}x${container.height}, list ${pillList?.width}x${pillList?.height})"
                )
            }
        }
        val listLp = list.layoutParams ?: return
        if (listLp is FrameLayout.LayoutParams && listLp.gravity != android.view.Gravity.CENTER) {
            val was = listLp.gravity
            listLp.gravity = android.view.Gravity.CENTER
            list.layoutParams = listLp
            Log.i(TAG, "box: centred pill_list, gravity $was -> CENTER")
        }
    }

    private fun pinContainerHeightToContent() {
        val list = pillList ?: return
        val lp = container.layoutParams ?: return
        val contentH = list.height
        if (contentH <= 0) return

        val declared = if (lp.height >= 0) lp.height else -1
        val grown = container.height > contentH + (4f * density).roundToInt()

        val stale = declared > 0 && declared != contentH
        if (!grown && !stale) return
        val was = "${lp.height} (measured ${container.height})"
        lp.height = contentH
        container.layoutParams = lp
        Log.i(TAG, "box: pinned container height $was -> $contentH (pill_list height)")
    }

    private fun installGeometryWatchers() {
        if (geometryWatcher != null) return
        val watcher = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            onRailLaidOut(v)
        }
        geometryWatcher = watcher
        container.addOnLayoutChangeListener(watcher)
        pillList?.addOnLayoutChangeListener(watcher)
    }

    private fun onRailLaidOut(v: View) {
        val list = pillList
        val contentH = list?.height ?: 0
        val slack = (4f * density).roundToInt()
        val stretched = contentH > 0 && v.height > contentH + slack
        val bg = backgroundPill
        Log.i(
            TAG,
            "laidOut: container=${v.width}x${v.height} lpW=${v.layoutParams?.width} lpH=${v.layoutParams?.height} " +
                "list=${list?.width}x${list?.height} listTop=${list?.top} contentH=$contentH stretched=$stretched " +
                "bg=${bg?.javaClass?.simpleName} bgVis=${bg?.visibility} bgSize=${bg?.width}x${bg?.height} bgAlpha=${bg?.alpha} " +
                "containerBg=${container.background?.javaClass?.simpleName} collapsed=$collapsed"
        )

        if (stretched) {
            val kids = (0 until container.childCount).joinToString("; ") { i ->
                val c = container.getChildAt(i)
                val clp = c.layoutParams as? ViewGroup.LayoutParams
                "[$i]${c.javaClass.simpleName} vis=${c.visibility} " +
                    "lp=${clp?.width}x${clp?.height} m=${c.width}x${c.height} " +
                    "top=${c.top} bot=${c.bottom} " +
                    "bgIntr=${c.background?.intrinsicWidth}x${c.background?.intrinsicHeight}"
            }
            val parentLp = container.layoutParams
            Log.i(TAG, "stretched-children: $kids || containerLp=${parentLp?.width}x${parentLp?.height}")
        }
        if (stretched) {
            normaliseContainerBox()
            pinContainerHeightToContent()
        }

        
        
        
        
        
        if (isScrollCapable && floatToStartOnCollapse && !slideAnimating) {
            container.translationX = containerXTarget(collapsed)
        }
        if (collapsed || !isScrollCapable || widthAnimating || indicatorAnimating || slideAnimating) return
        repositionIndicatorIfMoved()
    }

    private fun indicatorGeometryKey(): String {
        val pill = pills.getOrNull(selectedIndex)
        val lbl = labelView
        val lp = lbl?.layoutParams as? LinearLayout.LayoutParams
        return "${pill?.left}x${pill?.width}|${lbl?.visibility}|$labelWidthPx|" +
            "${lp?.leftMargin}|${container.width}|${pillList?.width}|${pill?.height}"
    }

    private fun repositionIndicatorIfMoved() {
        if (repositioning) return
        val key = indicatorGeometryKey()
        if (key == lastAppliedKey) return
        val from = lastAppliedKey
        lastAppliedKey = key
        Log.i(TAG, "reposition: geometry moved [$from] -> [$key]")
        repositioning = true
        positionLabelOverlay()
        container.post {
            repositioning = false
            if (collapsed) return@post
            positionIndicator(selectedIndex, animate = false)
        }
    }

    private fun applyCollapseGravity(collapsedNow: Boolean) {
        normaliseContainerBox()
        if (!floatToStartOnCollapse) return
        
        
        
        
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val wanted = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        if (lp.gravity != wanted) {
            lp.gravity = wanted
            container.layoutParams = lp
        }
        slideContainerX(collapsedNow)
    }

    private fun containerXTarget(collapsedNow: Boolean): Float {
        if (!collapsedNow) return 0f
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return 0f
        val parent = container.parent as? ViewGroup ?: return 0f
        val inner = (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(0)
        val centeredLeft = parent.paddingLeft + ((inner - container.width).coerceAtLeast(0)) / 2f
        return lp.leftMargin - centeredLeft
    }

    private fun slideContainerX(collapsedNow: Boolean) {
        containerXAnimator?.cancel()
        val from = container.translationX
        if (!motionOn()) {
            container.translationX = containerXTarget(collapsedNow)
            return
        }
        slideAnimating = true
        containerXAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val target = containerXTarget(collapsedNow)
                container.translationX = from + (target - from) * t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    slideAnimating = false
                }
            })
            start()
        }
    }

    private fun shrink() {
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        morphVersion++
        val version = morphVersion
        containerWidthAnimator?.cancel()
        widthAnimating = false
        collapsed = true
        applyCollapseGravity(true)

        labelView?.let { lbl ->
            fadeAlpha(lbl, 0f)
            lbl.visibility = View.INVISIBLE

            lbl.layoutParams = lbl.layoutParams.apply {
                width = 0
                height = 0
                if (this is LinearLayout.LayoutParams) {
                    leftMargin = 0
                    topMargin = 0
                }
            }
            labelWidthPx = 0
        }
        fadeAlpha(indicator, 0f)
        backgroundPill?.let { fadeAlpha(it, 0f) }
        others.forEach { fadeOut(it) }

        searchButton?.let { sb ->
            sb.visibility = View.VISIBLE
            sb.alpha = 0f
            sb.scaleX = 0.3f
            sb.scaleY = 0.3f
        }

        applyCollapsedGlass()
        savedPillPadding = intArrayOf(pl.paddingLeft, pl.paddingTop, pl.paddingRight, pl.paddingBottom)
        if (pl.orientation == LinearLayout.HORIZONTAL) {
            pl.setPadding(tightPaddingPx, pl.paddingTop, tightPaddingPx, pl.paddingBottom)
        }

        container.postDelayed({
            if (version != morphVersion || !collapsed) return@postDelayed
            labelView?.let { it.visibility = View.GONE }
            others.forEach { it.visibility = View.GONE }
            pl.requestLayout()
            pl.post {
                if (version != morphVersion || !collapsed) return@post

                val target = container.measuredWidth.coerceAtMost(maxContainerWidthPx())
                animateContainerWidth(startWidth, target) {
                    if (version != morphVersion || !collapsed) return@animateContainerWidth
                    searchButton?.animate()
                        ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(if (motionOn()) 180 else 0)?.start()
                }
            }
        }, 170)
    }

    private fun expand() {
        if (savedPillPadding == null) return
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        morphVersion++
        val version = morphVersion
        containerWidthAnimator?.cancel()
        widthAnimating = false
        collapsed = false
        applyCollapseGravity(false)

        others.forEach { it.visibility = View.VISIBLE }
        others.forEach { it.alpha = 0f }
        others.forEach { it.scaleX = 0.3f }
        others.forEach { it.scaleY = 0.3f }

        searchButton?.let { sb ->
            sb.animate().cancel()
            sb.visibility = View.GONE
            sb.alpha = 0f
            sb.scaleX = 1f
            sb.scaleY = 1f
        }
        labelView?.let { lbl ->
            lbl.visibility = View.VISIBLE
            lbl.alpha = 0f
        }
        backgroundPill?.let { it.alpha = 0f }
        savedPillPadding?.let { p ->
            pl.setPadding(p[0], p[1], p[2], p[3])
        }
        savedPillPadding = null
        applyExpandedGlass()

        indicator.alpha = 1f

        others.forEach { it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (motionOn()) 200 else 0).start() }
        labelView?.let { lbl -> lbl.animate().alpha(1f).setDuration(if (motionOn()) 200 else 0).start() }
        backgroundPill?.animate()?.alpha(1f)?.setDuration(if (motionOn()) 200 else 0)?.start()

        container.post {
            if (version != morphVersion || collapsed) return@post
            positionLabelOverlay()
            pl.requestLayout()

            repinContainerWidth()
            pl.post {
                if (version != morphVersion || collapsed) return@post
                val targetWidth = settledExpandedWidthPx()
                animateContainerWidth(startWidth, targetWidth) {
                    if (version != morphVersion || collapsed) return@animateContainerWidth
                    positionLabelOverlay()

                    repinContainerWidth()

                    container.post {
                        if (version != morphVersion || collapsed) return@post
                        positionIndicator(selectedIndex, animate = false)
                    }

                }
            }
        }
    }

    private fun labelLeftMarginPx(pillSize: Int): Int {
        val glyph = NavPillCustomizer.getIconDrawnSizePx(pillSize)
        val iconRight = pillSize / 2f + glyph / 2f
        val margin = iconRight + labelGapPx - pillSize
        return margin.coerceIn(iconRight - pillSize, 0f).roundToInt()
    }

    private fun syncLabelAttachment() {
        val parent = pillList ?: return
        val lbl = labelView
        val attached = lbl?.parent === parent
        if (NavPillCustomizer.getShowLabel()) {
            if (lbl == null || attached) return
            val anchor = parent.indexOfChild(pills.getOrNull(selectedIndex))
            if (anchor >= 0) {
                parent.addView(lbl, (anchor + 1).coerceIn(0, parent.childCount))
            } else {
                parent.addView(lbl)
            }
            return
        }

        if (lbl != null && attached) parent.removeView(lbl)
    }

    private fun positionLabelOverlay() {
        val lbl = labelView ?: return
        val parent = lbl.parent as? LinearLayout ?: return
        val pill = pills.getOrNull(selectedIndex) ?: return
        val text = labels.getOrNull(selectedIndex) ?: ""

        val curLp = lbl.layoutParams as? LinearLayout.LayoutParams
        val curPillSize = if (pill.height > 0) pill.height else (pillH * density).roundToInt()
        val curAnchor = parent.indexOfChild(pill)
        if (curLp != null && labelWidthPx > 0 && !collapsed &&
            curAnchor >= 0 && parent.indexOfChild(lbl) == curAnchor + 1 &&
            lbl.text.toString() == text && lbl.visibility == View.VISIBLE &&
            curLp.leftMargin == labelLeftMarginPx(curPillSize) &&
            curLp.height == curPillSize && curLp.width == labelWidthPx
        ) return
        lbl.text = text

        lbl.setTextColor(NavPillCustomizer.getIconColor())

        if (!collapsed) lbl.visibility = View.VISIBLE

        val wantAnchor = parent.indexOfChild(pill)
        if (wantAnchor >= 0 && parent.indexOfChild(lbl) != wantAnchor + 1) {

            parent.removeView(lbl)
            val anchor = parent.indexOfChild(pill)
            if (anchor >= 0) {

                parent.addView(lbl, (anchor + 1).coerceIn(0, parent.childCount))
            } else {
                parent.addView(lbl)
            }
        }

        val pillSize = if (pill.height > 0) pill.height else (pillH * density).roundToInt()
        val lp = lbl.layoutParams

        val leftMargin = labelLeftMarginPx(pillSize)
        if (lp is LinearLayout.LayoutParams) {
            lp.leftMargin = leftMargin
            lp.topMargin = 0
        }

        val roomForText = (maxContainerWidthPx() - fixedChildWidthPx(parent, lbl) - leftMargin)
            .coerceAtLeast(0)
        lbl.measure(
            View.MeasureSpec.makeMeasureSpec(roomForText, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(pillSize, View.MeasureSpec.EXACTLY)
        )
        lp.width = lbl.measuredWidth
        lp.height = pillSize
        lbl.layoutParams = lp
        labelWidthPx = lbl.measuredWidth
        parent.requestLayout()
    }

    private fun maxContainerWidthPx(): Int {
        val screen = container.resources.displayMetrics.widthPixels
        val lp = container.layoutParams
        val margins = if (lp is ViewGroup.MarginLayoutParams) lp.leftMargin + lp.rightMargin else 0

        val insets = ViewCompat.getRootWindowInsets(container)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.left ?: 0
        return (screen - margins - insets).coerceAtLeast((pillH * density).roundToInt() * 2)
    }

    private fun refreshCollapsedSelection(from: Int, to: Int) {
        val old = pills.getOrNull(from)
        val new = pills.getOrNull(to) ?: return
        old?.let {
            GlassEffectManager.removeGlass(it)
            it.visibility = View.GONE
            it.setBackgroundResource(android.R.color.transparent)
        }
        new.visibility = View.VISIBLE
        new.alpha = 1f
        new.scaleX = 1f
        new.scaleY = 1f
        applyButtonSurface(new)
    }

    private fun positionIndicator(index: Int, animate: Boolean) {
        val pill = pills.getOrNull(index) ?: pills.firstOrNull() ?: return

        if (isVerticalRail()) {
            indicatorAnimator?.cancel()
            indicator.visibility = View.GONE
            return
        }
        
        
        
        
        
        
        fun geometryValid(): Boolean {
            val contentH = pillList?.height ?: 0
            val slack = (8f * density).roundToInt()
            return container.isLaidOut &&
                container.width > 0 &&
                (contentH <= 0 || container.height <= contentH + slack)
        }
        if (!geometryValid()) {
            deferIndicatorDemand(index, animate, container)
            return
        }
        if (pill.width <= 0) {
            deferIndicatorDemand(index, animate, pill)
            return
        }
        val cLoc = IntArray(2)
        val pLoc = IntArray(2)
        container.getLocationInWindow(cLoc)
        pill.getLocationInWindow(pLoc)
        val vx = (pLoc[0] - cLoc[0]).toFloat()

        val vertical = isVerticalRail()
        val stretch = if (vertical) 0 else labelView?.let { lbl ->
            if (lbl.visibility == View.VISIBLE && !collapsed) {

                val margin = (lbl.layoutParams as? LinearLayout.LayoutParams)?.leftMargin ?: 0
                (margin + labelWidthPx).coerceAtLeast(0)
            } else 0
        } ?: 0
        val width = pill.width + stretch

        val inset = if (vertical) 0 else indicatorInsetPx
        val height = (pill.height - inset * 2).coerceAtLeast(1)
        val vy = (pLoc[1] - cLoc[1]).toFloat() + inset
        Log.i(
            TAG,
            "indicator[$index]: animate=$animate pill=${pill.width}x${pill.height} at(${(pLoc[0] - cLoc[0])},${(pLoc[1] - cLoc[1])}) " +
                "stretch=$stretch margin=${(labelView?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin} " +
                "labelW=$labelWidthPx labelVis=${labelView?.visibility} -> w=$width h=$height vx=$vx vy=$vy " +
                "container=${container.width}x${container.height} list=${pillList?.width}x${pillList?.height}"
        )
        if (animate) {
            animateIndicator(pill, width, height)
        } else {
            indicator.translationX = vx
            indicator.translationY = vy
            val lp = indicator.layoutParams
            lp.width = width
            lp.height = height
            indicator.layoutParams = lp
            indicator.visibility = View.VISIBLE
        }
    }

    
    
    
    
    
    private fun deferIndicatorDemand(index: Int, animate: Boolean, watchView: View) {
        indicator.visibility = View.INVISIBLE
        lastAppliedKey = null
        if (positionDefers >= 4) return
        positionDefers++
        watchView.post {
            positionDefers--
            if (!collapsed && selectedIndex == index) positionIndicator(index, animate)
        }
    }

    
    
    
    
    private fun animateIndicator(pill: View, width: Int, height: Int) {
        if (!motionOn()) {
            val cLoc = IntArray(2)
            val pLoc = IntArray(2)
            container.getLocationInWindow(cLoc)
            pill.getLocationInWindow(pLoc)
            indicator.translationX = (pLoc[0] - cLoc[0]).toFloat()
            indicator.translationY = (pLoc[1] - cLoc[1]).toFloat() + indicatorInsetPx
            val lp0 = indicator.layoutParams
            lp0.width = width
            lp0.height = height
            indicator.layoutParams = lp0
            indicator.visibility = View.VISIBLE
            return
        }
        indicatorAnimator?.cancel()
        indicator.visibility = View.VISIBLE
        indicatorAnimating = true
        val startX = indicator.translationX
        val startY = indicator.translationY
        val startW = indicator.layoutParams.width
        val startH = indicator.layoutParams.height
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = springInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val cLoc = IntArray(2)
                val pLoc = IntArray(2)
                container.getLocationInWindow(cLoc)
                pill.getLocationInWindow(pLoc)
                val tx = (pLoc[0] - cLoc[0]).toFloat()
                val ty = (pLoc[1] - cLoc[1]).toFloat() + indicatorInsetPx
                indicator.translationX = startX + (tx - startX) * t
                indicator.translationY = startY + (ty - startY) * t
                val lp = indicator.layoutParams
                lp.width = startW + ((width - startW) * t).roundToInt()
                lp.height = startH + ((height - startH) * t).roundToInt()
                indicator.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    indicatorAnimating = false
                    
                    
                    lastAppliedKey = null
                }
            })
            start()
        }
    }

    private fun animateContainerWidth(from: Int, to: Int, done: () -> Unit) {
        if (!motionOn()) { applyContainerWidth(to); widthAnimating = false; done(); return }
        containerWidthAnimator?.cancel()
        widthAnimating = true
        applyContainerWidth(from)
        containerWidthAnimator = ValueAnimator.ofInt(from.coerceAtLeast(1), to.coerceAtLeast(1)).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> applyContainerWidth(anim.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    widthAnimating = false
                    applyContainerWidth(to)
                    done()
                }
            })
            start()
        }
    }

    private fun applyContainerWidth(width: Int) {
        val lp = container.layoutParams ?: return
        lp.width = width.coerceAtLeast(1)
        container.layoutParams = lp
    }

    private fun motionOn(): Boolean = AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NavRailAnimations)

    private fun fadeOut(view: View) {
        view.animate().cancel()
        view.animate()
            .alpha(0f).scaleX(0.3f).scaleY(0.3f)
            .setDuration(if (motionOn()) 170 else 0)
            .start()
    }

    private fun fadeAlpha(view: View, to: Float) {
        view.animate().cancel()
        view.animate().alpha(to).setDuration(if (motionOn()) 170 else 0).start()
    }

    private fun ImageButton.updateSurface(color: Int) {
        background = GradientDrawable().apply {
            cornerRadius = iconRadiusPx
            setColor(color)
        }
    }
}