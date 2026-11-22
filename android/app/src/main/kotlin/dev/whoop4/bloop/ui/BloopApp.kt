package dev.whoop4.bloop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.whoop4.bloop.ui.screens.DeviceScreen
import dev.whoop4.bloop.ui.screens.HomeScreen
import dev.whoop4.bloop.ui.screens.LiveScreen
import dev.whoop4.bloop.ui.screens.RecoveryScreen
import dev.whoop4.bloop.ui.screens.SessionScreen
import dev.whoop4.bloop.ui.screens.SleepScreen
import dev.whoop4.bloop.ui.screens.StrainScreen
import dev.whoop4.bloop.ui.theme.BloopColors

enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", BloopIcons.Home),
    Recovery("recovery", "Recovery", BloopIcons.Recovery),
    Sleep("sleep", "Sleep", BloopIcons.Sleep),
    Strain("strain", "Strain", BloopIcons.Strain),
    Live("live", "Live", BloopIcons.Live),
}

private const val DEVICE_ROUTE = "device"
private const val SESSION_ROUTE = "session/{id}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloopApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Dest.Home.route

    Scaffold(
        containerColor = BloopColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (route == DEVICE_ROUTE) "Device" else if (route == SESSION_ROUTE) "Session" else Dest.entries.firstOrNull { it.route == route }?.label ?: "Bloop",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                navigationIcon = {
                    if (route == SESSION_ROUTE) IconButton(onClick = { nav.popBackStack() }) { Icon(BloopIcons.Back, contentDescription = "Back") }
                },
                actions = {
                    // The sliders icon toggles the Device screen.
                    IconButton(onClick = { if (route == DEVICE_ROUTE) nav.popBackStack() else nav.navigate(DEVICE_ROUTE) { launchSingleTop = true } }) {
                        Icon(BloopIcons.Settings, contentDescription = "Device settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BloopColors.Background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BloopColors.Surface) {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = route == d.route || (route == SESSION_ROUTE && d == Dest.Live) ||
                            (route == DEVICE_ROUTE && nav.previousBackStackEntry?.destination?.route == d.route),
                        onClick = {
                            // Close the Device screen first: otherwise it is saved as part of the tab's state and
                            // restoreState brings it straight back (the screen "wouldn't close").
                            if (route == DEVICE_ROUTE) nav.popBackStack(DEVICE_ROUTE, inclusive = true)
                            if (nav.currentBackStackEntry?.destination?.route == SESSION_ROUTE) nav.popBackStack(SESSION_ROUTE, inclusive = true)
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BloopColors.OnSurface,
                            selectedTextColor = BloopColors.OnSurface,
                            indicatorColor = BloopColors.SurfaceHigh,
                            unselectedIconColor = BloopColors.Muted,
                            unselectedTextColor = BloopColors.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Dest.Home.route, modifier = Modifier.padding(padding)) {
            composable(Dest.Home.route) { HomeScreen() }
            composable(Dest.Recovery.route) { RecoveryScreen() }
            composable(Dest.Sleep.route) { SleepScreen() }
            composable(Dest.Strain.route) { StrainScreen() }
            composable(Dest.Live.route) { LiveScreen(onOpenSession = { id -> nav.navigate("session/$id") { launchSingleTop = true } }) }
            composable(SESSION_ROUTE) { e ->
                val id = e.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                SessionScreen(id, onClosed = { nav.popBackStack() })
            }
            composable(DEVICE_ROUTE) { DeviceScreen() }
        }
    }
}
