package com.roadhit.game

private const val ALL_RIGHT_BONUS = 2

/** One point each for title, artist and year, plus a bonus when all three are right. */
fun score(m: FieldMatches): Int {
    val points = listOf(m.title, m.artist, m.year).count { it }
    return if (points == 3) points + ALL_RIGHT_BONUS else points
}
