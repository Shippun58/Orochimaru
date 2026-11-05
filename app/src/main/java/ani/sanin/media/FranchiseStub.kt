package ani.sanin.media

object FranchiseStub {

    private val ORDERED_RELATIONS = setOf("PREQUEL", "SEQUEL", "PARENT")

    private fun Media.isOrderedRelation() =
        relation?.substringBefore('\n')?.trim() in ORDERED_RELATIONS

    private const val MAX_ENTRIES = 24

    fun fromMedia(seed: Media): Franchise? {
        val name = seed.userPreferredName.takeIf { it.isNotBlank() }
            ?: seed.name?.takeIf { it.isNotBlank() }
            ?: return null

        val related = seed.relations.orEmpty()
            .filter { it.isOrderedRelation() }
            .filter { it.id != seed.id }
        if (related.isEmpty()) return null

        val entries = (listOf(seed) + related)
            .distinctBy { it.id }
            .sortedBy { it.startDate?.year }
            .map { it.toEntry() }
            .take(MAX_ENTRIES)

        return Franchise(
            name = name,

            bannerUrl = entries.last().posterUrl,
            entries = entries,
        )
    }

    private fun Media.toEntry() = FranchiseEntry(
        year = startDate?.year?.toString().orEmpty(),
        sortYear = startDate?.year,
        posterUrl = cover,
        title = userPreferredName.ifBlank { name.orEmpty() },

        type = relation?.substringBefore('\n')?.trim()?.let { FranchiseType.fromRelation(it) }
            ?: FranchiseType.fromFormat(format?.uppercase(java.util.Locale.US)),
    )
}
