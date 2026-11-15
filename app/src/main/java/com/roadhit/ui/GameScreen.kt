package com.roadhit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The game screen. Stateless: everything comes from [model] (which carries a song only in the
 * reveal) and the dialog flags hoisted to the activity.
 */
@Composable
fun GameScreen(
    model: GameScreenModel,
    strings: UiStrings,
    micDenied: Boolean,
    confirmingStop: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfirmStop: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismissMicDenied: () -> Unit,
) {
    when (model) {
        is GameScreenModel.Home -> HomeScreen(model, strings, micDenied, onStart, onSettings, onOpenAppSettings, onDismissMicDenied)
        is GameScreenModel.InGame -> InGameScreen(model, strings, onStop = { onConfirmStop(true) }, onSettings = onSettings)
    }
    if (confirmingStop && model is GameScreenModel.InGame) {
        AlertDialog(
            onDismissRequest = { onConfirmStop(false) },
            title = { Text(strings.stopConfirmTitle, fontSize = 26.sp) },
            text = { Text(strings.stopConfirmText, fontSize = 20.sp) },
            confirmButton = {
                Button(
                    onClick = { onConfirmStop(false); onStop() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.dialogTag("stop-confirm"),
                ) { Text(strings.stopConfirmYes, fontSize = 20.sp) }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmStop(false) }, modifier = Modifier.dialogTag("stop-cancel")) {
                    Text(strings.stopConfirmNo, fontSize = 20.sp)
                }
            },
        )
    }
}

@Composable
private fun HomeScreen(
    model: GameScreenModel.Home,
    strings: UiStrings,
    micDenied: Boolean,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismissMicDenied: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            strings.appName,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("title"),
        )
        if (model.finished && model.finalScores.isNotEmpty()) {
            Scoreboard(strings.finalScores, model.finalScores, strings)
        }
        if (micDenied) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().testTag("mic-denied"),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.micNeeded, fontSize = 20.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpenAppSettings, modifier = Modifier.testTag("open-app-settings")) {
                            Text(strings.openAppSettings, fontSize = 18.sp)
                        }
                        TextButton(onClick = onDismissMicDenied) { Text(strings.dismiss, fontSize = 18.sp) }
                    }
                }
            }
        }
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(120.dp).testTag("start"),
        ) { Text(strings.start, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth().height(72.dp).testTag("settings"),
        ) { Text(strings.settings, fontSize = 24.sp) }
    }
}

@Composable
private fun InGameScreen(model: GameScreenModel.InGame, strings: UiStrings, onStop: () -> Unit, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth > maxHeight
        val main: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.status,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).testTag("status"),
                    )
                    model.round?.let { Text(it, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("round")) }
                }
                model.turn?.let {
                    Text(it, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("turn"))
                }
                model.revealed?.let { Revealed(it, strings) }
            }
        }
        // Settings stay reachable (a change reaches the running game); Stop asks first.
        val controls: @Composable (Modifier) -> Unit = { m ->
            Row(m, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).height(64.dp).testTag("stop"),
                ) { Text(strings.stop, fontSize = 24.sp) }
                TextButton(onClick = onSettings, modifier = Modifier.height(64.dp).testTag("settings")) {
                    Text(strings.settings, fontSize = 20.sp)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    main(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    controls(Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (model.scoreboard.isNotEmpty()) Scoreboard(strings.scoreboard, model.scoreboard, strings)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                main(Modifier.fillMaxWidth())
                if (model.scoreboard.isNotEmpty()) Scoreboard(strings.scoreboard, model.scoreboard, strings)
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Revealed(song: RevealedSong, strings: UiStrings) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("revealed"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(song.title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("revealed-title"))
            Text(song.artist, fontSize = 26.sp, modifier = Modifier.testTag("revealed-artist"))
            Text(strings.year(song.year), fontSize = 24.sp, modifier = Modifier.testTag("revealed-year"))
        }
    }
}

@Composable
private fun Scoreboard(heading: String, rows: List<ScoreRow>, strings: UiStrings) {
    Column(Modifier.fillMaxWidth().testTag("scoreboard"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(heading, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { i, row ->
            val highlighted = row.current || row.winner
            val marker = when {
                row.winner -> strings.winner
                row.current -> strings.currentPlayer
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("score-${i + 1}")
                    .semantics { if (marker != null) contentDescription = "${row.name} ${row.score} $marker" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                val weight = if (highlighted) FontWeight.Bold else FontWeight.Normal
                Text(row.name, fontSize = 28.sp, fontWeight = weight, color = color, modifier = Modifier.weight(1f))
                if (marker != null) {
                    Text(marker, fontSize = 18.sp, color = color, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Text(row.score.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

/** A dialog is its own window, outside the activity's `testTagsAsResourceId`, so its tags opt in here. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.dialogTag(tag: String) = semantics { testTagsAsResourceId = true }.testTag(tag)
