package com.autocockpit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autocockpit.app.ui.UiTags
import com.autocockpit.app.ui.screens.GaugeScreen
import com.autocockpit.app.ui.screens.MediaScreen
import com.autocockpit.app.ui.screens.SettingsScreen
import com.autocockpit.app.ui.theme.AutoCockpitTheme
import com.autocockpit.app.ui.vm.GaugeViewModel

class MainActivity : ComponentActivity() {

    companion object {
        /** Instrumented tests opt out of the runtime BLE permission dialog. */
        const val EXTRA_SKIP_COMPANION_LINK: String = "com.autocockpit.app.SKIP_COMPANION_LINK"
    }

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Even a denied request leaves the rest of the cockpit usable.
        AppGraph.get(applicationContext).startCompanionLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra(EXTRA_SKIP_COMPANION_LINK, false)) {
            startCompanionLinkIfPermitted()
        }
        setContent {
            AutoCockpitTheme {
                val graph = remember { AppGraph.get(applicationContext) }
                AutoCockpitApp(graph)
            }
        }
    }

    private fun startCompanionLinkIfPermitted() {
        val graph = AppGraph.get(applicationContext)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            graph.startCompanionLink()
            return
        }
        val granted = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            graph.startCompanionLink()
        } else {
            bluetoothPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        }
    }
}

enum class Destination(val label: String, val icon: ImageVector, val testTag: String) {
    MEDIA("Media", Icons.Filled.LibraryMusic, UiTags.NAV_MEDIA),
    GAUGE("Gauge", Icons.Filled.Speed, UiTags.NAV_GAUGE),
    SETTINGS("Settings", Icons.Filled.Settings, UiTags.NAV_SETTINGS),
}

@Composable
fun AutoCockpitApp(graph: AppGraph) {
    var destination by rememberSaveable { mutableStateOf(Destination.MEDIA) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                        modifier = Modifier.testTag(entry.testTag),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.MEDIA -> MediaScreen()
                Destination.GAUGE -> GaugeScreen(viewModel = viewModel(factory = GaugeViewModel.factory(graph)))
                Destination.SETTINGS -> SettingsScreen()
            }
        }
    }
}
