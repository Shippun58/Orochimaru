package ani.sanin.settings

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.slider.Slider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ani.sanin.R
import ani.sanin.setSafeOnClickListener
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch

object SubscreenBuilder {

    data class Section(
        val title: String,
        val iconRes: Int,
        val entries: List<Entry>,
        val defaultExpanded: Boolean = false,
        val desc: String? = null,
    )

    data class SliderOption(
        val value: Float,
        val valueFrom: Float,
        val valueTo: Float,
        val step: Float = 1f,
        val suffix: String = "",
        val onValueChange: (Float) -> Unit,
    )

    data class Entry(
        val title: String,
        val desc: String? = null,
        val iconRes: Int = 0,
        val onClick: ((Context) -> Unit)? = null,
        val onLongClick: (() -> Unit)? = null,
        val switch: Pair<Boolean, (Boolean) -> Unit>? = null,
        val choice: Choice? = null,
        val slider: SliderOption? = null,

        val expandSlider: ExpandSlider? = null,

        val isEnabled: Boolean = true,
    )

    data class ExpandSlider(
        val slider: SliderOption,

        val showOnIndex: Int = 0,
    )

    data class Choice(
        val title: String,
        val options: Array<String>,
        val currentIndex: Int,
        val onSelect: (Int) -> Unit,
    )

