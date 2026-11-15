package com.roadhit.game

import com.roadhit.catalog.Catalog
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Judges the 30 recordings of the owner's voice the way the game engine does:
 * Hebrew alternatives first; if title or artist is unmatched or no year was heard,
 * again with the Hebrew alternatives followed by the English ones.
 */
class TranscriptSetTest {
    companion object {
        /**
         * Lines expected to still be judged wrong (at most 3; each is asserted to still fail).
         * 15: "99 Luftballons של Nena" - the Hebrew pass heard the artist as "לנה" (2 edits from "נינה",
         *     whose 4 letters allow 1) and the English pass as "shelena" (4+ edits from "nena"); neither
         *     is a general spelling of Nena, so no alias covers it.
         */
        val KNOWN_MISSES = setOf(15)
        const val TOLERANCE = 5
    }

    private val seed by lazy {
        Catalog.parse(javaClass.classLoader!!.getResource("seed-catalog.json")!!.readText())
    }

    /** The catalog that ships in the app (Gradle runs unit tests with app/ as the working directory). */
    private val bundled by lazy { Catalog.parse(File("src/main/assets/catalog.json").readText()) }
    private val judge = LocalAnswerJudge()

    private class Line(val n: Int, val songId: String?, val expect: JsonObject, val he: List<String>, val en: List<String>)

    private fun lines(): List<Line> {
        // Gradle runs unit tests with app/ as the working directory.
        val lineJson = Json.parseToJsonElement(File("../testdata/voice/lines.json").readText()).jsonArray
        val transcripts = Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("transcripts.json")!!.readText()
        ).jsonArray.associateBy { it.jsonObject["n"]!!.jsonPrimitive.int }
        return lineJson.map { el ->
            val o = el.jsonObject
            val n = o["n"]!!.jsonPrimitive.int
            val t = transcripts[n]!!.jsonObject
            Line(
                n = n,
                songId = o["songId"]!!.jsonPrimitive.contentOrNull,
                expect = o["expect"]!!.jsonObject,
                he = t["he"]!!.jsonArray.map { it.jsonPrimitive.content },
                en = t["en"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    /** Null when the line is judged correctly, else a description of the miss. */
    private fun check(line: Line, catalog: Catalog): String? {
        val top = "he=${line.he.take(2)} en=${line.en.take(2)}"
        if (line.songId == null) {
            val expected = VoiceCommand.valueOf(line.expect["command"]!!.jsonPrimitive.content)
            val actual = parseCommand(line.he)
            return if (actual == expected) null else "line ${line.n}: expected $expected, got $actual; $top"
        }
        val song = catalog.byId(line.songId) ?: return "line ${line.n}: no song ${line.songId}"
        var result = judge.judge(song, line.he, TOLERANCE)
        if (!result.title || !result.artist || heardYear(line.he) == null) {
            result = judge.judge(song, line.he + line.en, TOLERANCE)
        }
        val spokenYear = line.expect["year"]!!.jsonPrimitive.intOrNull
        val expected = FieldMatches(
            title = line.expect["title"]!!.jsonPrimitive.boolean,
            artist = line.expect["artist"]!!.jsonPrimitive.boolean,
            year = spokenYear != null && abs(spokenYear - song.year) <= TOLERANCE,
        )
        return if (result == expected) null else "line ${line.n}: expected $expected, got $result; $top"
    }

    @Test fun recordedLinesAreJudgedCorrectly() = assertJudged(seed)

    /** The same lines over the catalog that ships (final review M3). */
    @Test fun recordedLinesAreJudgedCorrectlyAgainstBundledCatalog() = assertJudged(bundled)

    private fun assertJudged(catalog: Catalog) {
        val all = lines()
        assertTrue(all.size == 30)
        assertTrue("KNOWN_MISSES may hold at most 3 lines", KNOWN_MISSES.size <= 3)
        val misses = all.associate { it.n to check(it, catalog) }.filterValues { it != null }
        val unexpected = misses.filterKeys { it !in KNOWN_MISSES }.values
        val stale = KNOWN_MISSES.filter { it !in misses }
        val message = buildString {
            if (unexpected.isNotEmpty()) appendLine("Lines judged wrong:\n" + unexpected.joinToString("\n"))
            if (stale.isNotEmpty()) appendLine("KNOWN_MISSES lines that now pass (remove them): $stale")
        }
        assertTrue(message, unexpected.isEmpty() && stale.isEmpty())
    }
}
