package tachiyomi.domain.entries

enum class TriStateFilter {
    DISABLED,
    ENABLED_IS,
    ENABLED_NOT,
    ;

    fun next(): TriStateFilter {
        return when (this) {
            DISABLED -> ENABLED_IS
            ENABLED_IS -> ENABLED_NOT
            ENABLED_NOT -> DISABLED
        }
    }
}

inline fun applyFilter(filter: TriStateFilter, predicate: () -> Boolean): Boolean = when (filter) {
    TriStateFilter.DISABLED -> true
    TriStateFilter.ENABLED_IS -> predicate()
    TriStateFilter.ENABLED_NOT -> !predicate()
}
