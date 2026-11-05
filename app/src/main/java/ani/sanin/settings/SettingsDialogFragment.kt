package ani.sanin.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.MainActivity
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.databinding.BottomSheetSettingsBinding
import ani.sanin.getThemeColor
import ani.sanin.home.AnimeFragment
import ani.sanin.home.HomeFragment
import ani.sanin.home.LoginFragment
import ani.sanin.incognitoNotification
import ani.sanin.loadImage
import ani.sanin.snackString
import ani.sanin.profile.ProfileActivity
import ani.sanin.profile.activity.FeedActivity
import ani.sanin.profile.notification.NotificationActivity
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.startMainActivity
import ani.sanin.openLinkInCustomTab
import ani.sanin.util.customAlertDialog
import ani.sanin.util.FocusEffectUtil
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import java.util.Timer
import kotlin.concurrent.schedule

class SettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var pageType: PageType
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageType = arguments?.getSerializableCompat("pageType") as? PageType ?: PageType.HOME
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val window = dialog?.window
        window?.statusBarColor = Color.CYAN
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        val isRescueModeEarly: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val notificationIcon = if (!isRescueModeEarly && Anilist.unreadNotificationCount > 0) {
            R.drawable.ic_round_notifications_active_24
        } else {
            R.drawable.ic_round_notifications_none_24
        }
        binding.settingsNotification.setImageResource(notificationIcon)
        if (isRescueModeEarly) binding.settingsNotification.visibility = View.GONE

        if (Anilist.token != null) {
            binding.settingsLogin.setText(R.string.logout)
            binding.settingsLogin.setOnClickListener {
                requireContext().customAlertDialog().apply {
                    setTitle(R.string.logout)
                    setMessage(R.string.logout_confirm)
                    setPosButton(R.string.yes) {
                        Anilist.removeSavedToken()
                        startMainActivity(requireActivity())
                    }
                    setNegButton(R.string.no)
                    show()
                }
            }
            val isRescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
            binding.settingsUsername.text = if (isRescueMode) MAL.username ?: "MAL User" else Anilist.username
            binding.settingsUserAvatar.loadImage(if (isRescueMode) MAL.avatar else Anilist.avatar)
        } else {
            binding.settingsUsername.visibility = View.GONE
            binding.settingsLogin.setText(R.string.login)
            binding.settingsLogin.setOnClickListener {
                dismiss()
                Anilist.loginIntent(requireActivity())
            }
        }
        val isRescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        binding.settingsNotificationCount.isVisible = !isRescueMode && Anilist.unreadNotificationCount > 0
        binding.settingsNotificationCount.text = Anilist.unreadNotificationCount.toString()
        if (isRescueMode) {
            binding.settingsActivity.visibility = View.GONE
        }
        binding.settingsUserAvatar.setOnClickListener {
            if (isRescueMode) {
                val malUsername = MAL.username
                if (!malUsername.isNullOrBlank()) {
                    openLinkInCustomTab("https://myanimelist.net/profile/$malUsername")
                } else {
                    snackString(getString(R.string.rescue_mode_active))
                }
                return@setOnClickListener
            }
            ContextCompat.startActivity(
                requireContext(), Intent(requireContext(), ProfileActivity::class.java)
                    .putExtra("userId", Anilist.userid), null
            )
        }

        binding.settingsLogin.isFocusable = true
        FocusEffectUtil.applyFocusListener(binding.settingsLogin)
        binding.settingsUserAvatar.isFocusable = true
        FocusEffectUtil.applyFocusListener(binding.settingsUserAvatar)
        binding.settingsNotification.isFocusable = true
        FocusEffectUtil.applyFocusListener(binding.settingsNotification)

        binding.settingsIncognito.isChecked = PrefManager.getVal(PrefName.Incognito)
        binding.settingsIncognito.setOnCheckedChangeListener { _, isChecked ->

            if (isAdded) {
                PrefManager.setVal(PrefName.Incognito, isChecked)
                incognitoNotification(requireContext())
            }
        }

        binding.settingsRescueMode.isChecked = PrefManager.getVal(PrefName.RescueMode)
        binding.settingsRescueMode.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.RescueMode, isChecked)
            activity?.let { act ->
                dismiss()
                val intent = Intent(act, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                act.startActivity(intent)
                act.overridePendingTransition(0, 0)
                act.finish()
                act.overridePendingTransition(0, 0)
            }
        }

        binding.settingsExtensionSettings.setSafeOnClickListener {
            dismiss()
            startActivity(Intent(activity, ExtensionsActivity::class.java))
        }

        binding.settingsSettings.setSafeOnClickListener {
            dismiss()
            startActivity(Intent(activity, SettingsActivity::class.java))
        }

        binding.settingsActivity.setSafeOnClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                snackString(getString(R.string.rescue_mode_active))
                return@setSafeOnClickListener
            }
            dismiss()
            startActivity(Intent(activity, FeedActivity::class.java))
        }

        binding.settingsNotification.setOnClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                snackString(getString(R.string.rescue_mode_active))
                return@setOnClickListener
            }
            dismiss()
            startActivity(Intent(activity, NotificationActivity::class.java))
        }
        binding.settingsDownloads.isChecked = PrefManager.getVal(PrefName.OfflineMode)
        binding.settingsDownloads.setOnCheckedChangeListener { _, isChecked ->
            Timer().schedule(300) {
                val currentActivity = activity

                if (currentActivity != null && isAdded) {
                    when (pageType) {
                        PageType.MANGA -> {
                            openMangaPage()
                        }

                        PageType.ANIME -> {
                            val intent = Intent(currentActivity, MainActivity::class.java)
                            intent.putExtra("FRAGMENT_CLASS_NAME", "")
                            startActivity(intent)
                        }

                        PageType.HOME -> {
                            val intent = Intent(currentActivity, MainActivity::class.java)
                            intent.putExtra("FRAGMENT_CLASS_NAME", HomeFragment::class.java.name)
                            startActivity(intent)
                        }

                        PageType.OfflineMANGA -> {
                            openMangaPage()
                        }

                        PageType.OfflineHOME -> {
                            val intent = Intent(currentActivity, MainActivity::class.java)
                            intent.putExtra(
                                "FRAGMENT_CLASS_NAME",
                                if (Anilist.token != null) HomeFragment::class.java.name else LoginFragment::class.java.name
                            )
                            startActivity(intent)
                        }

                        PageType.OfflineANIME -> {
                            val intent = Intent(currentActivity, MainActivity::class.java)
                            intent.putExtra("FRAGMENT_CLASS_NAME", AnimeFragment::class.java.name)
                            startActivity(intent)
                        }
                    }

                    dismiss()
                    PrefManager.setVal(PrefName.OfflineMode, isChecked)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openMangaPage() {
        snackString("Manga is not available")
    }

    companion object {
        enum class PageType {
            MANGA, ANIME, HOME, OfflineMANGA, OfflineANIME, OfflineHOME
        }

        fun newInstance(pageType: PageType): SettingsDialogFragment {
            val fragment = SettingsDialogFragment()
            val args = Bundle()
            args.putSerializable("pageType", pageType)
            fragment.arguments = args
            return fragment
        }
    }
}
