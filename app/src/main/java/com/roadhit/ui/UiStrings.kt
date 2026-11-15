package com.roadhit.ui

import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.phrasesFor

/**
 * Every text the phone screens show, in the app's language ([com.roadhit.game.Prefs.lang]),
 * which is an in-app setting rather than the phone's locale, so these are not Android resources.
 */
interface UiStrings {
    val appName: String
    val start: String
    val stop: String
    val settings: String
    val back: String

    val stopConfirmTitle: String
    val stopConfirmText: String
    val stopConfirmYes: String
    val stopConfirmNo: String

    fun turn(name: String): String
    fun round(round: Int, rounds: Int): String
    val statusSetup: String
    val statusClip: String
    val statusListening: String
    val statusReveal: String
    val statusPaused: String
    val scoreboard: String
    val finalScores: String
    val winner: String
    val currentPlayer: String
    fun year(year: Int): String

    val micNeeded: String
    val openAppSettings: String
    val dismiss: String
    /** The car screen when the microphone permission is missing; the prompt then appears on the phone. */
    val carMicNeeded: String
    val carMicAllow: String
    /** The car card and media notification when no game runs. */
    val pressPlayToStart: String

    val settingsTitle: String
    val language: String
    val hebrew: String
    val english: String
    /** The next game's choices that the screen can set; the voice setup offers them first. */
    val nextGame: String
    val nextGameHint: String
    val playerCount: String
    fun players(n: Int): String
    val roundCount: String
    fun rounds(n: Int): String
    val genre: String
    val genreAll: String
    val genrePop: String
    val genreRock: String
    val genreHipHop: String
    val genreDance: String
    val genreRnb: String
    val genreMizrahi: String
    val genreOther: String
    val decadesLabel: String
    /** A range of decades by their first years, e.g. (1980, 2000). */
    fun decades(from: Int, to: Int): String
    /** The switch that replaces the decades with an exact range of years. */
    val exactYears: String
    val fromYear: String
    val toYear: String
    val poolIsraeli: String
    val poolInternational: String
    val poolMixed: String
    val yearTolerance: String
    fun years(n: Int): String
    val clipLength: String
    fun seconds(n: Int): String
    val playerNames: String
    /** The name a player gets when their field is empty (also the field's placeholder). */
    fun defaultPlayer(n: Int): String
    val autoStart: String
    /** Shown on the settings screen when the stored preferences cannot be opened. */
    val settingsUnavailable: String
}

object HebrewUi : UiStrings {
    override val appName = "RoadHit"
    override val start = "התחלה"
    override val stop = "עצירה"
    override val settings = "הגדרות"
    override val back = "חזרה"

    override val stopConfirmTitle = "לעצור את המשחק?"
    override val stopConfirmText = "המשחק יישמר ואפשר יהיה להמשיך אותו אחר כך."
    override val stopConfirmYes = "לעצור"
    override val stopConfirmNo = "להמשיך לשחק"

    override fun turn(name: String) = "התור של $name"
    // "2 מתוך 5" rather than "2 / 5": a slash between numbers reads ambiguously in a right-to-left line.
    override fun round(round: Int, rounds: Int) = "סיבוב $round מתוך $rounds"
    override val statusSetup = "מכינים את המשחק…"
    override val statusClip = "מנגן קטע…"
    override val statusListening = "מקשיב… תגידו: התשובה היא…"
    override val statusReveal = "התשובה"
    override val statusPaused = "מושהה"
    override val scoreboard = "ניקוד"
    override val finalScores = "תוצאות סופיות"
    override val winner = "מנצח"
    override val currentPlayer = "בתור"
    override fun year(year: Int) = "שנה: $year"

    override val micNeeded = "כדי לשחק צריך גישה למיקרופון: המשחק שומע את התשובות. אפשר לאשר אותה בהגדרות האפליקציה."
    override val openAppSettings = "פתיחת הגדרות האפליקציה"
    override val dismiss = "סגירה"
    override val carMicNeeded = "כדי לשחק צריך גישה למיקרופון: המשחק שומע את התשובות. כשהרכב עומד, אשרו את הגישה בטלפון."
    override val carMicAllow = "אישור בטלפון"
    override val pressPlayToStart = "לחצו על הפעלה כדי להתחיל"

