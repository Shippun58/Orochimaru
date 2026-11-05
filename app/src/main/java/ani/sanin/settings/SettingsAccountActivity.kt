package ani.sanin.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.auth.LoginDiagnostics
import ani.sanin.connections.auth.QrLoginDialog

import ani.sanin.connections.mal.MAL
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.ActivitySettingsAccountsBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.openLinkInBrowser
import ani.sanin.others.CustomBottomDialog
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.Logger
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsAccountHelp.isFocusable = true
            settingsAccountHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            Simkl.getSavedToken()

            fun reload() {
                settingsAnilistLogin.isFocusable = true
                settingsMALLogin.isFocusable = true

                FocusEffectUtil.applyFocusListener(
                    settingsAnilistLogin,
                    settingsMALLogin,
                    settingsSimklLogin,
                    borderColor = Color.WHITE,
                    cornerRadiusPx = 24f * resources.displayMetrics.density,
                )
                if (Anilist.token != null) {
                    settingsAnilistLogin.setText(R.string.unlink_anilist)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsAnilistAvatar.loadImage(Anilist.avatar)
                    settingsAnilistAvatarRow.visibility = View.VISIBLE
                    settingsAnilistUsername.visibility = View.VISIBLE
                    settingsAnilistUsername.text = Anilist.username

                    settingsMALLoginRequired.visibility = View.GONE
                    settingsMALLogin.visibility = View.VISIBLE
                    settingsMALUsername.visibility = View.VISIBLE

                    if (MAL.token != null) {
                        settingsMALLogin.setText(R.string.unlink_mal)
                        settingsMALLogin.setOnClickListener {
                            MAL.removeSavedToken()
                            restartMainActivity.isEnabled = true
                            reload()
                        }
                        settingsMALAvatar.loadImage(MAL.avatar)
                        settingsMalAvatarRow.visibility = View.VISIBLE
                        settingsMALUsername.visibility = View.VISIBLE
                        settingsMALUsername.text = MAL.username
                    } else {
                        settingsMalAvatarRow.visibility = View.GONE
                        settingsMALUsername.visibility = View.GONE
                        settingsMALLogin.setText(R.string.link_mal)
                        settingsMALLogin.setOnClickListener {
                            MAL.loginIntent(context)
                        }
                    }
                } else {
                    settingsAnilistAvatarRow.visibility = View.GONE
                    settingsAnilistUsername.visibility = View.GONE
                    settingsRecyclerView.visibility = View.GONE
                    settingsAnilistLogin.setText(R.string.link_anilist)
                    settingsAnilistLogin.setOnClickListener {
                        context.customAlertDialog().apply {
                            setTitle(getString(R.string.login_to_anilist))
                            singleChoiceItems(
                                arrayOf(
                                    getString(R.string.browser_login),
                                    getString(R.string.qr_code_login)
                                )
                            ) { choice ->
                                when (choice) {
                                    0 -> Anilist.loginIntent(context)
                                    1 -> QrLoginDialog(
                                        context,
                                        this@SettingsAccountActivity.lifecycleScope
                                    ) {
                                        Logger.log("[QR-DEBUG] SettingsAccountActivity: onAuthenticated callback")
                                        if (Anilist.getSavedToken()) {
                                            Logger.log("[QR-DEBUG] SettingsAccountActivity: calling getUserData()")
                                            Anilist.query.getUserData()
                                            LoginDiagnostics.recordLogin(
                                                LoginDiagnostics.LoginMethod.QR_CODE
                                            )
                                            reload()
                                            toast("Successfully signed in")
                                        } else {
                                            Logger.log("[QR-DEBUG] SettingsAccountActivity: getSavedToken returned false")
                                            toast("Login failed: no token received from relay")
                                        }
                                    }.show()
                                }
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    settingsMALLoginRequired.visibility = View.VISIBLE
                    settingsMALLogin.visibility = View.GONE
                    settingsMALUsername.visibility = View.GONE
                }

            settingsAnilistGear.setOnClickListener {
                lifecycleScope.launch {
                    Anilist.query.getUserData()
                    startActivity(Intent(context, AnilistSettingsActivity::class.java))
                }
            }
            FocusEffectUtil.applyFocusListener(settingsAnilistGear)

            settingsSimklLogin.isFocusable = true
            if (Simkl.token != null) {
                settingsSimklLogin.setText(R.string.unlink_simkl)
                settingsSimklLogin.setOnClickListener {
                    Simkl.removeSavedToken()
                    restartMainActivity.isEnabled = true
                    reload()
                }
                settingsSimklAvatar.loadImage(Simkl.avatar)
                settingsSimklAvatarRow.visibility = View.VISIBLE
                settingsSimklUsername.text = Simkl.username
                settingsSimklUsername.visibility = View.VISIBLE
            } else {
                settingsSimklUsername.visibility = View.GONE
                settingsSimklAvatarRow.visibility = View.GONE
                settingsSimklLogin.setText(R.string.link_simkl)
                settingsSimklLogin.setOnClickListener {
                    Simkl.loginIntent(this@SettingsAccountActivity)
                }
            }
            }
            reload()
        }
        binding.settingsDiscordJoin.isFocusable = true
        FocusEffectUtil.applyFocusListener(binding.settingsDiscordJoin)
        binding.settingsDiscordJoin.setOnClickListener {
            openLinkInBrowser(getString(R.string.discord))
        }
        binding.settingsTelegramJoin.isFocusable = true
        FocusEffectUtil.applyFocusListener(binding.settingsTelegramJoin)
        binding.settingsTelegramJoin.setOnClickListener {
            openLinkInBrowser(getString(R.string.telegram))
        }
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(

                Settings(
                    type = 1,
                    name = "Comments",
                    desc = "Choose comment sources",
                    icon = R.drawable.ic_round_comment_24,
                    onClick = { showCommentsDialog() },
                    isVisible = Anilist.token != null
                ),
            )
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

    }

    private fun showCommentsDialog() {
        val saninChecked = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1
        val anikotoChecked = PrefManager.getVal<Int>(PrefName.AnikotoCommentsEnabled) == 1
        val items = arrayOf("Sanin", "Anikoto")
        val checked = booleanArrayOf(saninChecked, anikotoChecked)

        customAlertDialog().apply {
            setTitle("Comment Sources")
            multiChoiceItems(items, checked) { result ->
                for (i in result.indices) checked[i] = result[i]
            }
            setPosButton("OK") {
                PrefManager.setVal(PrefName.CommentsEnabled, if (checked[0]) 1 else 2)
                PrefManager.setVal(PrefName.AnikotoCommentsEnabled, if (checked[1]) 1 else 0)
                reload()
            }
            setNegButton("Cancel")
        }.show()
    }

    fun reload() {
        snackString(getString(R.string.restart_app_extra))
    }
}