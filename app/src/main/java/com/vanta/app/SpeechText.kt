package com.vanta.app

/** Cleans a response for speech without changing the Markdown shown in chat. */
object SpeechText {
    fun clean(input: String): String {
        var text = input
            .replace(Regex("(?s)```.*?(?:```|$)"), " ")
            .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("<https?://[^>]+>"), " ")
            .replace(Regex("\\bhttps?://\\S+"), " ")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace(Regex("(?m)^\\s*[-+•▪◦]\\s+"), "")
            .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
            .replace(Regex("(?<!\\w)\\*{1,3}|\\*{1,3}(?!\\w)|(?<!\\w)_{1,3}|_{1,3}(?!\\w)|~{1,2}|`+"), "")
            .replace(Regex("<[^>]+>"), " ")

        val withoutEmoji = StringBuilder(text.length)
        text.codePoints().forEach { codePoint ->
            if (!isEmojiCodePoint(codePoint)) withoutEmoji.appendCodePoint(codePoint)
        }
        text = withoutEmoji.toString()

        return text
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2300..0x23FF ||
            codePoint in 0x1F1E6..0x1F1FF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint == 0xFE0F || codePoint == 0x200D || codePoint == 0x20E3
}
