package ani.sanin.cloudstream

import android.content.Context
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object CsRepos {

    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<NetworkHelper>().client

    fun repos(): Set<String> = PrefManager.getVal(PrefName.CloudStreamRepos)

    fun addRepo(url: String) {
        PrefManager.setVal(PrefName.CloudStreamRepos, repos() + url)
    }

    fun removeRepo(url: String) {
        PrefManager.setVal(PrefName.CloudStreamRepos, repos() - url)
    }

    suspend fun fetchManifest(repoUrl: String): CsRepoManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(repoUrl).build()
        val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        json.decodeFromString<CsRepoManifest>(body)
    }

    suspend fun resolveRepoUrl(input: String): String? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return@withContext trimmed
        }
        if (!trimmed.matches(Regex("^[a-zA-Z0-9!_-]+$"))) {
            return@withContext null
        }
        val shortener = if (trimmed.startsWith("!")) {
            "https://py.md/" + trimmed.removePrefix("!")
        } else {
            "https://cutt.ly/$trimmed"
        }
        runCatching {
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            noRedirectClient.newCall(Request.Builder().url(shortener).build()).execute().use { response ->
                val location = response.header("Location") ?: return@runCatching null
                if (location.startsWith("https://py.md/404") ||
                    location.removeSuffix("/") == "https://py.md"
                ) {
                    return@runCatching null
                }
                if (location.startsWith("https://cutt.ly/404") ||
                    location.removeSuffix("/") == "https://cutt.ly"
                ) {
                    return@runCatching null
                }
                location
            }
        }.getOrNull()
    }

    suspend fun fetchPlugins(pluginListUrl: String): List<CsSource> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(pluginListUrl).build()
            val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            json.decodeFromString<List<CsSource>>(body)
        }.getOrDefault(emptyList())
    }

    suspend fun getRepoPlugins(repoUrl: String): List<CsSource> {
        val manifest = runCatching { fetchManifest(repoUrl) }.getOrNull() ?: return emptyList()
        return manifest.pluginLists.flatMap { fetchPlugins(it) }
    }

    fun baseUrl(repoUrl: String): String {
        val idx = repoUrl.lastIndexOf('/')
        return if (idx > 0) repoUrl.substring(0, idx + 1) else repoUrl
    }

    fun sourceUrl(repoUrl: String, file: String): String {
        if (file.startsWith("http")) return file
        return baseUrl(repoUrl) + file.removePrefix("/")
    }

    fun pluginsDir(context: Context): File =
        File(context.filesDir, "cs_plugins").apply { mkdirs() }

    fun installed(context: Context): List<CsInstalledSource> {
        val list = PrefManager.getVal<List<String>>(PrefName.CloudStreamInstalledSources)
        return list.mapNotNull {
            runCatching { json.decodeFromString<CsInstalledSource>(it) }.getOrNull()
        }
    }

    fun installedFile(context: Context, source: CsInstalledSource): File =
        File(pluginsDir(context), "${source.id}_${source.version}.cs3")

    suspend fun install(context: Context, repoUrl: String, source: CsSource): CsInstalledSource =
        withContext(Dispatchers.IO) {
            val url = sourceUrl(repoUrl, source.url)
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).execute().use { it.body?.bytes() ?: ByteArray(0) }
            val iconUrl = source.iconUrl?.let {
                if (it.startsWith("http")) it else sourceUrl(repoUrl, it)
            }
            val installed = CsInstalledSource(
                id = source.id,
                name = source.name,
                version = source.version,
                type = source.type,
                lang = source.lang,
                url = source.url,
                repoUrl = repoUrl,
                iconUrl = iconUrl
            )

            installedFile(context, installed).setWritable(true)
            installedFile(context, installed).writeBytes(body)
            val current = PrefManager.getVal<List<String>>(PrefName.CloudStreamInstalledSources)
                .filterNot {
                    runCatching { json.decodeFromString<CsInstalledSource>(it).id }.getOrNull() == source.id
                }
            PrefManager.setVal(
                PrefName.CloudStreamInstalledSources,
                current + json.encodeToString(installed)
            )
            installed
        }

    fun uninstall(context: Context, source: CsInstalledSource) {
        installedFile(context, source).delete()
        val current = PrefManager.getVal<List<String>>(PrefName.CloudStreamInstalledSources)
        PrefManager.setVal(
            PrefName.CloudStreamInstalledSources,
            current.filterNot {
                runCatching { json.decodeFromString<CsInstalledSource>(it).id }.getOrNull() == source.id
            }
        )
    }

    fun saveOrder(ordered: List<CsInstalledSource>) {
        PrefManager.setVal(
            PrefName.CloudStreamInstalledSources,
            ordered.map { json.encodeToString(it) }
        )
    }
}
