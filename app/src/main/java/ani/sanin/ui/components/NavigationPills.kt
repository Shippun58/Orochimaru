package ani.sanin.ui.components
import ani.sanin.settings.AnimUtils
import androidx.compose.animation.core.snap

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ani.sanin.R
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager

private val TAB_ORDER = listOf("home", "explore", "discovery", "library")
private val TAB_ICONS = mapOf(
    "home" to R.drawable.ic_round_home_24,
    "explore" to R.drawable.ic_round_media_24,
    "discovery" to R.drawable.ic_round_filter_list_24,
    "library" to R.drawable.ic_library_shelves_24
)
private val TAB_LABELS = mapOf(
    "home" to "Home",
    "explore" to "Explore",
    "discovery" to "Discovery",
    "library" to "Library"
)

@Composable
fun NavigationPills(
    viewModel: NavigationPillsViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val isExpanded by viewModel.isExpanded.collectAsState()

    val pillHeight by animateDpAsState(
        targetValue = if (isExpanded) 56.dp else 48.dp,
        animationSpec = if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NavRailAnimations)) spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow) else snap(),
        label = "pillHeight"
    )

    val view = LocalView.current
    val glassEnabled = remember { GlassEffectManager.isComponentEnabled(GlassComponent.NavPills) }
    val textColor = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) Color(0xFF1A1A1A) else Color.White.copy(alpha = 0.9f)
    } else Color.White.copy(alpha = 0.9f)
    val activeTint = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) Color(0xFF1A6BFF) else Color(0xFF87CEEB)
    } else Color(0xFF87CEEB)
    val inactiveTint = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) textColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.7f)
    } else Color.White.copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (glassEnabled) {
            AndroidView(
                factory = { ctx ->
                    View(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        post {
                            GlassEffectManager.applyGlass(this, GlassComponent.NavPills, 50f)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillHeight)
                    .focusable(false)
            )
        }
        Row(
            modifier = Modifier
                .height(pillHeight)
                .background(
                    brush = if (glassEnabled) Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    ) else Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.04f)
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 6.dp)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (currentTab > 0) {
                                    viewModel.setTab(currentTab - 1)
                                } else {
                                    (view.parent as? View)?.focusSearch(View.FOCUS_LEFT)?.requestFocus()
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (currentTab < TAB_ORDER.lastIndex) {
                                    viewModel.setTab(currentTab + 1)
                                } else {
                                    (view.parent as? View)?.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
                                }
                                true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                viewModel.setTab(currentTab)
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TAB_ORDER.forEachIndexed { index, tab ->
                val isActive = currentTab == index
                NavigationPill(
                    tab = tab,
                    label = TAB_LABELS[tab] ?: tab,
                    isActive = isActive,
                    isExpanded = isExpanded,
                    textColor = textColor,
                    activeTint = activeTint,
                    inactiveTint = inactiveTint
                )
            }
        }
    }
}

@Composable
fun NavigationPill(
    tab: String,
    label: String,
    isActive: Boolean,
    isExpanded: Boolean,
    textColor: Color = Color.White,
    activeTint: Color = Color(0xFF87CEEB),
    inactiveTint: Color = Color.White.copy(alpha = 0.7f)
) {
    val iconRes = TAB_ICONS[tab] ?: R.drawable.ic_round_home_24

    val pillWidth by animateDpAsState(
        targetValue = if (isExpanded) 88.dp else 48.dp,
        animationSpec = if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NavRailAnimations)) spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow) else snap(),
        label = "pillWidth"
    )

    Box(
        modifier = Modifier
            .width(pillWidth)
            .fillMaxHeight()
            .background(
                color = if (isActive) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(50)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isExpanded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    tint = if (isActive) activeTint else inactiveTint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = label,
                    color = if (isActive) textColor else textColor.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = if (isActive) activeTint else inactiveTint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
