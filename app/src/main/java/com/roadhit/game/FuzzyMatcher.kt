package com.roadhit.game

object MatchThresholds {
    /** Number of single-character edits tolerated for a needle of [len] characters. */
    fun allowedEdits(len: Int): Int = when {
        len <= 3 -> 0
        len <= 5 -> 1
        else -> len / 4
    }
}

/**
 * True if [needle] appears in [haystack] as a run of whole words, allowing up to [allowed]
 * character edits (by default as many as [MatchThresholds] gives the needle's length).
 * Number tokens (all digits) are never edited: the run must hold exactly the needle's numbers.
 * Both arguments must already be normalized.
 */
fun containsClose(haystack: String, needle: String, allowed: Int = MatchThresholds.allowedEdits(needle.length)): Boolean {
    if (needle.isEmpty()) return false
    val words = haystack.split(' ').filter { it.isNotEmpty() }
    val needleTokens = needle.split(' ').filter { it.isNotEmpty() }
    val needleNumbers = numberTokens(needleTokens)
    val needleWords = needleTokens.size
    for (size in setOf(needleWords, needleWords + 1, maxOf(1, needleWords - 1))) {
        for (start in 0..words.size - size) {
            val run = words.subList(start, start + size)
            if (numberTokens(run) == needleNumbers && levenshtein(run.joinToString(" "), needle) <= allowed) return true
        }
    }
    return false
}

private fun numberTokens(tokens: List<String>) = tokens.filter { t -> t.all(Char::isDigit) }

private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}
