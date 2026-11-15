package com.roadhit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadhit.game.Difficulty
import com.roadhit.game.GameSettings
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Pool
import com.roadhit.game.Prefs
import kotlin.math.roundToInt

/**
 * The settings screen. Each change goes to [onChange] or [onGameChange] at once (saved by the
 * caller); the screen keeps only in-progress edits (slider drags, the text being typed). [game]
 * holds the next game's choices.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    game: GameSettings,
    strings: UiStrings,
    onChange: ((Prefs) -> Prefs) -> Unit,
    onGameChange: ((GameSettings) -> GameSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(strings.back, fontSize = 20.sp) }
            Text(strings.settingsTitle, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }

        Heading(strings.language)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RadioOption(strings.hebrew, prefs.lang == Lang.HE, "lang-he") { onChange { it.copy(lang = Lang.HE) } }
            RadioOption(strings.english, prefs.lang == Lang.EN, "lang-en") { onChange { it.copy(lang = Lang.EN) } }
        }
        HorizontalDivider()

        Heading(strings.nextGame)
        Column(Modifier.selectableGroup()) {
            val options = listOf(
                Triple(Pool.ISRAELI, strings.poolIsraeli, "pool-israeli"),
                Triple(Pool.INTERNATIONAL, strings.poolInternational, "pool-international"),
                Triple(Pool.MIXED, strings.poolMixed, "pool-mixed"),
            )
            for ((option, label, tag) in options) {
                RadioOption(label, game.pool == option, tag) {
                    if (game.pool != option) onGameChange { it.copy(pool = option) }
                }
            }
        }
        Text(strings.genre, fontSize = 20.sp)
        GenreChips(game.genre, strings) { g -> onGameChange { it.copy(genre = g) } }

        val from = game.fromYear
        val to = game.toYear
        if (from != null && to != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                YearField(strings.fromYear, from, "from-year", Modifier.weight(1f)) { y ->
                    onGameChange { it.copy(fromYear = y, toYear = maxOf(it.toYear ?: y, y)) }
                }
                YearField(strings.toYear, to, "to-year", Modifier.weight(1f)) { y ->
                    onGameChange { it.copy(toYear = y, fromYear = minOf(it.fromYear ?: y, y)) }
                }
            }
        } else {
            DecadeSlider(game.fromDecade, game.toDecade, strings) { f, t ->
                onGameChange { it.copy(fromDecade = f, toDecade = t) }
            }
        }
        SwitchRow(strings.exactYears, checked = from != null && to != null, tag = "exact-years") { on ->
            onGameChange {
                if (on) it.copy(fromYear = it.fromDecade, toYear = it.toDecade + 9) else it.copy(fromYear = null, toYear = null)
            }
        }

        IntSlider(
            label = strings.playerCount,
            value = game.players,
            range = 1..MAX_PLAYERS,
            text = strings::players,
            tag = "players",
        ) { v -> onGameChange { it.copy(players = v) } }
        IntSlider(
            label = strings.roundCount,
            value = game.rounds,
            range = 1..MAX_ROUNDS,
            text = strings::rounds,
            tag = "rounds",
        ) { v -> onGameChange { it.copy(rounds = v) } }
        Text(strings.nextGameHint, fontSize = 16.sp)

        // Only here: the voice never asks for or mentions the difficulty.
        Text(strings.difficulty, fontSize = 20.sp)
        Column(Modifier.selectableGroup()) {
            val levels = listOf(
                Triple(Difficulty.EASY, strings.difficultyEasy, "difficulty-easy"),
                Triple(Difficulty.MEDIUM, strings.difficultyMedium, "difficulty-medium"),
                Triple(Difficulty.HARD, strings.difficultyHard, "difficulty-hard"),
            )
            for ((level, label, tag) in levels) {
                RadioOption(label, game.difficulty == level, tag) {
                    if (game.difficulty != level) onGameChange { it.copy(difficulty = level) }
                }
            }
        }
        HorizontalDivider()

        IntSlider(
            label = strings.yearTolerance,
            value = prefs.yearTolerance,
            range = 0..10,
            text = strings::years,
            tag = "tolerance",
        ) { v -> onChange { it.copy(yearTolerance = v) } }
        HorizontalDivider()

        IntSlider(
            label = strings.clipLength,
            value = prefs.clipSeconds,
            range = 10..30,
            text = strings::seconds,
            tag = "clip",
        ) { v -> onChange { it.copy(clipSeconds = v) } }
        HorizontalDivider()

        Heading(strings.playerNames)
        for (i in 0 until MAX_PLAYERS) {
            NameField(i, prefs.playerNames.getOrElse(i) { "" }, strings) { name ->
                onChange { it.copy(playerNames = withPlayerName(it.playerNames, i, name)) }
            }
        }
        HorizontalDivider()

        SwitchRow(strings.autoStart, checked = prefs.autoStartOnOpen, tag = "autostart") { on ->
            onChange { it.copy(autoStartOnOpen = on) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One genre or all of them (null). */
