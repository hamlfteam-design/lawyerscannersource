package com.personal.docscanner.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.personal.docscanner.ui.camera.CameraScreen
import com.personal.docscanner.ui.crop.CropScreen
import com.personal.docscanner.ui.doc.DocumentScreen
import com.personal.docscanner.ui.fields.FieldsScreen
import com.personal.docscanner.ui.home.HomeScreen
import com.personal.docscanner.ui.index.IndexScreen
import com.personal.docscanner.ui.scan.ScanViewModel
import com.personal.docscanner.ui.settings.SettingsScreen

/**
 * Route names and argument keys in one place, so a typo is a compile error
 * rather than a screen that silently never opens.
 */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val CROP = "crop"
    const val DOCUMENT = "document"
    const val FIELDS = "fields"
    const val SETTINGS = "settings"
    const val INDEX = "index"

    const val ARG_FOLDER = "folderId"
    const val ARG_DOC = "docId"

    /** `null` folder means the library root; the literal "root" stands in for it. */
    const val ROOT = "root"

    fun home(folderId: String?) = "$HOME/${folderId ?: ROOT}"
    fun camera(folderId: String?, docId: String?) =
        "$CAMERA/${folderId ?: ROOT}/${docId ?: ROOT}"
    fun document(docId: String) = "$DOCUMENT/$docId"

    fun decodeFolder(raw: String?): String? = raw?.takeIf { it != ROOT }
}

@Composable
fun AppNav(navController: NavHostController = rememberNavController()) {
    // Activity-scoped so the capture survives the camera → crop navigation.
    val scanViewModel: ScanViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.home(null)) {

        composable(
            route = "${Routes.HOME}/{${Routes.ARG_FOLDER}}",
            arguments = listOf(navArgument(Routes.ARG_FOLDER) { type = NavType.StringType })
        ) { entry ->
            val folderId = Routes.decodeFolder(entry.arguments?.getString(Routes.ARG_FOLDER))
            HomeScreen(
                folderId = folderId,
                onOpenFolder = { navController.navigate(Routes.home(it)) },
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onScan = {
                    scanViewModel.start(folderId = folderId)
                    navController.navigate(Routes.camera(folderId, null))
                },
                onOpenFields = { navController.navigate(Routes.FIELDS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenIndex = { navController.navigate(Routes.INDEX) },
                onBack = { navController.popBackStack() },
                canGoBack = folderId != null
            )
        }

        composable(
            route = "${Routes.CAMERA}/{${Routes.ARG_FOLDER}}/{${Routes.ARG_DOC}}",
            arguments = listOf(
                navArgument(Routes.ARG_FOLDER) { type = NavType.StringType },
                navArgument(Routes.ARG_DOC) { type = NavType.StringType }
            )
        ) {
            CameraScreen(
                scanViewModel = scanViewModel,
                onCaptured = { navController.navigate(Routes.CROP) },
                onFinish = { docId ->
                    if (docId != null) {
                        navController.navigate(Routes.document(docId)) {
                            popUpTo(Routes.CAMERA) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onCancel = {
                    scanViewModel.reset()
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.CROP) {
            CropScreen(
                scanViewModel = scanViewModel,
                onSaved = { docId, addAnother ->
                    if (addAnother) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.document(docId)) {
                            popUpTo(Routes.HOME + "/{${Routes.ARG_FOLDER}}") { inclusive = false }
                        }
                    }
                },
                onEdited = {
                    scanViewModel.clearPending()
                    navController.popBackStack()
                },
                onCancel = {
                    scanViewModel.clearPending()
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "${Routes.DOCUMENT}/{${Routes.ARG_DOC}}",
            arguments = listOf(navArgument(Routes.ARG_DOC) { type = NavType.StringType })
        ) { entry ->
            val docId = entry.arguments?.getString(Routes.ARG_DOC).orEmpty()
            DocumentScreen(
                documentId = docId,
                onBack = { navController.popBackStack() },
                onAddPage = { folderId ->
                    scanViewModel.start(folderId = folderId, documentId = docId)
                    navController.navigate(Routes.camera(folderId, docId))
                },
                onEditPage = { pageId ->
                    scanViewModel.startPageEdit(pageId)
                    navController.navigate(Routes.CROP)
                },
                onDeleted = {
                    navController.popBackStack(
                        route = "${Routes.HOME}/{${Routes.ARG_FOLDER}}",
                        inclusive = false
                    )
                }
            )
        }

        composable(Routes.INDEX) {
            IndexScreen(
                onBack = { navController.popBackStack() },
                onOpenDocument = { navController.navigate(Routes.document(it)) }
            )
        }

        composable(Routes.FIELDS) {
            FieldsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenFields = { navController.navigate(Routes.FIELDS) }
            )
        }
    }
}
