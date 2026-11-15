package com.roadhit.session

import android.content.Context
import android.util.Log
import com.roadhit.game.GameSettings
import com.roadhit.game.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Background writes of the shared stores; lives as long as the process. */
private val appStoresScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val appStoresLock = Mutex()

@Volatile
private var appStoresInstance: AppStores? = null

/**
 * The process-wide [AppStores], opened once over [appDataStore]. The game service and the screens
 * share it, so a preference changed on one side is seen by the other at once.
 */
internal suspend fun Context.appStores(): AppStores {
    appStoresInstance?.let { return it }
    val app = applicationContext
    return appStoresLock.withLock {
        appStoresInstance ?: withContext(Dispatchers.IO) {
            AppStores.open(app.appDataStore, appStoresScope, defaultLang(app)) {
                Log.w("RoadHit", "store write failed", it)
            }
        }.also { appStoresInstance = it }
    }
}

/**
 * Saves a preference change in the process's scope, so leaving the screen does not cut it short.
 * Call on the main thread: changes start in call order (and [AppStores.updatePrefs] keeps that order).
 */
internal fun AppStores.updatePrefsLater(f: (Prefs) -> Prefs) {
    appStoresScope.launch(Dispatchers.Main.immediate) { updatePrefs(f) }
}

/** The same for a change to the next game's choices ([AppStores.updateSettings]). */
internal fun AppStores.updateSettingsLater(f: (GameSettings) -> GameSettings) {
    appStoresScope.launch(Dispatchers.Main.immediate) { updateSettings(f) }
}
