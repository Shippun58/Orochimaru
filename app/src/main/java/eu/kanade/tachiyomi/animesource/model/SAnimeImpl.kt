@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SAnimeImpl : SAnime {

    override lateinit var url: String

    override lateinit var title: String

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var background_url: String? = null

    override var initialized: Boolean = false

    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE

    override var fetch_type: FetchType = FetchType.Episodes

    override var season_number: Double = -1.0

    @Transient
    override var memo: JsonObject = JsonObject(emptyMap())

    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        out.writeObject(memo.toString())
    }

    private fun readObject(`in`: ObjectInputStream) {
        `in`.defaultReadObject()
        memo = runCatching {
            val raw = `in`.readObject() as? String ?: "{}"
            Json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
    }
}
