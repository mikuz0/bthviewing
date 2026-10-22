package com.example.bthviewing

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* результат не нужен: сканер сам проверит hasPermissions() */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Применяем сохранённую настройку поворота до setContent,
        // чтобы не было "мигания" при старте.
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("lock_portrait", false)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setContent {
            AppRoot(onRequestPermissions = { requestPermissions() })
        }
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem("devices", "Устройства", Icons.Default.Devices),
    BottomNavItem("journal", "Журнал", Icons.Default.List),
    BottomNavItem("settings", "Настройки", Icons.Default.Settings)
)

@Composable
fun AppRoot(
    onRequestPermissions: () -> Unit,
    viewModel: MainViewModel = viewModel(),
    journalViewModel: JournalViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val darkTheme by appViewModel.darkTheme.collectAsState()
    val lockPortrait by appViewModel.lockPortrait.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(lockPortrait) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        activity.requestedOrientation = if (lockPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "devices",
                modifier = Modifier.padding(padding)
            ) {
                composable("devices") {
                    DevicesScreen(
                        viewModel = viewModel,
                        onRequestPermissions = onRequestPermissions
                    )
                }
                composable("journal") {
                    LaunchedEffect(Unit) { viewModel.stopRefresh() }
                    val state by viewModel.uiState.collectAsState()
                    JournalScreen(
                        currentDeviceAddress = state.selectedAddress,
                        currentDeviceName = state.selectedName
                            ?: state.lastReading?.deviceName
                            ?: state.devices[state.selectedAddress]?.name,
                        viewModel = journalViewModel
                    )
                }
                composable("settings") {
                    LaunchedEffect(Unit) { viewModel.stopRefresh() }
                    val state by viewModel.uiState.collectAsState()
                    SettingsScreen(
                        deviceAddress = state.selectedAddress,
                        deviceServices = state.deviceServices,
                        mainViewModel = viewModel          // ← новый аргумент
                    )
                }
            }
        }
    }
}