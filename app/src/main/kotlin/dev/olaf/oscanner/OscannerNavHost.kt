package dev.olaf.oscanner

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.olaf.oscanner.core.data.DataContainer
import dev.olaf.oscanner.feature.capture.CaptureRoute
import dev.olaf.oscanner.feature.capture.CaptureViewModel
import dev.olaf.oscanner.feature.document.ArrangeRoute
import dev.olaf.oscanner.feature.document.ArrangeViewModel
import dev.olaf.oscanner.feature.document.CleanupRoute
import dev.olaf.oscanner.feature.document.CleanupViewModel
import dev.olaf.oscanner.feature.document.DocumentRoute
import dev.olaf.oscanner.feature.document.DocumentViewModel
import dev.olaf.oscanner.feature.document.MarkupRoute
import dev.olaf.oscanner.feature.document.MarkupViewModel
import dev.olaf.oscanner.feature.library.LibraryRoute
import dev.olaf.oscanner.feature.library.LibraryViewModel
import dev.olaf.oscanner.feature.library.TrashRoute
import dev.olaf.oscanner.feature.library.TrashViewModel
import dev.olaf.oscanner.feature.review.ReviewRoute
import dev.olaf.oscanner.feature.review.ReviewViewModel
import dev.olaf.oscanner.feature.settings.LanguagesRoute
import dev.olaf.oscanner.feature.settings.LanguagesViewModel
import dev.olaf.oscanner.feature.settings.SettingsRoute
import dev.olaf.oscanner.feature.settings.SettingsViewModel
import dev.olaf.oscanner.feature.text.TextRoute
import dev.olaf.oscanner.feature.text.TextViewModel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val LANGUAGES = "languages"
    const val CAPTURE = "capture?documentId={documentId}"
    const val REVIEW = "review/{documentId}?page={page}"
    const val DOCUMENT = "document/{documentId}"
    const val TEXT = "text/{documentId}?page={page}"
    const val ARRANGE = "arrange/{documentId}"
    const val MARKUP = "markup/{documentId}?page={page}"
    const val CLEANUP = "cleanup/{documentId}?page={page}"

    fun capture(documentId: Long? = null) = "capture?documentId=${documentId ?: -1}"
    fun review(documentId: Long, page: Int = 0) = "review/$documentId?page=$page"
    fun document(documentId: Long) = "document/$documentId"
    fun text(documentId: Long, page: Int = 0) = "text/$documentId?page=$page"
    fun arrange(documentId: Long) = "arrange/$documentId"
    fun markup(documentId: Long, page: Int) = "markup/$documentId?page=$page"
    fun cleanup(documentId: Long, page: Int) = "cleanup/$documentId?page=$page"
}