@Composable
private fun GenreChips(selected: Genre?, strings: UiStrings, onSelect: (Genre?) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in listOf<Genre?>(null) + Genre.entries) {
            FilterChip(
                selected = selected == g,
                onClick = { if (selected != g) onSelect(g) },
                label = { Text(g?.let(strings::genreName) ?: strings.genreAll, fontSize = 18.sp) },
                modifier = Modifier.testTag("genre-${g?.name?.lowercase() ?: "all"}"),
            )
        }
    }
}

/** A range of whole decades; [onRange] gets the first years of the first and last decade as they change. */
@Composable
private fun DecadeSlider(from: Int, to: Int, strings: UiStrings, onRange: (Int, Int) -> Unit) {
    var dragged by remember(from, to) { mutableStateOf(from.toFloat()..to.toFloat()) }
    fun decadeOf(v: Float) = ((v / 10).roundToInt() * 10).coerceIn(FIRST_DECADE, LAST_DECADE)
    val shown = strings.decades(decadeOf(dragged.start), decadeOf(dragged.endInclusive))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.decadesLabel, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text(shown, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("decades-value"))
        }
        RangeSlider(
            value = dragged,
            onValueChange = { v ->
                dragged = v
                val f = decadeOf(v.start)
                val t = decadeOf(v.endInclusive)
                if (f != from || t != to) onRange(f, t)
            },
            valueRange = FIRST_DECADE.toFloat()..LAST_DECADE.toFloat(),
            steps = (LAST_DECADE - FIRST_DECADE) / 10 - 1,
            modifier = Modifier.fillMaxWidth().testTag("decades").semantics { contentDescription = "${strings.decadesLabel} $shown" },
        )
    }
}

/** A year typed as digits; [onYear] gets each complete year from [FIRST_DECADE] to [LAST_YEAR]. */
@Composable
private fun YearField(label: String, stored: Int, tag: String, modifier: Modifier, onYear: (Int) -> Unit) {
    // Typed text is kept here until it is a whole valid year; a change from outside replaces it.
    var text by remember(stored) { mutableStateOf(stored.toString()) }
    fun yearIn(t: String) = t.toIntOrNull()?.takeIf { it in FIRST_DECADE..LAST_YEAR }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val digits = v.filter(Char::isDigit).take(4)
            text = digits
            yearIn(digits)?.takeIf { it != stored }?.let(onYear)
        },
        label = { Text(label, fontSize = 16.sp) },
        isError = yearIn(text) == null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier.testTag(tag),
    )
}

/** In place of the settings while the stored preferences are not open: Back, and once [failed], why. */
@Composable
fun SettingsUnavailable(strings: UiStrings, failed: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(strings.back, fontSize = 20.sp) }
            Text(strings.settingsTitle, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        if (failed) Text(strings.settingsUnavailable, fontSize = 22.sp, modifier = Modifier.testTag("settings-unavailable"))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RadioOption(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A whole-number slider; [onValue] gets each new whole value as it is dragged to. */
@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, text: (Int) -> String, tag: String, onValue: (Int) -> Unit) {
    var dragged by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = dragged.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text(text(shown), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("$tag-value"))
        }
        Slider(
            value = dragged,
            onValueChange = { v ->
                dragged = v
                val whole = v.roundToInt()
                if (whole != value) onValue(whole)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label ${text(shown)}" },
        )
    }
}

@Composable
private fun NameField(index: Int, stored: String, strings: UiStrings, onName: (String) -> Unit) {
    // Typed text is kept here (the store trims names), so spaces and the cursor are not disturbed.
    var text by rememberSaveable { mutableStateOf(stored) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", fontSize = 20.sp, modifier = Modifier.width(32.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                val clipped = v.take(MAX_NAME_LENGTH)
                text = clipped
                onName(clipped)
            },
            placeholder = { Text(strings.defaultPlayer(index + 1), fontSize = 20.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
            keyboardOptions = KeyboardOptions(imeAction = if (index < MAX_PLAYERS - 1) ImeAction.Next else ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("name-${index + 1}"),
        )
    }
}

/** As [com.roadhit.session.AppStores] keeps them. */
private const val MAX_NAME_LENGTH = 30

/** As the voice setup accepts. */
private const val MAX_ROUNDS = 20
