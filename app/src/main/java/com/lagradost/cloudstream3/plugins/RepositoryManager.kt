package com.lagradost.cloudstream3.plugins
import ani.sanin.R

import android.content.Context
import androidx.annotation.WorkerThread
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginSanitizedFileName
import com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin
import com.lagradost.cloudstream3.plugins.REPOSITORIES_KEY
import com.lagradost.cloudstream3.plugins.RepositoryData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class Repository(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("manifestVersion") @SerialName("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") @SerialName("pluginLists") val pluginLists: List<String>,
)

@Serializable
data class SitePlugin(

    @JsonProperty("url") @SerialName("url") val url: String,

    @JsonProperty("status") @SerialName("status") val status: Int,

    @JsonProperty("version") @SerialName("version") val version: Int,

    @JsonProperty("apiVersion") @SerialName("apiVersion") val apiVersion: Int,

    @JsonProperty("name") @SerialName("name") val name: String,

    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("authors") @SerialName("authors") val authors: List<String>,
    @JsonProperty("description") @SerialName("description") val description: String?,

    @JsonProperty("repositoryUrl") @SerialName("repositoryUrl") val repositoryUrl: String?,

    @JsonProperty("tvTypes") @SerialName("tvTypes") val tvTypes: List<String>?,

    @JsonProperty("language") @SerialName("language") val language: String?,
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,

    @JsonProperty("fileSize") @SerialName("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") @SerialName("fileHash") val fileHash: String?,
)

@Serializable
data class PluginWrapper(
    @JsonProperty("repository") @SerialName("repository") val repository: Repository,
    @JsonProperty("repositoryData") @SerialName("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") @SerialName("plugin") val plugin: SitePlugin
) {
    companion object {
        private val localRepository = Repository("", "", "", 1, emptyList())
        private val localRepositoryData = RepositoryData("", "", "")
        fun getLocalPluginWrapper(plugin: SitePlugin): PluginWrapper {
            return PluginWrapper(
                localRepository,
                localRepositoryData,
                plugin
            )
        }
    }
}

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"
    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        getKey<Array<RepositoryData>>("PREBUILT_REPOSITORIES") ?: emptyArray()
    }
    private val GH_REGEX =
        Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")

    @WorkerThread
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun convertRawGitUrl(url: String): String {
        if (getKey<Boolean>(context!!.getString(R.string.jsdelivr_proxy_key)) != true) return url
        val match = GH_REGEX.find(url) ?: return url
        val (user, repo, rest) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
    }

    suspend fun parseRepoUrl(url: String): String? {
        val fixedUrl = url.trim()
        return if (fixedUrl.contains("^https?://".toRegex())) {
            fixedUrl
        } else if (fixedUrl.contains("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex())) {
            fixedUrl.replace("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex(), "").let {
                return@let if (!it.contains("^https?://".toRegex()))
                    "https://${it}"
                else fixedUrl
            }
        } else if (fixedUrl.matches("^[a-zA-Z0-9!_-]+$".toRegex())) {
            safeAsync {
                if (fixedUrl.startsWith("!")) {
                    val response = app.get("https://py.md/${fixedUrl.removePrefix("!")}", allowRedirects = false)
                    val url = response.headers["Location"] ?: return@safeAsync null
                    if (url.startsWith("https://py.md/404")) return@safeAsync null
                    if (url.removeSuffix("/") == "https://py.md") return@safeAsync null
                    return@safeAsync url
                } else {
                    val response = app.get("https://cutt.ly/${fixedUrl}", allowRedirects = false)
                    val url = response.headers["Location"] ?: return@safeAsync null
                    if (url.startsWith("https://cutt.ly/404")) return@safeAsync null
                    if (url.removeSuffix("/") == "https://cutt.ly") return@safeAsync null
                    return@safeAsync url
                }
            }
        } else null
    }

    suspend fun parseRepository(url: String): Repository? {
        return safeAsync {

            app.get(convertRawGitUrl(url), cacheTime = 5, cacheUnit = TimeUnit.MINUTES)
                .parsedSafe<Repository>()
        }
    }

    private suspend fun parsePlugins(pluginUrls: String): List<SitePlugin> {

        return try {
            app.get(convertRawGitUrl(pluginUrls), cacheTime = 5, cacheUnit = TimeUnit.MINUTES)
                .parsed<Array<SitePlugin>>().toList()
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
        val repo = parseRepository(repositoryData.url) ?: return null
        val list = repo.pluginLists.amap { url ->
            parsePlugins(url).map {
                PluginWrapper(repo, repositoryData, it)
            }
        }.flatten()
        return list
    }

    suspend fun downloadPluginToFile(
        context: Context,
        pluginUrl: String,
        file: File,
        expectedFileHash: String?
    ): File? {
        return safeAsync {
            val parentDir = file.parentFile ?: return@safeAsync null
            parentDir.mkdirs()

            val tempFile = File.createTempFile(file.name, ".tmp", context.cacheDir)

            val body = app.get(convertRawGitUrl(pluginUrl)).okhttpResponse.body

            body.byteStream().use { body ->
                tempFile.outputStream().use { fileSteam ->
                    body.copyTo(fileSteam)
                }
            }

            if (expectedFileHash != null) {
                val downloadHash = sha256(tempFile)
                if (expectedFileHash != downloadHash) {
                    tempFile.delete()
                    throw IllegalStateException("Extension hash mismatch when validating '${file.name}'! Expected: '$expectedFileHash', got: '$downloadHash'.")
                }
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            file
        }
    }

    fun getRepositories(): Array<RepositoryData> {
        return getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()
    }

    private val repoLock = Mutex()
    suspend fun addRepository(repository: RepositoryData) {
        repoLock.withLock {
            val currentRepos = getRepositories()

            setKey(REPOSITORIES_KEY, (currentRepos + repository).distinctBy { it.url })
        }
    }

    suspend fun removeRepository(context: Context, repository: RepositoryData) {
        val extensionsDir = File(context.filesDir, ONLINE_PLUGINS_FOLDER)

        repoLock.withLock {
            val currentRepos = getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()

            val newRepos = currentRepos.filter { it.url != repository.url }
            setKey(REPOSITORIES_KEY, newRepos)
        }

        val file = File(
            extensionsDir,
            getPluginSanitizedFileName(repository.url)
        )

        safe {
            file.listFiles { plugin: File ->
                unloadPlugin(plugin.absolutePath)
                false
            }
        }

        PluginManager.deleteRepositoryData(file.absolutePath)
    }
}
