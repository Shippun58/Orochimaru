package ani.sanin.subdub

data class SubDubInfo(
    val sub: Int = 0,
    val dub: Int = 0,
    val total: Int = 0,
) {
    val hasData: Boolean get() = total > 0
}
