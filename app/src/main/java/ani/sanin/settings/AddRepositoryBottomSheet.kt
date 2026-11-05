package ani.sanin.settings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
import ani.sanin.copyToClipboard
import ani.sanin.databinding.BottomSheetAddRepositoryBinding
import ani.sanin.databinding.ItemRepoBinding
import ani.sanin.media.MediaType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.util.customAlertDialog
import ani.sanin.util.FocusEffectUtil
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RepoItem(
    val url: String,
    private val mediaType: MediaType,
    val onRemove: (String, MediaType) -> Unit
) : BindableItem<ItemRepoBinding>() {
    override fun getLayout() = R.layout.item_repo

    override fun bind(viewBinding: ItemRepoBinding, position: Int) {
        viewBinding.repoNameTextView.text = url.cleanShownUrl()
        viewBinding.repoDeleteImageView.setOnClickListener {
            onRemove(url, mediaType)
        }
        viewBinding.repoCopyImageView.setOnClickListener {
            viewBinding.repoCopyImageView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copyToClipboard(url, true)
        }
    }

    override fun initializeViewBinding(view: View): ItemRepoBinding {
        return ItemRepoBinding.bind(view)
    }

    private fun String.cleanShownUrl(): String {
        return this
            .removePrefix("https://raw.githubusercontent.com/")
            .replace("index.min.json", "")
            .replace("repo.json", "")
            .removeSuffix("/")
    }
}