    fun build(
        context: Context,
        container: LinearLayout,
        sections: List<Section>,
        clear: Boolean = true,
    ) {
        val inflater = LayoutInflater.from(context)
        if (clear) container.removeAllViews()

        sections.forEachIndexed { sIdx, section ->
            val sectionView = inflater.inflate(R.layout.item_settings_section, container, false)
            val header = sectionView.findViewById<LinearLayout>(R.id.sectionHeader)
            val icon = sectionView.findViewById<ImageView>(R.id.sectionIcon)
            val title = sectionView.findViewById<TextView>(R.id.sectionTitle)
            val desc = sectionView.findViewById<TextView>(R.id.sectionDesc)
            val chevron = sectionView.findViewById<ImageView>(R.id.sectionChevron)
            val items = sectionView.findViewById<LinearLayout>(R.id.sectionItems)

            icon.setImageResource(section.iconRes)
            title.text = section.title
            if (section.desc != null) {
                desc.text = section.desc
                desc.visibility = View.VISIBLE
            }

            section.entries.forEach { entry ->
                if (entry.switch != null) {
                    val switchView = inflater.inflate(R.layout.item_settings_section_switch, items, false)
                    val sIcon = switchView.findViewById<ImageView>(R.id.switchIcon)
                    val sTitle = switchView.findViewById<TextView>(R.id.switchTitle)
                    val sDesc = switchView.findViewById<TextView>(R.id.switchDesc)
                    val sToggle = switchView.findViewById<MaterialSwitch>(R.id.switchToggle)

                    if (entry.iconRes != 0) sIcon.setImageResource(entry.iconRes) else sIcon.visibility = View.GONE
                    sTitle.text = entry.title
                    if (entry.desc != null) {
                        sDesc.text = entry.desc
                        sDesc.visibility = View.VISIBLE
                    }

                    sToggle.isSaveEnabled = false
                    sToggle.isChecked = entry.switch!!.first
                    sToggle.setOnCheckedChangeListener { _, isChecked -> entry.switch.second(isChecked) }
                    if (!entry.isEnabled) {
                        switchView.alpha = 0.35f
                        switchView.isClickable = false
                        switchView.isFocusable = false
                        sToggle.isEnabled = false
                        sTitle.isClickable = false
                        sDesc.isClickable = false
                    }
                    sTitle.setOnClickListener { sToggle.isChecked = !sToggle.isChecked }
                    sDesc.setOnClickListener { sToggle.isChecked = !sToggle.isChecked }

                    switchView.setOnClickListener { sToggle.isChecked = !sToggle.isChecked }
                    if (entry.onLongClick != null) {
                        switchView.setOnLongClickListener { entry.onLongClick!!.invoke(); true }
                    }
                    FocusEffectUtil.applyFocusListener(switchView)
                    items.addView(switchView)

                } else if (entry.slider != null) {
                    val sliderView = inflater.inflate(R.layout.item_settings_section_slider, items, false)
                    val slTitle = sliderView.findViewById<TextView>(R.id.sliderTitle)
                    val slDesc = sliderView.findViewById<TextView>(R.id.sliderDesc)
                    val sl = sliderView.findViewById<Slider>(R.id.slider)
                    val slValue = sliderView.findViewById<TextView>(R.id.sliderValue)
                    slTitle.text = entry.title
                    if (entry.desc != null) {
                        slDesc.text = entry.desc
                        slDesc.visibility = View.VISIBLE
                    }
                    sl.valueFrom = entry.slider.valueFrom
                    sl.valueTo = entry.slider.valueTo
                    sl.stepSize = entry.slider.step
                    sl.value = entry.slider.value
                    slValue.text = "${entry.slider.value.toInt()}${entry.slider.suffix}"
                    sl.addOnChangeListener { _, value, fromUser ->

                        slValue.text = "${value.toInt()}${entry.slider.suffix}"
                        if (fromUser) {
                            entry.slider.onValueChange(value)
                        }
                    }

                    sliderView.setOnKeyListener { _, keyCode, event ->
                        if (!entry.isEnabled) return@setOnKeyListener false
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val step = entry.slider.step
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val nv = (sl.value - step).coerceIn(sl.valueFrom, sl.valueTo)
                                if (nv != sl.value) {
                                    sl.value = nv
                                    slValue.text = "${nv.toInt()}${entry.slider.suffix}"
                                    entry.slider.onValueChange(nv)
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val nv = (sl.value + step).coerceIn(sl.valueFrom, sl.valueTo)
                                if (nv != sl.value) {
                                    sl.value = nv
                                    slValue.text = "${nv.toInt()}${entry.slider.suffix}"
                                    entry.slider.onValueChange(nv)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    if (!entry.isEnabled) {
                        sliderView.alpha = 0.35f
                        sliderView.isClickable = false
                        sliderView.isFocusable = false
                        sl.isEnabled = false
                    }
                    FocusEffectUtil.applyFocusListener(sliderView)
                    items.addView(sliderView)

                } else {
                    val entryView = inflater.inflate(R.layout.item_settings_section_entry, items, false)
                    val eIcon = entryView.findViewById<ImageView>(R.id.entryIcon)
                    val eTitle = entryView.findViewById<TextView>(R.id.entryTitle)
                    val eDesc = entryView.findViewById<TextView>(R.id.entryDesc)
                    val eChevron = entryView.findViewById<ImageView>(R.id.entryChevron)

                    if (entry.iconRes != 0) eIcon.setImageResource(entry.iconRes) else eIcon.visibility = View.GONE
                    eTitle.text = entry.title
                    if (entry.desc != null) {
                        eDesc.text = entry.desc
                        eDesc.visibility = View.VISIBLE
                    }

                    var expandSliderView: View? = null
                    var expandSlider: Slider? = null

                    if (entry.expandSlider != null) {
                        expandSliderView = inflater.inflate(R.layout.item_settings_section_slider, items, false)
                        val slTitle2 = expandSliderView.findViewById<TextView>(R.id.sliderTitle)
                        val sl2 = expandSliderView.findViewById<Slider>(R.id.slider)
                        val slValue2 = expandSliderView.findViewById<TextView>(R.id.sliderValue)
                        expandSlider = sl2

                        val es = entry.expandSlider!!
                        slTitle2.text = "Intensity"
                        sl2.valueFrom = es.slider.valueFrom
                        sl2.valueTo = es.slider.valueTo
                        sl2.stepSize = es.slider.step
                        sl2.value = es.slider.value
                        slValue2.text = "${es.slider.value.toInt()}${es.slider.suffix}"
                        sl2.addOnChangeListener { _, value, fromUser ->
                            slValue2.text = "${value.toInt()}${es.slider.suffix}"
                            if (fromUser) {
                                es.slider.onValueChange(value)
                            }
                        }
                        expandSliderView.setOnKeyListener { _, keyCode, event ->
                            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            val step = es.slider.step
                            when (keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    val nv = (sl2.value - step).coerceIn(sl2.valueFrom, sl2.valueTo)
                                    if (nv != sl2.value) {
                                        sl2.value = nv
                                        slValue2.text = "${nv.toInt()}${es.slider.suffix}"
                                        es.slider.onValueChange(nv)
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val nv = (sl2.value + step).coerceIn(sl2.valueFrom, sl2.valueTo)
                                    if (nv != sl2.value) {
                                        sl2.value = nv
                                        slValue2.text = "${nv.toInt()}${es.slider.suffix}"
                                        es.slider.onValueChange(nv)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        expandSliderView.visibility = if (entry.expandSlider!!.showOnIndex != entry.choice?.currentIndex) View.VISIBLE else View.GONE
                        FocusEffectUtil.applyFocusListener(expandSliderView)
                        items.addView(expandSliderView)
                    }

                    if (!entry.isEnabled) {
                        entryView.alpha = 0.35f
                        entryView.isClickable = false
                        entryView.isFocusable = false
                        eChevron.visibility = View.GONE
                    }
                    if (entry.choice != null && entry.isEnabled) {
                        entryView.setSafeOnClickListener {
                            val c = entry.choice!!
                            context.customAlertDialog().apply {
                                setTitle(c.title)
                                singleChoiceItems(c.options, c.currentIndex) { idx ->
                                    c.onSelect(idx)

                                    if (entry.expandSlider != null && expandSliderView != null) {
                                        val shouldShow = idx != entry.expandSlider!!.showOnIndex
                                        if (shouldShow) {
                                            expandSliderView.visibility = View.VISIBLE
                                            expandSliderView.alpha = 0f
                                            expandSliderView.animate().alpha(1f).setDuration(if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.XpandableAnimations)) 200 else 0).start()
                                        } else {
                                            expandSliderView.animate().alpha(0f).setDuration(if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.XpandableAnimations)) 150 else 0).withEndAction {
                                                expandSliderView.visibility = View.GONE
                                            }.start()
                                        }
                                    }
                                }
                                show()
                            }
                        }
                    } else if (entry.onClick != null) {
                        entryView.setSafeOnClickListener { entry.onClick!!.invoke(context) }
                    } else {
                        eChevron.visibility = View.GONE
                    }

                    if (entry.onLongClick != null) {
                        entryView.setOnLongClickListener { entry.onLongClick!!.invoke(); true }
                    }
                    FocusEffectUtil.applyFocusListener(entryView)
                    items.addView(entryView)
                }
            }

            val expanded = mutableSetOf<Int>()
            if (section.defaultExpanded) expanded.add(sIdx)

            fun toggle() {
                val isExpanding = sIdx !in expanded
                if (isExpanding) expanded.add(sIdx) else expanded.remove(sIdx)

                ObjectAnimator.ofFloat(chevron, "rotation",
                    if (isExpanding) 0f else 180f,
                    if (isExpanding) 180f else 0f,
                ).apply { duration = if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.XpandableAnimations)) 250 else 0; interpolator = AccelerateDecelerateInterpolator(); start() }

                if (isExpanding) AnimUtils.rollExpand(items) else AnimUtils.rollCollapse(items)
            }

            header.setSafeOnClickListener { toggle() }
            FocusEffectUtil.applyFocusListener(header)
            chevron.rotation = if (section.defaultExpanded) 180f else 0f
            items.visibility = if (section.defaultExpanded) View.VISIBLE else View.GONE

            container.addView(sectionView)

            if (sIdx < sections.lastIndex) {
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (8 * context.resources.displayMetrics.density).toInt()
                    )
                }
                container.addView(spacer)
            }
        }
    }
}
