package dev.olaf.oscanner.feature.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.LocalOscannerExtras
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.model.CameraOrientation
import dev.olaf.oscanner.core.design.pressScale
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.design.rememberReducedMotion
import dev.olaf.oscanner.core.model.Quad
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

/** Camera chrome sits on photos, so it uses fixed light-on-dark colors in both themes. */
private val Scrim = Color.Black.copy(alpha = 0.55f)
private val ChromeInk = Color(0xFFF4EEE6)

@Composable
fun CaptureRoute(
    viewModel: CaptureViewModel,
    onClose: () -> Unit,
    onSaved: (SavedScan) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(
        R.string.capture_default_title,
        LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
    )
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) { viewModel.savedScans.collect { currentOnSaved(it) } }

    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA) }
    // Back from system settings with the permission turned on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasCameraPermission() }


    // The screen stays portrait; only the controls at the top turn to face the person.
    LockPortrait()
    val sensed by rememberDeviceTurn()
    // Locked, the controls and photos stay one way however the phone is held. Landscape takes
    // whichever side the phone is turned to, or the left when it's upright.
    val turn = when (state.orientation) {
        CameraOrientation.Auto -> sensed
        CameraOrientation.Portrait -> 0
        CameraOrientation.Landscape -> if (sensed == 90) 90 else 270
    }
    val upright = rememberUprightRotation(turn)
    LaunchedEffect(turn, sensed) { viewModel.setDeviceTurn(turn, sensed) }

    var showFixes by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.captures.isNotEmpty()) confirmLeave = true else onClose() }
    BackHandler(enabled = state.captures.isNotEmpty() && !state.saving) { confirmLeave = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraLayer(viewModel = viewModel, state = state, title = title)
        } else {
            PermissionPrompt(
                showSettings = asked && context.findActivity()?.let {
                    !it.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                } == true,
                onAllow = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        TopChrome(
            rotation = upright,
            sideways = turn % 180 != 0,
            flash = state.flash,
            autoCapture = state.autoCapture,
            showCameraControls = granted,
            showAuto = granted && state.mode != CaptureMode.Code,
            onClose = leave,
            onToggleFlash = viewModel::toggleFlash,
            onToggleAuto = { viewModel.setAutoCapture(!state.autoCapture) },
            orientation = state.orientation,
            onCycleOrientation = viewModel::cycleOrientation,
            fixesOn = state.fixes.count,
            showFixes = granted && state.mode != CaptureMode.Code,
            onOpenFixes = { showFixes = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (granted) {
            // Held sideways, the status and any hint read across the middle of the view, turned
            // upright; long pills turned on their side at the top would run down over the page.
            Column(
                modifier = if (turn % 180 == 0) {
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
                } else {
                    Modifier.align(Alignment.Center)
                }.graphicsLayer { rotationZ = upright },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusPill(text = statusText(state))
                AnimatedVisibility(visible = state.hint != null, enter = fadeIn(), exit = fadeOut()) {
                    // Keeps showing the last hint while it fades out.
                    var last by remember { mutableStateOf(state.hint) }
                    state.hint?.let { last = it }
                    last?.let { hint ->
                        HintPill(
                            hint = hint,
                            fixed = when (hint) {
                                CaptureHint.Finger -> state.fixes.removeFingers
                                CaptureHint.Handwriting -> state.fixes.removeHandwriting
                                else -> false
                            },
                            onOpenFixes = { showFixes = true },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        if (state.reviewing) {
            CapturedPages(
                captures = state.captures,
                onOutline = viewModel::setOutline,
                onMoveCorner = viewModel::moveCorner,
                onDelete = viewModel::deleteCapture,
                onClose = viewModel::closePages,
            )
        }
    }

    if (showFixes) {
        AutoFixSheet(fixes = state.fixes, onChange = viewModel::setFixes, onDismiss = { showFixes = false })
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(pluralStringResource(R.plurals.capture_keep_title, state.captures.size, state.captures.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    viewModel.save(title)
                }) { Text(stringResource(R.string.capture_keep_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    viewModel.discard()
                    onClose()
                }) { Text(stringResource(R.string.capture_keep_discard), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun CameraLayer(viewModel: CaptureViewModel, state: CaptureUiState, title: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.capture_failed)
    val flashAlpha = remember { Animatable(0f) }

    val controller = remember {
        val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
            imageCaptureResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourThree)
                .setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }

    DisposableEffect(controller, lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = PageAnalyzer(
            mode = { viewModel.state.value.mode },
            toScreen = context.backCameraToPortrait(),
            onCode = viewModel::onCode,
            onResult = { quad, sharpness, aspect, glare, signals -> viewModel.onFrame(quad, sharpness, aspect, glare, signals) },
        )
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.execute { analyzer.release() }
            executor.shutdown()
        }
    }

    LaunchedEffect(state.flash) {
        controller.imageCaptureFlashMode = if (state.flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    val takePicture by rememberUpdatedState {
        if (!viewModel.state.value.busy) {
            viewModel.onShutterPressed()
            val file = viewModel.newCaptureFile()
            controller.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        viewModel.onPictureSaved(file)
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (!reducedMotion) {
                            scope.launch {
                                flashAlpha.snapTo(0.3f)
                                flashAlpha.animateTo(0f, tween(80))
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        viewModel.onPictureFailed()
                        scope.launch { snackbar.showSnackbar(failedText) }
                    }
                },
            )
        }
    }
    LaunchedEffect(viewModel) { viewModel.shutterRequests.collect { takePicture() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { viewModel.import(it, title) }

    // ID cards are two pictures, front then back; the second one finishes the scan.
    LaunchedEffect(state.mode, state.captures.size) {
        if (state.mode == CaptureMode.IdCard && state.captures.size >= 2 && !state.modeLocked) viewModel.save(title)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.mode != CaptureMode.Code) {
            QuadOverlay(quad = state.quad, frameAspect = state.frameAspect, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha.value)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            BottomChrome(
                state = state,
                onShutter = { takePicture() },
                onModeChange = viewModel::setMode,
                onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onDone = { viewModel.save(title) },
                onOpenPages = viewModel::openPages,
            )
        }
    }

    state.code?.let { code -> CodeSheet(code, onDismiss = viewModel::dismissCode) }
}

/** What the status pill says, in words that fit the mode. */
@Composable
private fun statusText(state: CaptureUiState): String = when {
    state.mode == CaptureMode.Code -> stringResource(R.string.capture_status_code)
    state.status == CaptureStatus.Captured -> stringResource(R.string.capture_status_captured)
    state.glare -> stringResource(R.string.capture_status_glare)
    state.status == CaptureStatus.HoldSteady -> stringResource(R.string.capture_status_hold)
    state.mode == CaptureMode.IdCard && state.captures.isEmpty() -> stringResource(R.string.capture_status_card_front)
    state.mode == CaptureMode.IdCard -> stringResource(R.string.capture_status_card_back)
    state.mode == CaptureMode.Book -> stringResource(R.string.capture_status_book)
    state.mode == CaptureMode.Whiteboard -> stringResource(R.string.capture_status_board)
    else -> stringResource(R.string.capture_status_searching)
}

@Composable
private fun TopChrome(
    /** Degrees that keep the controls upright for how the phone is held. */
    rotation: Float,
    /** Held sideways: the controls drop their words and stay compact. */
    sideways: Boolean,
    flash: Boolean,
    autoCapture: Boolean,
    showCameraControls: Boolean,
    showAuto: Boolean,
    onClose: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleAuto: () -> Unit,
    orientation: CameraOrientation,
    onCycleOrientation: () -> Unit,
    /** How many Auto-fix fixes are on, shown on its button. */
    fixesOn: Int,
    showFixes: Boolean,
    onOpenFixes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Scrim, CircleShape),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.capture_close),
                    tint = ChromeInk,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            Spacer(Modifier.weight(1f))
            if (showFixes) {
                val word = stringResource(R.string.capture_fixes)
                ChromePill(
                    icon = Icons.Rounded.AutoFixHigh,
                    label = if (fixesOn > 0) "$word · $fixesOn" else word,
                    onClick = onOpenFixes,
                    rotation = rotation,
                    showLabel = !sideways,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (showAuto) {
                ChromePill(
                    icon = if (autoCapture) Icons.Rounded.MotionPhotosAuto else Icons.Rounded.TouchApp,
                    label = stringResource(if (autoCapture) R.string.capture_auto else R.string.capture_manual),
                    onClick = onToggleAuto,
                    rotation = rotation,
                    showLabel = !sideways,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (showCameraControls) {
                // Icon only: the top bar is full, and the icon itself shows the way it's locked.
                ChromePill(
                    icon = when (orientation) {
                        CameraOrientation.Auto -> Icons.Rounded.ScreenRotation
                        CameraOrientation.Portrait -> Icons.Rounded.StayCurrentPortrait
                        CameraOrientation.Landscape -> Icons.Rounded.StayCurrentLandscape
                    },
                    label = stringResource(
                        when (orientation) {
                            CameraOrientation.Auto -> R.string.capture_orientation_auto
                            CameraOrientation.Portrait -> R.string.capture_orientation_portrait
                            CameraOrientation.Landscape -> R.string.capture_orientation_landscape
                        },
                    ),
                    onClick = onCycleOrientation,
                    rotation = rotation,
                    showLabel = false,
                )
                Spacer(Modifier.width(8.dp))
                ChromePill(
                    icon = if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    label = stringResource(if (flash) R.string.capture_flash_on else R.string.capture_flash_off),
                    onClick = onToggleFlash,
                    rotation = rotation,
                    // The icon says it all; the word stays for screen readers.
                    showLabel = false,
                )
            }
        }
    }
}

/**
 * A word about the shot, under the status: a warning (amber) or a tip (a light bulb), each with
 * what to do. Hints Auto-fix can deal with open it on a tap; once it's on, they say so instead.
 */
@Composable
private fun HintPill(hint: CaptureHint, fixed: Boolean, onOpenFixes: () -> Unit, modifier: Modifier = Modifier) {
    val (title, tip) = when (hint) {
        CaptureHint.Finger -> R.string.capture_hint_finger to if (fixed) R.string.capture_hint_finger_fixed else R.string.capture_hint_finger_tip
        CaptureHint.Dark -> R.string.capture_hint_dark to R.string.capture_hint_dark_tip
        CaptureHint.Steep -> R.string.capture_hint_steep to R.string.capture_hint_steep_tip
        CaptureHint.Far -> R.string.capture_hint_far to R.string.capture_hint_far_tip
        CaptureHint.Handwriting -> R.string.capture_hint_handwriting to if (fixed) R.string.capture_hint_handwriting_fixed else R.string.capture_hint_handwriting_tip
    }
    val opensFixes = !fixed && (hint == CaptureHint.Finger || hint == CaptureHint.Handwriting)
    val openLabel = stringResource(R.string.capture_hint_open_fixes)
    Surface(
        color = Scrim,
        contentColor = ChromeInk,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .widthIn(max = 320.dp)
            .then(if (opensFixes) Modifier.clickable(onClickLabel = openLabel, onClick = onOpenFixes) else Modifier)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hint.warning) Icons.Rounded.WarningAmber else Icons.Rounded.Lightbulb,
                contentDescription = null,
                tint = if (hint.warning) Amber else ChromeInk,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(tip), style = MaterialTheme.typography.bodySmall, color = ChromeInk.copy(alpha = 0.8f))
            }
        }
    }
}

/** Warnings only: status stays paper-white and actions orange. */
private val Amber = Color(0xFFFFC56B)

/** What the camera is doing, in a few words; TalkBack reads changes as they happen. */
@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Scrim,
        contentColor = ChromeInk,
        shape = CircleShape,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** A neutral camera control on the scrim: icon and a word. */
@Composable
private fun ChromePill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    showLabel: Boolean = true,
) {
    Surface(
        onClick = onClick,
        color = Scrim,
        contentColor = ChromeInk,
        shape = CircleShape,
        modifier = modifier
            .height(48.dp)
            .semantics { contentDescription = label },
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
            if (showLabel) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BottomChrome(
    state: CaptureUiState,
    onShutter: () -> Unit,
    onModeChange: (CaptureMode) -> Unit,
    onImport: () -> Unit,
    onDone: () -> Unit,
    onOpenPages: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Scrim)
            .navigationBarsPadding()
            .padding(top = 16.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Modes can not change mid-batch: a batch becomes one kind of document.
        if (!state.modeLocked && state.captures.isEmpty()) {
            ModeWheel(selected = state.mode, onSelect = onModeChange)
            Spacer(Modifier.height(12.dp))
        }
        if (state.mode == CaptureMode.Code) {
            Text(
                stringResource(R.string.capture_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = ChromeInk.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .height(80.dp),
            )
            return@Column
        }
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp),
        ) {
            if (state.captures.isEmpty()) {
                ChromePill(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.capture_import), onImport, Modifier.align(Alignment.CenterStart))
            } else {
                BatchStack(captures = state.captures.map { it.file }, onClick = onOpenPages, modifier = Modifier.align(Alignment.CenterStart))
            }
            Shutter(
                progress = if (state.autoCapture) state.progress else 0f,
                enabled = !state.busy && !state.saving,
                onClick = onShutter,
                modifier = Modifier.align(Alignment.Center),
            )
            DoneButton(
                count = state.captures.size,
                saving = state.saving,
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Modes on a wheel, like a camera app's: swipe or tap, and the mode in the middle is the one in
 * use, larger and in full color; the others shrink and fade toward the edges.
 */
@Composable
private fun ModeWheel(selected: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    val modes = WHEEL_ORDER
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Each name gets a slot as wide as it is, with the same gap between every two names, so a
    // short word like Book sits as close to its neighbors as a long one like Whiteboard.
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    val labels = modes.map { stringResource(it.label()) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(labels, style, density) {
        labels.map { with(density) { measurer.measure(it, style).size.width.toDp() } + SLOT_PADDING * 2 }
    }

    var placed by remember { mutableStateOf(false) }
    // Start on the chosen mode, once the wheel has been laid out; then the item nearest the
    // middle when the wheel settles becomes the mode. (Listening before then would take the
    // first item, sitting in the middle for a moment, as a choice.)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        list.centerOn(modes.indexOf(currentSelected), animate = false)
        placed = true
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { moving ->
            if (moving) return@collect
            val middle = centered(list) ?: return@collect
            if (modes[middle] != currentSelected) currentOnSelect(modes[middle])
        }
    }
    // A mode set from elsewhere brings the wheel round to it.
    LaunchedEffect(selected, placed) {
        val index = modes.indexOf(selected)
        if (placed && centered(list) != index) list.centerOn(index, animate = true)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Center),
            horizontalArrangement = Arrangement.spacedBy(WHEEL_GAP),
            // Room for the first and last names to come to the middle.
            contentPadding = PaddingValues(start = (maxWidth - widths.first()) / 2, end = (maxWidth - widths.last()) / 2),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                // Names fade toward the edges but stay partly there, a hint that the wheel turns.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.22f to Color.Black,
                            0.78f to Color.Black,
                            1f to Color.Black.copy(alpha = 0.35f),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .semantics { role = Role.RadioButton },
        ) {
            items(modes.size) { index ->
                val mode = modes[index]
                // 1 at the middle, falling to 0 a name or two away.
                val nearness by remember {
                    derivedStateOf {
                        val info = list.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@derivedStateOf 0f
                        val viewportMiddle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val itemMiddle = item.offset + item.size / 2f
                        (1f - abs(itemMiddle - viewportMiddle) / (item.size + WHEEL_GAP.toPx(density))).coerceIn(0f, 1f)
                    }
                }
                val active = mode == selected
                Box(
                    Modifier
                        .width(widths[index])
                        .fillMaxHeight()
                        .clickable(onClickLabel = labels[index]) { scope.launch { list.centerOn(index, animate = true) } }
                        .semantics { this.selected = active },
                    contentAlignment = Alignment.Center,
                ) {
                    // All names share one size and weight, so slots fit them exactly; the one in
                    // the middle grows about its own center, the same on both sides.
                    Text(
                        labels[index],
                        style = style,
                        color = if (active) colors.primary else ChromeInk,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.graphicsLayer {
                            val scale = 1f + ACTIVE_GROWTH * nearness
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.6f + 0.4f * nearness
                        },
                    )
                }
            }
        }
    }
}

/** The index of the item whose middle is nearest the wheel's middle. */
private fun centered(list: LazyListState): Int? {
    val info = list.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - middle) }?.index
}

/** Scrolls so item [index]'s middle is at the wheel's middle. */
private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val middle = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val delta = item.offset + item.size / 2f - middle
    if (animate) animateScrollBy(delta) else scrollBy(delta)
}

private fun androidx.compose.ui.unit.Dp.toPx(density: androidx.compose.ui.unit.Density) = with(density) { toPx() }

/** Space either side of a name inside its slot (a bigger touch target), and between slots. */
private val SLOT_PADDING = 12.dp
private val WHEEL_GAP = 12.dp
/** How much bigger the name in the middle is drawn. */
private const val ACTIVE_GROWTH = 0.22f

/** Document, the usual choice, in the middle; the others either side, one swipe away. */
private val WHEEL_ORDER = listOf(CaptureMode.IdCard, CaptureMode.Book, CaptureMode.Document, CaptureMode.Whiteboard, CaptureMode.Code)

/** Orange ring, white core. While auto-capture waits for a steady page, the ring fills clockwise. */
@Composable
private fun Shutter(progress: Float, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val animated by animateFloatAsState(progress, tween(90), label = "shutterProgress")
    val description = stringResource(R.string.capture_shutter)
    Box(
        modifier
            .size(80.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .drawBehind {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(ring, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                if (animated > 0f) {
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                drawCircle(if (enabled) Color.White else Color.White.copy(alpha = 0.5f), radius = size.minDimension / 2 - stroke - 5.dp.toPx())
            },
    )
}

@Composable
private fun BatchStack(captures: List<File>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (captures.isEmpty()) {
        Spacer(modifier.size(56.dp))
        return
    }
    val last by rememberPageImage(captures.last().path, maxSide = 200)
    val description = pluralStringResource(R.plurals.capture_pages_taken, captures.size, captures.size)
    val open = stringResource(R.string.capture_pages_open)
    // Tap to look over the pages so far, without ending the scan.
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = open, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            last?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Surface(
            shape = CircleShape,
            color = ChromeInk,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-8).dp)
                .size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(captures.size.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DoneButton(count: Int, saving: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = count > 0 && !saving,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (count > 0) 0.18f else 0.08f),
        contentColor = ChromeInk.copy(alpha = if (count > 0) 1f else 0.5f),
        modifier = modifier.height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ChromeInk)
            } else {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.capture_done), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Paper-white outline of the detected page: status, not an action, so never orange. */
@Composable
private fun QuadOverlay(quad: Quad?, frameAspect: Float, modifier: Modifier = Modifier) {
    val color = LocalOscannerExtras.current.detectionQuad
    Canvas(modifier) {
        val q = quad ?: return@Canvas
        // Same center-crop the preview uses (FILL_CENTER).
        val (shownW, shownH) = if (size.width / size.height > frameAspect) {
            size.width to size.width / frameAspect
        } else {
            size.height * frameAspect to size.height
        }
        val ox = (size.width - shownW) / 2
        val oy = (size.height - shownH) / 2
        val points = q.corners.map { Offset(ox + it.x * shownW, oy + it.y * shownH) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, color.copy(alpha = 0.12f))
        drawPath(path, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        points.forEach { drawCircle(color, radius = 5.dp.toPx(), center = it) }
    }
}

@Composable
private fun PermissionPrompt(
    showSettings: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.capture_permission_title),
            style = MaterialTheme.typography.titleLarge,
            color = ChromeInk,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.capture_permission_body),
            style = MaterialTheme.typography.bodyLarge,
            color = ChromeInk.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPill(
            icon = Icons.Rounded.Check,
            label = stringResource(if (showSettings) R.string.capture_permission_settings else R.string.capture_permission_allow),
            onClick = if (showSettings) onOpenSettings else onAllow,
        )
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
