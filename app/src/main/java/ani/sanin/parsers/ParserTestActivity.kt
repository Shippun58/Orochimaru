package ani.sanin.parsers

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.databinding.ActivityParserTestBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import com.xwray.groupie.GroupieAdapter

class ParserTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParserTestBinding
    val adapter = GroupieAdapter()
    val extensionsToTest: MutableList<ExtensionTestItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityParserTestBinding.inflate(layoutInflater)
        binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.extensionResultsRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)

        binding.extensionResultsRecyclerView.adapter = adapter
        binding.extensionResultsRecyclerView.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false
        )
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.optionsLayout.setOnClickListener {
            ExtensionTestSettingsBottomDialog.newInstance()
                .show(supportFragmentManager, "extension_test_settings")
        }

        binding.startButton.setOnClickListener {
            if (ExtensionTestSettingsBottomDialog.extensionsToTest.isEmpty()) {
                toast(R.string.no_extensions_selected)
                return@setOnClickListener
            }
            extensionsToTest.forEach {
                it.cancelJob()
            }
            extensionsToTest.clear()
            adapter.clear()
            when (ExtensionTestSettingsBottomDialog.extensionType) {
                "anime" -> {
                    ExtensionTestSettingsBottomDialog.extensionsToTest.forEach { name ->
                        val extension =
                            AnimeSources.list.find { source -> source.name == name }?.get?.value
                        extension?.let {
                            extensionsToTest.add(
                                ExtensionTestItem(
                                    "anime",
                                    ExtensionTestSettingsBottomDialog.testType,
                                    it,
                                    ExtensionTestSettingsBottomDialog.searchQuery
                                )
                            )
                        }
                    }
                }

                "manga" -> {
                    ExtensionTestSettingsBottomDialog.extensionsToTest.forEach { name ->
                        val extension =
                            MangaSources.list.find { source -> source.name == name }?.get?.value
                        extension?.let {
                            extensionsToTest.add(
                                ExtensionTestItem(
                                    "manga",
                                    ExtensionTestSettingsBottomDialog.testType,
                                    it,
                                    ExtensionTestSettingsBottomDialog.searchQuery
                                )
                            )
                        }
                    }
                }

                "novel" -> {
                    ExtensionTestSettingsBottomDialog.extensionsToTest.forEach { name ->
                        val extension =
                            NovelSources.list.find { source -> source.name == name }?.get?.value
                        extension?.let {
                            extensionsToTest.add(
                                ExtensionTestItem(
                                    "novel",
                                    ExtensionTestSettingsBottomDialog.testType,
                                    it,
                                    ExtensionTestSettingsBottomDialog.searchQuery
                                )
                            )
                        }
                    }
                }
            }
            extensionsToTest.forEach {
                adapter.add(it)
                it.startTest()
            }
        }
    }
}