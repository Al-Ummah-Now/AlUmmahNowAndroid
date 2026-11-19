package net.alummahnow.design.format

/**
 * The avatar fallback letter: the first letter of the first word, upper-cased. An Arabic «ال» article is skipped when
 * the word is longer than it («الأمة الآن» → «أ», not «ا»), as on Apple.
 */
fun avatarInitial(name: String): String {
    val word = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    val stem = if (word.startsWith("ال") && word.length > 2) word.drop(2) else word
    return stem.take(1).uppercase()
}
