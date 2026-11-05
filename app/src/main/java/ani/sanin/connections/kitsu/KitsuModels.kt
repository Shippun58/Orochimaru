package ani.sanin.connections.kitsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class KitsuDocument(
    @SerialName("data") val data: JsonElement? = null,
    @SerialName("included") val included: List<KitsuResource>? = null,
    @SerialName("meta") val meta: KitsuMeta? = null,
)

@Serializable
data class KitsuMeta(
    @SerialName("count") val count: Int = 0,
)

fun KitsuDocument.dataList(): List<KitsuResource> = when (val d = data) {
    is JsonArray -> d.mapNotNull { element ->
        element.jsonObjectOrNull?.let { kitsuJson.decodeFromJsonElement<KitsuResource>(it) }
    }
    is JsonObject -> listOfNotNull(
        kitsuJson.decodeFromJsonElement<KitsuResource>(d)
    )
    else -> emptyList()
}

fun KitsuDocument.dataOne(): KitsuResource? = dataList().firstOrNull()

private val JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

@Serializable
data class KitsuResource(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,

    @SerialName("attributes") val attributes: JsonElement? = null,
    @SerialName("relationships") val relationships: Map<String, KitsuRelationship>? = null,
)

@Serializable
data class KitsuRelationship(
    @SerialName("links") val links: KitsuLinks? = null,

    @SerialName("data") val data: JsonElement? = null,
)

fun KitsuResource.relationshipIds(name: String): List<String> =
    when (val related = relationships?.get(name)?.data) {
        is JsonArray -> related.mapNotNull { id ->
            (id as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }
        is JsonObject -> listOfNotNull(related["id"]?.jsonPrimitive?.contentOrNull)
        else -> emptyList()
    }

@Serializable
data class KitsuLinks(
    @SerialName("related") val related: String? = null,
)

@Serializable
data class KitsuAnimeAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("startDate") val startDate: String? = null,
    @SerialName("seasonYear") val seasonYear: Int? = null,
    @SerialName("season") val season: String? = null,
    @SerialName("showType") val showType: String? = null,
    @SerialName("subtype") val subtype: String? = null,
    @SerialName("posterImage") val posterImage: KitsuPosterImage? = null,
    @SerialName("coverImage") val coverImage: KitsuPosterImage? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("synopsis") val synopsis: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("userCount") val userCount: Int? = null,
    @SerialName("nsfw") val nsfw: Boolean = false,
    @SerialName("episodeCount") val episodeCount: Int? = null,
    @SerialName("averageRating") val averageRating: String? = null,
)

data class KitsuDiscoveryItem(
    val id: String,
    val title: String,
    val synopsis: String?,
    val year: Int?,
    val userCount: Int?,
    val score: Double?,
    val posterUrl: String?,
    val anilistId: Int?,
)

data class KitsuCategoryPage(
    val items: List<KitsuDiscoveryItem>,
    val rawCount: Int,
    val hasMore: Boolean,
    val success: Boolean,
)

data class KitsuCategory(
    val id: String,
    val slug: String,
    val title: String,
    val description: String? = null,
    val totalMediaCount: Int = 0,
    val nsfw: Boolean = false,
    val childCount: Int = 0,
    val mediaCount: Int = 0,
    val parentId: String? = null,
    val parentTitle: String? = null,
)

@Serializable
data class KitsuCategoryAttributes(
    @SerialName("title") val title: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("nsfw") val nsfw: Boolean = false,
    @SerialName("childCount") val childCount: Int = 0,
    @SerialName("totalMediaCount") val totalMediaCount: Int = 0,
)

fun KitsuResource.categoryAttributes(): KitsuCategoryAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuCategoryAttributes>(it) }

@Serializable
data class KitsuTitles(
    @SerialName("en") val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
)

@Serializable
data class KitsuInstallmentAttributes(
    @SerialName("position") val position: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
)

fun KitsuResource.animeAttributes(): KitsuAnimeAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuAnimeAttributes>(it) }

fun KitsuResource.installmentAttributes(): KitsuInstallmentAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuInstallmentAttributes>(it) }

@Serializable
data class KitsuFranchiseAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
)

fun KitsuResource.franchiseAttributes(): KitsuFranchiseAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuFranchiseAttributes>(it) }

@Serializable
data class KitsuMappingAttributes(
    @SerialName("externalSite") val externalSite: String? = null,
    @SerialName("externalId") val externalId: String? = null,
)

fun KitsuResource.mappingAttributes(): KitsuMappingAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuMappingAttributes>(it) }

@Serializable
data class KitsuPosterImage(
    @SerialName("tiny") val tiny: String? = null,
    @SerialName("small") val small: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("original") val original: String? = null,
)

internal val kitsuJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
