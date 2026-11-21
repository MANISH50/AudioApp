package io.screenlink.app.keyboard

/**
 * Turns an edit of the phone's text field into what the laptop must type: some backspaces, then new text. Works with
 * every keyboard app and language, including word suggestions and autocorrect (which replace whole words).
 */
object TextDiff {
    data class Edit(val backspaces: Int, val insert: String)

    fun diff(old: String, new: String): Edit {
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        // Never split a surrogate pair (emoji etc.).
        if (prefix > 0 && prefix < old.length && Character.isLowSurrogate(old[prefix]) && Character.isHighSurrogate(old[prefix - 1])) prefix--
        val removed = old.substring(prefix)
        return Edit(removed.codePointCount(0, removed.length), new.substring(prefix))
    }
}
