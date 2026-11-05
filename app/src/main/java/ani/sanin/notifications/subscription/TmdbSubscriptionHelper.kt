package ani.sanin.notifications.subscription

import ani.sanin.settings.saving.PrefManager

class TmdbSubscriptionHelper {
    companion object {
        private const val SUBSCRIPTIONS = "tmdb_subscriptions"

        @kotlinx.serialization.Serializable
        data class TmdbSubscriptionItem(
            val id: Int,
            val name: String,
            val type: String,
            val image: String? = null,
            val banner: String? = null,

            val lastSeason: Int = 0,
            val lastEpisode: Int = 0
        ) : java.io.Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun getSubscriptions(): Map<Int, TmdbSubscriptionItem> =
            (PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? Map<Int, TmdbSubscriptionItem>)
                ?: mapOf<Int, TmdbSubscriptionItem>().also { PrefManager.setCustomVal(SUBSCRIPTIONS, it) }

        @Suppress("UNCHECKED_CAST")
        fun save(media: TmdbSubscriptionItem) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, TmdbSubscriptionItem>
                ?: mutableMapOf()
            data[media.id] = media
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
        }

        @Suppress("UNCHECKED_CAST")
        fun delete(id: Int) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, TmdbSubscriptionItem>
                ?: mutableMapOf()
            data.remove(id)
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
            ani.sanin.notifications.push.PushSync.sync()
        }

        fun isSubscribed(id: Int): Boolean = getSubscriptions().containsKey(id)

        fun subscribe(
            id: Int,
            name: String,
            type: String,
            image: String?,
            banner: String?
        ) {
            val existing = getSubscriptions()[id]
            save(
                TmdbSubscriptionItem(
                    id = id,
                    name = name,
                    type = type,
                    image = image,
                    banner = banner,
                    lastSeason = existing?.lastSeason ?: 0,
                    lastEpisode = existing?.lastEpisode ?: 0
                )
            )
            ani.sanin.notifications.push.PushSync.sync()
        }
    }
}
