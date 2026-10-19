package dev.olaf.oscanner

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.olaf.oscanner.core.data.DataContainer
import dev.olaf.oscanner.feature.library.LibraryRoute
import dev.olaf.oscanner.feature.library.LibraryViewModel
import dev.olaf.oscanner.feature.settings.SettingsRoute
import dev.olaf.oscanner.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch

private object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
}

@Composable
fun OscannerNavHost(container: DataContainer) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Capture, Import and the document view arrive in Phase 1; until then say so plainly.
    val notYet = stringResource(R.string.app_not_built_yet)
    val showNotYet: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(notYet) } }

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { LibraryViewModel(container.documents, container.folders, container.settings) }
                },
            )
            LibraryRoute(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onScan = showNotYet,
                onImport = showNotYet,
                onOpenDocument = { showNotYet() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(container.settings, container.sampleData.takeIf { BuildConfig.DEBUG })
                    }
                },
            )
            SettingsRoute(
                viewModel = viewModel,
                versionName = BuildConfig.VERSION_NAME,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
