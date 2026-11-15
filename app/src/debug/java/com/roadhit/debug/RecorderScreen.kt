package com.roadhit.debug

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadhit.speech.MicStream
import com.roadhit.speech.PhoneMicSource
import com.roadhit.speech.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "RoadHit"

/** Records the voice test set: one 16 kHz mono WAV per line, `testset/NN.wav`. */
@Composable
fun RecorderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { TestSet.loadLines(context) }
    var index by remember { mutableIntStateOf(0) }
    var recorded by remember {
        mutableStateOf(lines.filter { TestSet.recording(context, it.n).exists() }.map { it.n }.toSet())
    }
    var mic by remember { mutableStateOf<MicStream?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val line = lines[index]

    fun startRecording() {
        val n = line.n
        val stream = try {
            PhoneMicSource(context).open()
        } catch (e: Exception) {
            Log.e(TAG, "lab record $n failed to open mic", e)
            return
        }
        mic = stream
        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(3200)
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                }
                out.toByteArray().also { Wav.write(TestSet.recording(context, n), it) }
            }
            Log.i(TAG, "lab record %02d saved %d ms".format(n, pcm.size / 32))
            recorded = recorded + n
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    fun stopPlayback() {
        player?.release()
        player = null
    }

    DisposableEffect(Unit) {
        onDispose {
            mic?.close()
            stopPlayback()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${line.n} / ${lines.size}", style = MaterialTheme.typography.titleLarge)
        val hebrew = line.say.firstOrNull { it.isLetter() }?.let { it in '֐'..'׿' } == true
        Text(
            line.say,
            modifier = Modifier.fillMaxWidth().weight(1f),
            fontSize = 34.sp,
            lineHeight = 44.sp,
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.bodyLarge.copy(
                textDirection = if (hebrew) TextDirection.Rtl else TextDirection.Ltr,
            ),
        )
        Text(
            if (line.n in recorded) "Recorded" else "Not recorded yet",
            color = if (line.n in recorded) Color(0xFF2E7D32) else Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mic == null) {
                Button(onClick = {
                    stopPlayback()
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecording()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text("Record") }
            } else {
                Button(
                    onClick = { mic?.close(); mic = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("Stop") }
            }
            OutlinedButton(
                enabled = mic == null && line.n in recorded,
                onClick = {
                    stopPlayback()
                    player = MediaPlayer().apply {
                        setDataSource(TestSet.recording(context, line.n).path)
                        setOnCompletionListener { stopPlayback() }
                        prepare()
                        start()
                    }
                },
            ) { Text("Play back") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = mic == null && index > 0, onClick = { stopPlayback(); index-- }) { Text("Previous") }
            OutlinedButton(enabled = mic == null && index < lines.lastIndex, onClick = { stopPlayback(); index++ }) { Text("Next") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { i, l ->
                Box(
                    Modifier
                        .size(28.dp)
                        .background(
                            when {
                                i == index -> MaterialTheme.colorScheme.primary
                                l.n in recorded -> Color(0xFF66BB6A)
                                else -> Color(0xFFE0E0E0)
                            },
                            CircleShape,
                        )
                        .clickable(enabled = mic == null) { stopPlayback(); index = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${l.n}", fontSize = 11.sp, color = if (i == index) Color.White else Color.Black)
                }
            }
        }
    }
}
