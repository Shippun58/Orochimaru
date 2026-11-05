package eu.kanade.tachiyomi.extension.api

import ani.sanin.asyncMap
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import java.io.ByteArrayInputStream
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private fun cleanRepoUrl(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    private fun List<ExtensionSourceJsonObject>.toAnimeExtensionSources(): List<AvailableAnimeSources> {
        return this.map {
            AvailableAnimeSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toAnimeExtensions(repository: String): List<AnimeExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                val majorLibVersion = libVersion.toInt()
                majorLibVersion >= ExtensionLoader.ANIME_LIB_VERSION_MIN && majorLibVersion <= ExtensionLoader.ANIME_LIB_VERSION_MAX
            }
            .map {
                AnimeExtension.Available(
                    name = it.name.removePrefix("Aniyomi: ").removePrefix("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toAnimeExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = it.iconUrl ?: "$cleanRepo/icon/${it.pkg}.png",
                )
            }
    }

    private fun updateStoreUrl(oldUrl: String, newUrl: String) {
        val current = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
        if (current.contains(oldUrl)) {
            val updated = current.minus(oldUrl).plus(newUrl)
            PrefManager.setVal(PrefName.AnimeExtensionRepos, updated)
        }
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (this.size < 2) return this
        val isGzip = (this[0].toInt() and 0xFF == 0x1F) && (this[1].toInt() and 0xFF == 0x8B)
        if (!isGzip) return this
        return try {
            ByteArrayInputStream(this).source().gzip().buffer().readByteArray()
        } catch (e: Throwable) {
            this
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun fetchExtensions(
        repoUrl: String,
        originalUrl: String = repoUrl
    ): List<ExtensionJsonObject> {
        var targetUrl = repoUrl.trim()
        if (!targetUrl.endsWith(".json") && !targetUrl.endsWith(".pb")) {
            targetUrl = "${cleanRepoUrl(repoUrl)}/index.pb"
        }

        try {
            val response = try {
                networkService.client
                    .newCall(GET(targetUrl))
                    .awaitSuccess()
            } catch (e: Throwable) {
                if (targetUrl.endsWith("index.pb")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/repo.json"
                    try {
                        networkService.client.newCall(GET(fallback)).awaitSuccess()
                    } catch (_: Throwable) {
                        val minFallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                        networkService.client.newCall(GET(minFallback)).awaitSuccess()
                    }
                } else if (targetUrl.endsWith("repo.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                    try {
                        networkService.client.newCall(GET(fallback)).awaitSuccess()
                    } catch (_: Throwable) {
                        val pbFallback = "${cleanRepoUrl(targetUrl)}/index.pb"
                        networkService.client.newCall(GET(pbFallback)).awaitSuccess()
                    }
                } else if (targetUrl.endsWith("index.min.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.pb"
                    networkService.client.newCall(GET(fallback)).awaitSuccess()
                } else {
                    throw e
                }
            }

            val rawBytes = response.body.bytes()
            if (rawBytes.isEmpty()) return emptyList()

            val responseBytes = rawBytes.decompressIfGzipped()
            if (responseBytes.isEmpty()) return emptyList()
            val firstByte = responseBytes[0]

            if (firstByte == 0x5B.toByte()) {
                val bodyString = responseBytes.toString(Charsets.UTF_8)
                return json.decodeFromString<List<ExtensionJsonObject>>(bodyString)
            } else {
                val store = if (firstByte == 0x7B.toByte()) {
                    val bodyString = responseBytes.toString(Charsets.UTF_8)
                    if (bodyString.contains("\"index_v2\"") || bodyString.contains("\"indexV2\"")) {
                        val legacyRepo = json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                        val nextUrl = legacyRepo.indexV2
                        if (nextUrl != null) {
                            updateStoreUrl(originalUrl, nextUrl)
                            return fetchExtensions(nextUrl, originalUrl)
                        }
                    }
                    json.decodeFromString<NetworkExtensionStore>(bodyString)
                } else {

                    runCatching {
                        ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(responseBytes)
                    }.getOrNull() ?: return emptyList()
                }

                val resolvedList: NetworkExtensionStore.ExtensionList? = if (store.extensionListUrl != null) {
                    val listUrl = if (store.extensionListUrl.startsWith("http")) {
                        store.extensionListUrl
                    } else {
                        "${cleanRepoUrl(targetUrl)}/${store.extensionListUrl.removePrefix("/")}"
                    }
                    val listResponse = runCatching {
                        networkService.client.newCall(GET(listUrl)).awaitSuccess()
                    }.getOrNull()
                    val listBytes = listResponse?.body?.bytes()?.decompressIfGzipped()
                    if (listBytes != null && listBytes.isNotEmpty() && listBytes[0] == 0x7B.toByte()) {
                        runCatching {
                            json.decodeFromString<NetworkExtensionStore.ExtensionList>(listBytes.toString(Charsets.UTF_8))
                        }.getOrNull()
                    } else if (listBytes != null && listBytes.isNotEmpty()) {
                        runCatching {
                            ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(listBytes)
                        }.getOrNull()
                    } else {
                        null
                    }
                } else {
                    store.extensionList
                }

                if (resolvedList != null) {
                    return resolvedList.extensions.map { ext ->
                        val sourcesMapped = ext.sources.map { src ->
                            ExtensionSourceJsonObject(
                                id = src.id,
                                lang = src.language,
                                name = src.name,
                                baseUrl = src.homeUrl
                            )
                        }
                        val primaryLang = ext.sources.firstOrNull()?.language ?: "all"
                        val prefixName = if (ext.name.startsWith("Aniyomi: ")) ext.name else "Aniyomi: ${ext.name}"
                        ExtensionJsonObject(
                            name = prefixName,
                            pkg = ext.packageName,
                            apk = ext.resources.apkUrl,
                            lang = primaryLang,
                            code = ext.versionCode,
                            version = ext.versionName,
                            nsfw = if (ext.contentWarning == NetworkExtensionStore.ContentWarning.NSFW || ext.contentWarning == NetworkExtensionStore.ContentWarning.MIXED) 1 else 0,
                            hasReadme = 0,
                            hasChangelog = 0,
                            sources = sourcesMapped,
                            iconUrl = ext.resources.iconUrl,
                            extensionLib = ext.extensionLib,
                        )
                    }
                } else if (targetUrl.endsWith("repo.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                    return fetchExtensions(fallback, originalUrl)
                }
            }
        } catch (e: Throwable) {
            Logger.log("Failed to fetch extensions from $repoUrl: $e")
        }
        return emptyList()
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> {
        return withIOContext {

            val extensions: ArrayList<AnimeExtension.Available> = arrayListOf()

            val repos =
                PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toMutableList()

            repos.asyncMap {
                try {
                    var repoExtensions = fetchExtensions(it)
                    if (repoExtensions.isEmpty()) {
                        val fallback = fallbackRepoUrl(it)
                        if (fallback != null) {
                            repoExtensions = fetchExtensions(fallback)
                        }
                    }
                    extensions.addAll(repoExtensions.toAnimeExtensions(it))
                } catch (e: Throwable) {
                    Logger.log("Failed to get anime extensions")
                    Logger.log(e)
                }
            }

            extensions
        }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
        }
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        var fallbackRepoUrl = "https://gcore.jsdelivr.net/gh/"
        val strippedRepoUrl = cleanRepoUrl(repoUrl)
            .removePrefix("https://")
            .removePrefix("http://")
        val repoUrlParts = strippedRepoUrl.split("/")
        if (repoUrlParts.size < 3) {
            return null
        }
        val repoOwner = repoUrlParts[1]
        val repoName = repoUrlParts[2]
        fallbackRepoUrl += "$repoOwner/$repoName"
        val repoBranch = if (repoUrlParts.size > 3) {
            repoUrlParts[3]
        } else {
            "main"
        }
        fallbackRepoUrl += "@$repoBranch"
        return fallbackRepoUrl
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "all",
    val code: Long = 0,
    val version: String = "1.0",
    val nsfw: Int = 0,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<ExtensionSourceJsonObject>? = null,
    val iconUrl: String? = null,
    val extensionLib: String? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private fun ExtensionJsonObject.extractLibVersion(): Double {
    extensionLib?.toDoubleOrNull()?.let { return it }
    val parts = version.split('.')
    return if (parts.size >= 2) {
        val majorMinor = "${parts[0]}.${parts[1]}"
        majorMinor.toDoubleOrNull() ?: parts[0].toDoubleOrNull() ?: 1.0
    } else {
        version.toDoubleOrNull() ?: 1.0
    }
}
