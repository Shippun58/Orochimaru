package ani.sanin.settings

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build.BRAND
import android.os.Build.DEVICE
import android.os.Build.SUPPORTED_ABIS
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.RELEASE
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.BuildConfig
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.ActivitySettingsBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil

private data class SettingsSection(
    val title: String,
    val iconRes: Int,
    val entries: List<SectionEntry>,
    val defaultExpanded: Boolean = false,
    val desc: String? = null,
)

private data class SectionEntry(
    val title: String,
    val desc: String? = null,
    val iconRes: Int,
    val onClick: (() -> Unit)? = null,
)

class SettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivitySettingsBinding
    private var cursedCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            settingsVersion.text = getString(R.string.version_current, BuildConfig.VERSION_NAME)
            settingsVersion.setOnLongClickListener {
                copyToClipboard(getDeviceInfo(), false)
                toast(getString(R.string.copied_device_info))
                true
            }

            settingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            FocusEffectUtil.applyFocusListener(settingsBack)

            settingsLogo.setSafeOnClickListener {
                cursedCounter++
                (settingsLogo.drawable as? Animatable)?.start()
                if (cursedCounter % 16 == 0) {
                    val oldVal: Boolean = PrefManager.getVal(PrefName.OC)
                    if (!oldVal) toast(R.string.omega_cursed)
                    else toast(R.string.omega_freed)
                    PrefManager.setVal(PrefName.OC, !oldVal)
                } else {
                    val array = resources.getStringArray(R.array.tips)
                    snackString(array[(Math.random() * array.size).toInt()], this@SettingsActivity)
                }
            }

            val sections = buildSections()
            settingsRecyclerView.layoutManager = LinearLayoutManager(this@SettingsActivity)
            settingsRecyclerView.adapter = SectionAdapter(sections)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (PrefManager.getCustomVal("reload", false)) {
                startMainActivity(this@SettingsActivity)
                PrefManager.setCustomVal("reload", false)
            } else {
                finish()
            }
        }
    }

    private fun buildSections(): List<SettingsSection> = listOf(
        SettingsSection(
            title = "Account",
            desc = "Profiles, tracking & accounts",
            iconRes = R.drawable.ic_settings_account,
            entries = listOf(
                SectionEntry(
                    title = getString(R.string.accounts),
                    desc = "AniList, MAL & Simkl profiles",
                    iconRes = R.drawable.ic_settings_account,
                    onClick = { startActivity(Intent(this, SettingsAccountActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Look & Feel",
            desc = "Colors, cards, blur & glass",
            iconRes = R.drawable.ic_set_theme,
            entries = listOf(
                SectionEntry(
                    title = "Appearance",
                    desc = "Colors, cards, blur & glass",
                    iconRes = R.drawable.ic_set_theme,
                    onClick = { startActivity(Intent(this, SettingsAppearanceActivity::class.java)) },
                ),
                SectionEntry(
                    title = "Motion",
                    desc = "Animations & motion effects",
                    iconRes = R.drawable.ic_set_motion,
                    onClick = { startActivity(Intent(this, SettingsAnimationActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Playback & App Behaviour",
            desc = "Player, decoder & sync",
            iconRes = R.drawable.ic_set_video,
            entries = listOf(
                SectionEntry(
                    title = "Player & Sync",
                    desc = "Player, buffer, decoder & library sync",
                    iconRes = R.drawable.ic_set_video,
                    onClick = { startActivity(Intent(this, SettingsAnimeActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Network & Data",
            desc = "DNS, backup & restore",
            iconRes = R.drawable.ic_set_dns,
            entries = listOf(
                SectionEntry(
                    title = "Connection & Backup",
                    desc = "DNS, user agent, backup & restore",
                    iconRes = R.drawable.ic_set_dns,
                    onClick = { startActivity(Intent(this, SettingsCommonActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Downloads",
            desc = "Storage, queue & notifications",
            iconRes = R.drawable.ic_download_24,
            entries = listOf(
                SectionEntry(
                    title = "Downloads",
                    desc = "Storage, queue & notifications",
                    iconRes = R.drawable.ic_download_24,
                    onClick = { startActivity(Intent(this, SettingsDownloadsActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Utilities",
            desc = "Add-ons, cache & diagnostics",
            iconRes = R.drawable.ic_settings_tools,
            entries = listOf(
                SectionEntry(
                    title = "Management",
                    desc = "Add-ons, cache & diagnostics",
                    iconRes = R.drawable.ic_settings_tools,
                    onClick = { startActivity(Intent(this, SettingsExtrasActivity::class.java)) },
                ),
            ),
        ),
    )

    override fun onResume() {
        ThemeManager(this).applyTheme()
        super.onResume()
    }

    private inner class SectionAdapter(
        private val sections: List<SettingsSection>,
    ) : RecyclerView.Adapter<SectionAdapter.SectionVH>() {

        inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
            val header: LinearLayout = v.findViewById(R.id.sectionHeader)
            val icon: ImageView = v.findViewById(R.id.sectionIcon)
            val title: TextView = v.findViewById(R.id.sectionTitle)
            val desc: TextView = v.findViewById(R.id.sectionDesc)
            val chevron: ImageView = v.findViewById(R.id.sectionChevron)
            val items: LinearLayout = v.findViewById(R.id.sectionItems)
        }

        private val expandedSet = mutableSetOf<Int>().apply {
            sections.forEachIndexed { i, s -> if (s.defaultExpanded) add(i) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_section, parent, false)
            return SectionVH(v)
        }

        override fun onBindViewHolder(holder: SectionVH, position: Int) {
            val section = sections[position]
            val isExpanded = position in expandedSet

            holder.icon.setImageResource(section.iconRes)
            holder.title.text = section.title
            holder.desc.apply {
                if (section.desc != null) {
                    text = section.desc
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            holder.chevron.rotation = if (isExpanded) 180f else 0f

            holder.header.setOnClickListener {
                toggleSection(holder, position)
            }
            FocusEffectUtil.applyFocusListener(holder.header)

            holder.items.removeAllViews()
            val inflater = LayoutInflater.from(this@SettingsActivity)
            section.entries.forEach { entry ->
                val entryView = inflater.inflate(
                    R.layout.item_settings_section_entry, holder.items, false,
                )
                entryView.findViewById<ImageView>(R.id.entryIcon).setImageResource(entry.iconRes)
                entryView.findViewById<TextView>(R.id.entryTitle).text = entry.title
                entryView.findViewById<TextView>(R.id.entryDesc).apply {
                    if (entry.desc != null) {
                        text = entry.desc
                        visibility = View.VISIBLE
                    } else {
                        visibility = View.GONE
                    }
                }
                entryView.setOnClickListener { entry.onClick?.invoke() }
                FocusEffectUtil.applyFocusListener(entryView)
                holder.items.addView(entryView)
            }

            holder.items.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        private fun toggleSection(holder: SectionVH, position: Int) {
            val isExpanding = position !in expandedSet
            if (isExpanding) expandedSet.add(position) else expandedSet.remove(position)

            ObjectAnimator.ofFloat(holder.chevron, "rotation",
                if (isExpanding) 0f else 180f,
                if (isExpanding) 180f else 0f,
            ).apply {
                duration = if (AnimUtils.enabled(PrefName.XpandableAnimations)) 250 else 0
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }

            if (isExpanding) AnimUtils.rollExpand(holder.items) else AnimUtils.rollCollapse(holder.items)
        }

        override fun getItemCount() = sections.size
    }

    companion object {
        fun getDeviceInfo(): String = """
            sanin Version: ${BuildConfig.VERSION_NAME}
            Device: $BRAND $DEVICE
            Architecture: ${getArch()}
            OS Version: $CODENAME $RELEASE ($SDK_INT)
        """.trimIndent()

        private fun getArch(): String {
            SUPPORTED_ABIS.forEach {
                when (it) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return System.getProperty("os.arch") ?: System.getProperty("os.product.cpu.abi")
                ?: "Unknown Architecture"
        }
    }
}