    override val settingsTitle = "הגדרות"
    override val language = "שפה"
    override val hebrew = "עברית"
    override val english = "אנגלית"
    override val nextGame = "המשחק הבא"
    override val nextGameHint = "אפשר לשנות גם בקול: אומרים \"לשנות\" בתחילת המשחק."
    override val playerCount = "מספר שחקנים"
    override fun players(n: Int) = if (n == 1) "שחקן אחד" else "$n שחקנים"
    override val roundCount = "סיבובים לכל שחקן"
    override fun rounds(n: Int) = if (n == 1) "סיבוב אחד" else "$n סיבובים"
    override val genre = "ז'אנר"
    override val genreAll = "הכול"
    override val genrePop = "פופ"
    override val genreRock = "רוק"
    override val genreHipHop = "היפ הופ"
    override val genreDance = "דאנס ואלקטרוני"
    override val genreRnb = "אר אנד בי וסול"
    override val genreMizrahi = "מזרחית"
    override val genreOther = "אחר"
    override val decadesLabel = "עשורים"
    override fun decades(from: Int, to: Int) = when {
        from == FIRST_DECADE && to == LAST_DECADE -> "כל העשורים"
        from == to -> decade(from)
        else -> "${decade(from)} עד ${decade(to)}"
    }
    private fun decade(d: Int) = "שנות ה-${if (d < 2000) d % 100 else d}"
    override val exactYears = "שנים מדויקות"
    override val fromYear = "משנת"
    override val toYear = "עד שנת"
    override val poolIsraeli = "להיטים ישראליים"
    override val poolInternational = "להיטים בינלאומיים"
    override val poolMixed = "מעורב"
    override val yearTolerance = "טווח שנים לתשובה נכונה"
    override fun years(n: Int) = if (n == 1) "±1 שנה" else "±$n שנים"
    override val clipLength = "אורך הקטע"
    override fun seconds(n: Int) = "$n שניות"
    override val playerNames = "שמות השחקנים"
    override fun defaultPlayer(n: Int) = phrasesFor(Lang.HE).defaultPlayerName(n)
    override val autoStart = "להתחיל לדבר מיד כשהאפליקציה נפתחת"
    override val settingsUnavailable = "אי אפשר לטעון את ההגדרות כרגע. נסו שוב מאוחר יותר."

    override fun toString() = "HebrewUi"
}

object EnglishUi : UiStrings {
    override val appName = "RoadHit"
    override val start = "Start"
    override val stop = "Stop"
    override val settings = "Settings"
    override val back = "Back"

    override val stopConfirmTitle = "Stop the game?"
    override val stopConfirmText = "The game is saved and can be continued later."
    override val stopConfirmYes = "Stop"
    override val stopConfirmNo = "Keep playing"

    override fun turn(name: String) = "$name's turn"
    override fun round(round: Int, rounds: Int) = "Round $round / $rounds"
    override val statusSetup = "Setting up…"
    override val statusClip = "Playing the clip…"
    override val statusListening = "Listening… Say: The answer is…"
    override val statusReveal = "The answer"
    override val statusPaused = "Paused"
    override val scoreboard = "Scores"
    override val finalScores = "Final scores"
    override val winner = "Winner"
    override val currentPlayer = "Now playing"
    override fun year(year: Int) = "Year: $year"

    override val micNeeded = "The game needs the microphone to hear your answers. You can allow it in the app's settings."
    override val openAppSettings = "Open app settings"
    override val dismiss = "Close"
    override val carMicNeeded = "The game needs the microphone to hear your answers. When parked, allow it on your phone."
    override val carMicAllow = "Allow on phone"
    override val pressPlayToStart = "Press play to start"

    override val settingsTitle = "Settings"
    override val language = "Language"
    override val hebrew = "Hebrew"
    override val english = "English"
    override val nextGame = "Next game"
    override val nextGameHint = "You can also change these by voice: say \"change\" when a game starts."
    override val playerCount = "Number of players"
    override fun players(n: Int) = if (n == 1) "1 player" else "$n players"
    override val roundCount = "Rounds per player"
    override fun rounds(n: Int) = if (n == 1) "1 round" else "$n rounds"
    override val genre = "Genre"
    override val genreAll = "All"
    override val genrePop = "Pop"
    override val genreRock = "Rock"
    override val genreHipHop = "Hip-hop"
    override val genreDance = "Dance and electronic"
    override val genreRnb = "R&B and soul"
    override val genreMizrahi = "Mizrahi"
    override val genreOther = "Other"
    override val decadesLabel = "Decades"
    override fun decades(from: Int, to: Int) = when {
        from == FIRST_DECADE && to == LAST_DECADE -> "All decades"
        from == to -> "The ${decade(from)}"
        else -> "The ${decade(from)} to the ${decade(to)}"
    }
    private fun decade(d: Int) = if (d < 2000) "${d % 100}s" else "${d}s"
    override val exactYears = "Exact years"
    override val fromYear = "From year"
    override val toYear = "To year"
    override val poolIsraeli = "Israeli hits"
    override val poolInternational = "International hits"
    override val poolMixed = "Mixed"
    override val yearTolerance = "Year tolerance"
    override fun years(n: Int) = if (n == 1) "±1 year" else "±$n years"
    override val clipLength = "Clip length"
    override fun seconds(n: Int) = "$n seconds"
    override val playerNames = "Player names"
    override fun defaultPlayer(n: Int) = phrasesFor(Lang.EN).defaultPlayerName(n)
    override val autoStart = "Start talking as soon as the app opens"
    override val settingsUnavailable = "The settings cannot be loaded right now. Please try again later."

    override fun toString() = "EnglishUi"
}

fun uiStringsFor(lang: Lang): UiStrings = when (lang) {
    Lang.HE -> HebrewUi
    Lang.EN -> EnglishUi
}

/** The decades a game can cover, by their first years; the last one runs to [LAST_YEAR]. */
const val FIRST_DECADE = 1940
const val LAST_DECADE = 2020
const val LAST_YEAR = LAST_DECADE + 9

/** The name of [g] on the screens. */
fun UiStrings.genreName(g: Genre): String = when (g) {
    Genre.POP -> genrePop
    Genre.ROCK -> genreRock
    Genre.HIPHOP -> genreHipHop
    Genre.DANCE -> genreDance
    Genre.RNB -> genreRnb
    Genre.MIZRAHI -> genreMizrahi
    Genre.OTHER -> genreOther
}
