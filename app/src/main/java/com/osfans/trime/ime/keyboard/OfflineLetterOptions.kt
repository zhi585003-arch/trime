package com.osfans.trime.ime.keyboard

/** Built-in fallback also works with themes copied before popup keys were added. */
internal object OfflineLetterOptions {
    private const val letters = "qwertyuiopasdfghjklzxcvbnm"
    private val symbols = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "~", "@", "#", "!", "%", "&", "*", "(", ")", "`", "/", "-", "_", ":", ";", "?")
    fun popup(click: String): List<String> = if (click.length == 1 && click[0] in letters)
        listOf("OfflineUpper$click", "OfflineSym$click", "OfflineLower$click") else emptyList()
    fun literal(action: String): String? {
        val letter = action.lastOrNull() ?: return null
        val index = letters.indexOf(letter)
        if (index < 0) return null
        return when (action) {
            "OfflineUpper$letter" -> letter.uppercaseChar().toString()
            "OfflineLower$letter" -> letter.toString()
            "OfflineSym$letter" -> symbols[index]
            else -> null
        }
    }
}
