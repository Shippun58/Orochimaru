package ani.sanin.cloudstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CsRepoManifest(
    val name: String,
    val description: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("manifestVersion") val manifestVersion: Int = 1,
    @SerialName("pluginLists") val pluginLists: List<String> = emptyList()
)

@Serializable
data class CsSource(
    val url: String,
    val name: String,
    val version: Int = 1,
    @SerialName("internalName") val internalName: String? = null,
    val status: Int = 1,
    @SerialName("apiVersion") val apiVersion: Int = 1,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("tvTypes") val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("fileHash") val fileHash: String? = null,
    @SerialName("jarUrl") val jarUrl: String? = null,
    @SerialName("jarHash") val jarHash: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null
) {
    val id: String get() = internalName ?: name
    val type: String get() = tvTypes.firstOrNull() ?: "other"
    val lang: String get() = language ?: "en"
    val typeLabel: String get() = type.replaceFirstChar { it.uppercase() }
}

@Serializable
data class CsInstalledSource(
    val id: String,
    val name: String,
    val version: Int,
    val type: String,
    val lang: String,
    val url: String,
    val repoUrl: String,
    val iconUrl: String? = null,
    val installedAt: Long = System.currentTimeMillis()
)
