data class Verse(val id: Int, val text: String, val page: Int)
data class SurahWithVerses(
    val id: Int,
    val name: String,
    val page: Int,
    val verses: List<Verse>
)
