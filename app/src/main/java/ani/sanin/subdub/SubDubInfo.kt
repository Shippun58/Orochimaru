package ani.sanin.subdub

data class SubDubInfo(
    val sub: Int = 0,
    val dub: Int = 0,
    val total: Int = 0,
) {
    // The Worker can know the number of released sub episodes before AniList
    // knows the series' final episode total (common for ongoing anime).
    // Do not hide that valid result just because total is still unknown.
    val hasData: Boolean get() = total > 0 || sub > 0 || dub > 0
}
