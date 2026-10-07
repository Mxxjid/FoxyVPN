package com.vauth.foxyvpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.MainActivity
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "VpnTileService"

/**
 * Quick Settings tile that toggles the VPN straight from Android's control panel.
 *
 * The tile is registered as a passive (non-active) tile, so the system binds it whenever it is
 * visible: [onStartListening] follows the service's real state while the panel is open, and every
 * fresh bind re-reads it — even after the app's process was killed while the panel was closed.
 */
class VpnTileService : TileService() {

    private val scope: CoroutineScope = MainScope()
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            FoxyVpnService.state.collect { state -> updateTile(state) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        if (FoxyVpnService.state.value != ConnectionState.DISCONNECTED) {
            AppLogger.i(TAG, "tile: disconnect requested")
            // Optimistic update; the state flow confirms it right after.
            updateTile(ConnectionState.DISCONNECTED)
            FoxyVpnService.stop(this)
            return
        }

        AppLogger.i(TAG, "tile: connect requested")
        connectFromTile()
    }

    private fun connectFromTile() {
        val app = application as FoxyVpnApp

        if (app.settingsStore.proxyOnlyMode) {
            startVpnService()
            return
        }

        if (VpnService.prepare(this) == null) {
            startVpnService()
            return
        }

        // The system's VPN consent dialog needs an Activity, so hand off to MainActivity,
        // which re-runs its normal connect flow (and connects automatically once granted).
        AppLogger.i(TAG, "tile: VPN consent is required; opening the app")
        openAppToConnect()
    }

    private fun startVpnService() {
        updateTile(ConnectionState.CONNECTING)
        try {
            FoxyVpnService.start(this)
        } catch (error: Exception) {
            // Android 12+ may refuse foreground-service starts made from the background;
            // fall back to opening the app, where the start is always allowed.
            AppLogger.w(TAG, "tile: could not start the VPN service from the background", error)
            updateTile(ConnectionState.DISCONNECTED)
            openAppToConnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun openAppToConnect() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
        }
        val launch = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    startActivityAndCollapse(intent)
                }
            } catch (error: Exception) {
                AppLogger.w(TAG, "tile: could not open the app", error)
            }
        }
        if (isLocked) {
            unlockAndRun(launch)
        } else {
            launch.run()
        }
    }

    private fun updateTile(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            ConnectionState.CONNECTED -> Tile.STATE_ACTIVE
            ConnectionState.CONNECTING -> Tile.STATE_INACTIVE
            ConnectionState.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(
                when (state) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting\u2026"
                    ConnectionState.DISCONNECTED -> null
                },
            )
        }
        runCatching { tile.updateTile() }
            .onFailure { AppLogger.w(TAG, "could not update the quick settings tile", it) }
    }
}