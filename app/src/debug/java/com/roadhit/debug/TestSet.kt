package com.roadhit.debug

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One spoken test answer from `testdata/voice/lines.json` (packaged as the debug asset `lines.json`). */
@Serializable
data class TestLine(val n: Int, val say: String)

/** The voice test set: the lines to say and where their recordings live. */
object TestSet {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadLines(context: Context): List<TestLine> =
        context.assets.open("lines.json").bufferedReader().use { json.decodeFromString(it.readText()) }

    /** `getExternalFilesDir(null)/testset` — pullable with adb without root. */
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "testset")

    /** Resolves a file name (`07.wav`) in [dir], rejecting paths that leave it. */
    fun file(context: Context, name: String): File {
        require(name.isNotBlank() && '/' !in name && name != "..") { "bad file name: $name" }
        return File(dir(context), name)
    }

    fun recording(context: Context, n: Int): File = File(dir(context), "%02d.wav".format(n))
}
