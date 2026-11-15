package com.roadhit.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.roadhit.game.Lang
import com.roadhit.session.GameService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The car's now-playing screen. The host draws the card (whose turn, the scores; the song only
 * after the verdict) and the previous / pause / next controls from the game's media session; this
 * screen adds the header: the round, or the app name ([carHeaderTitle]), in the app language.
 */
internal class GameCarScreen(carContext: CarContext, private val lang: StateFlow<Lang>) : Screen(carContext) {

    // Main thread.
    private var title = carHeaderTitle(GameService.view.value, lang.value)

    init {
        // lifecycleScope runs on the main thread and ends when the screen is destroyed.
        lifecycleScope.launch {
            combine(GameService.view, lang, ::carHeaderTitle).distinctUntilChanged().collect { t ->
                if (t != title) {
                    title = t
                    Log.i(TAG, "car header $t")
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template = MediaPlaybackTemplate.Builder()
        .setHeader(Header.Builder().setTitle(title).build())
        .build()

    private companion object {
        const val TAG = "RoadHit"
    }
}