@Composable
fun OscannerNavHost(
    container: DataContainer,
    /** Images another app shared to us; consumed once they're imported. */
    sharedImages: List<Uri>?,
    onSharedImagesHandled: () -> Unit,
    scanRequested: Boolean,
    onScanHandled: () -> Unit,
    appLockAvailable: Boolean,
    confirmLock: (onResult: (Boolean) -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importFailed = stringResource(R.string.app_import_failed)
    val movedToTrash = stringResource(R.string.app_moved_to_trash)
    val undo = stringResource(R.string.app_undo)
    val importTitle = stringResource(
        R.string.app_import_title,
        LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
    )

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            runCatching { container.pages.importImages(uris, importTitle) }
                .onSuccess { navController.navigate(Routes.review(it)) }
                .onFailure { snackbarHostState.showSnackbar(importFailed) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { import(it) }

    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            onScanHandled()
            navController.navigate(Routes.capture()) { launchSingleTop = true }
        }
    }

    LaunchedEffect(sharedImages) {
        sharedImages?.let {
            onSharedImagesHandled()
            import(it)
        }
    }

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { LibraryViewModel(container.documents, container.folders, container.tags, container.settings, container.pages) }
                },
            )
            LibraryRoute(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onScan = { navController.navigate(Routes.capture()) },
                onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenTrash = { navController.navigate(Routes.TRASH) { launchSingleTop = true } },
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
                onOpenLanguages = { navController.navigate(Routes.LANGUAGES) { launchSingleTop = true } },
                appLockAvailable = appLockAvailable,
                confirmLock = confirmLock,
            )
        }
        composable(Routes.LANGUAGES) {
            val viewModel: LanguagesViewModel = viewModel(
                factory = viewModelFactory { initializer { LanguagesViewModel(container.languages, container.settings) } },
            )
            LanguagesRoute(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.TRASH) {
            val viewModel: TrashViewModel = viewModel(
                factory = viewModelFactory { initializer { TrashViewModel(container.documents, container.pages) } },
            )
            TrashRoute(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CAPTURE,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val documentId = entry.arguments?.getLong("documentId")?.takeIf { it >= 0 }
            val viewModel: CaptureViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { CaptureViewModel(container.pages, context.cacheDir, documentId) }
                },
            )
            CaptureRoute(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onSaved = { saved ->
                    navController.navigate(Routes.review(saved.documentId, saved.firstNewPage)) {
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.REVIEW,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val page = entry.arguments!!.getInt("page")
            val viewModel: ReviewViewModel = viewModel(
                factory = viewModelFactory { initializer { ReviewViewModel(container.pages, documentId) } },
            )
            ReviewRoute(
                viewModel = viewModel,
                startPage = page,
                onBack = { navController.popBackStack() },
                onSaved = { navController.showDocument(it) },
            )
        }
        composable(
            Routes.DOCUMENT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val viewModel: DocumentViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { DocumentViewModel(container.documents, container.folders, container.tags, container.pages, documentId) }
                },
            )
            DocumentRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = { id, page -> navController.navigate(Routes.review(id, page)) },
                onText = { id, page -> navController.navigate(Routes.text(id, page)) },
                onScanMore = { navController.navigate(Routes.capture(it)) },
                onArrange = { navController.navigate(Routes.arrange(it)) },
                onMarkup = { id, page -> navController.navigate(Routes.markup(id, page)) },
                onCleanup = { id, page -> navController.navigate(Routes.cleanup(id, page)) },
                onTrashed = { id ->
                    navController.popBackStack(Routes.LIBRARY, inclusive = false)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(movedToTrash, actionLabel = undo, duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) container.documents.restore(id)
                    }
                },
            )
        }
        composable(
            Routes.CLEANUP,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val page = entry.arguments!!.getInt("page")
            val viewModel: CleanupViewModel = viewModel(
                factory = viewModelFactory { initializer { CleanupViewModel(container.pages, documentId, page) } },
            )
            CleanupRoute(viewModel = viewModel, onDone = { navController.popBackStack() })
        }
        composable(
            Routes.MARKUP,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val page = entry.arguments!!.getInt("page")
            val viewModel: MarkupViewModel = viewModel(
                factory = viewModelFactory { initializer { MarkupViewModel(container.pages, container.signatures, documentId, page) } },
            )
            MarkupRoute(viewModel = viewModel, onDone = { navController.popBackStack() })
        }
        composable(
            Routes.ARRANGE,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val viewModel: ArrangeViewModel = viewModel(
                factory = viewModelFactory { initializer { ArrangeViewModel(container.documents, container.pages, documentId) } },
            )
            ArrangeRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenDocument = { navController.navigate(Routes.document(it)) },
            )
        }
        composable(
            Routes.TEXT,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            val viewModel: TextViewModel = viewModel(
                factory = viewModelFactory { initializer { TextViewModel(container.documents, container.pages, documentId) } },
            )
            TextRoute(viewModel = viewModel, startPage = entry.arguments!!.getInt("page"), onBack = { navController.popBackStack() })
        }
    }
}

/** After Review: back to the document if we came from it, otherwise open it in Review's place. */
private fun NavHostController.showDocument(documentId: Long) {
    val previous = previousBackStackEntry
    val cameFromDocument = previous?.destination?.route == Routes.DOCUMENT &&
        previous.arguments?.getLong("documentId") == documentId
    if (cameFromDocument) {
        popBackStack()
    } else {
        navigate(Routes.document(documentId)) {
            popUpTo(Routes.REVIEW) { inclusive = true }
        }
    }
}
