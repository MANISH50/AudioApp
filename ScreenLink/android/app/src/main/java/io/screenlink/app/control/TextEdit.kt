package io.screenlink.app.control

/** Text-field editing for laptop typing: the new text and cursor position. Pure logic, unit-tested. */
object TextEdit {
    data class Result(val text: String, val cursor: Int)

    private fun range(text: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val s = if (selStart in 0..text.length) selStart else text.length
        val e = if (selEnd in 0..text.length) selEnd else s
        return min(s, e) to maxOf(s, e)
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    fun insert(text: String, selStart: Int, selEnd: Int, insert: String): Result {
        val (a, b) = range(text, selStart, selEnd)
        return Result(text.substring(0, a) + insert + text.substring(b), a + insert.length)
    }

    fun backspace(text: String, selStart: Int, selEnd: Int): Result {
        val (a, b) = range(text, selStart, selEnd)
        if (a != b) return Result(text.substring(0, a) + text.substring(b), a)
        if (a == 0) return Result(text, 0)
        val from = text.offsetByCodePoints(a, -1)
        return Result(text.substring(0, from) + text.substring(a), from)
    }
}