class AddRepositoryBottomSheet : DialogFragment() {
    private var _binding: BottomSheetAddRepositoryBinding? = null
    private val binding get() = _binding!!
    private var mediaType: MediaType = MediaType.ANIME
    private var onRepositoryAdded: ((String, MediaType) -> Unit)? = null
    private var repositories: MutableList<String> = mutableListOf()
    private var onRepositoryRemoved: ((String, MediaType) -> Unit)? = null
    private var adapter: GroupieAdapter = GroupieAdapter()
    private var cloudStream: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddRepositoryBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(R.drawable.top_sheet_background)
        }

        binding.repositoriesRecyclerView.adapter = adapter
        binding.repositoriesRecyclerView.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.VERTICAL,
            false
        )
        adapter.addAll(repositories.map { RepoItem(it, mediaType, ::onRepositoryRemoved) })

        dialog?.window?.let { TvKeyboardUtil.retainWindowFocus(it) }
        binding.repositoryInput.hint =
            if (cloudStream) getString(R.string.anime_add_repository) + " (repo.json)"
            else getString(R.string.anime_add_repository)
        TvKeyboardUtil.setupTvInput(binding.repositoryInput)

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (TvKeyboardUtil.isCompactKeyboardVisible(binding.repositoryInput)) {
                    binding.repositoryInput.clearFocus()
                    binding.addButton.requestFocus()
                    TvKeyboardUtil.hideCompactKeyboard(binding.repositoryInput)
                    return@setOnKeyListener true
                }
            }
            false
        }

        binding.addButton.setOnClickListener {
            val input = binding.repositoryInput.text.toString()
            val error = isValidUrl(input)
            if (error == null) {
                acceptUrl(input)
            } else {
                binding.repositoryInput.error = error
            }
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        binding.repositoryInput.setOnEditorActionListener { textView, action, keyEvent ->
            if (action == EditorInfo.IME_ACTION_DONE ||
                (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val url = textView.text.toString()
                if (url.isNotBlank()) {
                    val error = isValidUrl(url)
                    if (error == null) {
                        acceptUrl(url)
                        return@setOnEditorActionListener true
                    } else {
                        binding.repositoryInput.error = error
                    }
                }
            }
            false
        }
    }

    private fun acceptUrl(url: String) {
        val finalUrl = getRepoUrl(url)
        context?.let { context ->
            if (cloudStream) {
                CoroutineScope(Dispatchers.IO).launch {
                    val resolved = CsRepos.resolveRepoUrl(finalUrl) ?: finalUrl
                    val manifest = runCatching { CsRepos.fetchManifest(resolved) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (_binding == null || !isAdded) return@withContext
                        if (manifest == null) {
                            binding.repositoryInput.error = "No CloudStream repo found at this URL"
                        } else {
                            addRepoWarning(context) {
                                onRepositoryAdded?.invoke(resolved, mediaType)
                                dismiss()
                            }
                        }
                    }
                }
            } else {
                addRepoWarning(context) {
                    onRepositoryAdded?.invoke(finalUrl, mediaType)
                    dismiss()
                }
            }
        }
    }

    private fun isValidUrl(input: String): String? {
        val cleaned = if (cloudStream) stripCloudStreamPrefix(input) else input.trim()
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            if (!cloudStream && !cleaned.removeSuffix("/").endsWith("index.min.json")) {
                return "URL must end with index.min.json"
            }
            return null
        }
        if (cloudStream && cleaned.isNotBlank() && cleaned.substringBefore("/").contains(".")) {
            return null
        }

        if (cloudStream && cleaned.matches(Regex("^[a-zA-Z0-9!_-]+$"))) {
            return null
        }

        val parts = cleaned.split("/")
        if (parts.size !in 2..3) {
            return "Must be a full URL or in format: username/repo[/branch]"
        }

        val username = parts[0]
        val repo = parts[1]
        val branch = if (parts.size == 3) parts[2] else "repo"

        if (username.isBlank() || repo.isBlank()) {
            return "Username and repository name cannot be empty"
        }
        if (parts.size == 3 && branch.isBlank()) {
            return "Branch name cannot be empty"
        }

        return null
    }

    private fun getRepoUrl(input: String): String = normalizeRepoUrl(input, cloudStream)

    private fun onRepositoryRemoved(url: String, mediaType: MediaType) {
        onRepositoryRemoved?.invoke(url, mediaType)
        repositories.remove(url)
        adapter.update(repositories.map { RepoItem(it, mediaType, ::onRepositoryRemoved) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun addRepoWarning(context: Context, onRepositoryAdded: () -> Unit) {
            context.customAlertDialog()
                .setTitle(R.string.warning)
                .setMessage(R.string.add_repository_warning)
                .setPosButton(R.string.ok) {
                    onRepositoryAdded.invoke()
                }
                .setNegButton(R.string.cancel) { }
                .show()
        }

        fun addRepo(input: String, mediaType: MediaType, cloudStream: Boolean = false) {
            val validLink = if (input.contains("github.com") && input.contains("blob")) {
                input.replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            } else input

            if (cloudStream) {
                CsRepos.addRepo(normalizeRepoUrl(validLink, true))
                return
            }
            when (mediaType) {
                MediaType.ANIME -> {
                    val anime =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
                            .plus(validLink)
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, anime)
                    CoroutineScope(Dispatchers.IO).launch {
                        Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
                    }
                }
                else -> {}
            }
        }

        fun removeRepo(input: String, mediaType: MediaType, cloudStream: Boolean = false) {
            if (cloudStream) {
                CsRepos.removeRepo(input)
                return
            }
            when (mediaType) {
                MediaType.ANIME -> {
                    val anime =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
                            .minus(input)
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, anime)
                    CoroutineScope(Dispatchers.IO).launch {
                        Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
                    }
                }
                else -> {}
            }
        }

        fun normalizeRepoUrl(input: String, cloudStream: Boolean): String {
            val cleaned = if (cloudStream) stripCloudStreamPrefix(input) else input.trim()
            if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
                return cleaned
            }
            if (cloudStream && cleaned.substringBefore("/").contains(".")) {
                return "https://$cleaned"
            }
            if (cloudStream && cleaned.matches(Regex("^[a-zA-Z0-9!_-]+$"))) {
                val trimmed = input.trim()
                val hadOfficialPrefix = stripCloudStreamPrefix(trimmed) != trimmed
                return if (hadOfficialPrefix) "https://$cleaned" else cleaned
            }
            val parts = cleaned.split("/")
            if (parts.size !in 2..3) return cleaned
            val username = parts[0]
            val repo = parts[1]
            val branch = if (parts.size == 3) parts[2] else if (cloudStream) "builds" else "repo"
            val suffix = if (cloudStream) "repo.json" else "index.min.json"
            return "https://raw.githubusercontent.com/$username/$repo/$branch/$suffix"
        }

        fun stripCloudStreamPrefix(input: String): String {
            return input.trim()
                .replaceFirst(Regex("^https?://cs\\.repo/?\\??"), "")
                .removePrefix("cloudstreamrepo://")
                .removePrefix("cloudstreamrepo:")
        }

        fun newInstance(
            mediaType: MediaType,
            repositories: List<String>,
            onRepositoryAdded: (String, MediaType) -> Unit,
            onRepositoryRemoved: (String, MediaType) -> Unit,
            cloudStream: Boolean = false
        ): AddRepositoryBottomSheet {
            return AddRepositoryBottomSheet().apply {
                this.mediaType = mediaType
                this.repositories.addAll(repositories)
                this.onRepositoryAdded = onRepositoryAdded
                this.onRepositoryRemoved = onRepositoryRemoved
                this.cloudStream = cloudStream
            }
        }
    }
}
