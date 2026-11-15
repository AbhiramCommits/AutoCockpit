package com.autocockpit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoCockpitTheme {
                val graph = remember { AppGraph.get(applicationContext) }
                AutoCockpitApp(graph)
            }
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
