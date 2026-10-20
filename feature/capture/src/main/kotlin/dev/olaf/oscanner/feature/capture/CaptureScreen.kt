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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import dev.olaf.oscanner.core.design.pressScale
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.design.rememberReducedMotion
import dev.olaf.oscanner.core.model.Quad
import kotlinx.coroutines.launch
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
    val turn by rememberDeviceTurn()
    val upright = rememberUprightRotation(turn)
    LaunchedEffect(turn) { viewModel.setDeviceTurn(turn) }

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
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (granted) {
            // Held sideways, the status reads across the middle of the view, turned upright;
            // a long pill turned on its side at the top would run down over the page.
            StatusPill(
                text = statusText(state),
                modifier = if (turn % 180 == 0) {
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
                } else {
                    Modifier.align(Alignment.Center)
                }.graphicsLayer { rotationZ = upright },
            )
        }

        if (state.reviewing) {
            CapturedPages(
                captures = state.captures,
                onOutline = viewModel::setOutline,
                onDelete = viewModel::deleteCapture,
                onClose = viewModel::closePages,
            )
        }
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
            onResult = { quad, sharpness, aspect, glare -> viewModel.onFrame(quad, sharpness, aspect, glare) },
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
                ChromePill(
                    icon = if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    label = stringResource(if (flash) R.string.capture_flash_on else R.string.capture_flash_off),
                    onClick = onToggleFlash,
                    rotation = rotation,
                    showLabel = !sideways,
                )
            }
        }
    }
}

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
    val list = rememberLazyListState(initialFirstVisibleItemIndex = modes.indexOf(selected))
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // The item nearest the middle once the wheel settles becomes the mode.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (moving) return@collect
            val middle = centered(list) ?: return@collect
            if (modes[middle] != currentSelected) currentOnSelect(modes[middle])
        }
    }
    // A mode set from elsewhere (say, after a scan) brings the wheel round to it.
    LaunchedEffect(selected) {
        val index = modes.indexOf(selected)
        if (centered(list) != index) list.animateScrollToItem(index)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = (maxWidth - ITEM_WIDTH) / 2
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                // Names fade out toward the edges instead of being cut off there.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .semantics { role = Role.RadioButton },
        ) {
            items(modes.size) { index ->
                val mode = modes[index]
                // 1 at the middle, falling to 0 a couple of items away.
                val nearness by remember {
                    derivedStateOf {
                        val info = list.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@derivedStateOf 0f
                        val viewportMiddle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val itemMiddle = item.offset + item.size / 2f
                        (1f - abs(itemMiddle - viewportMiddle) / (item.size * 2f)).coerceIn(0f, 1f)
                    }
                }
                val active = mode == selected
                Box(
                    Modifier
                        .width(ITEM_WIDTH)
                        .fillMaxHeight()
                        .clickable(onClickLabel = stringResource(mode.label())) {
                            scope.launch { list.animateScrollToItem(index) }
                        }
                        .semantics { this.selected = active },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(mode.label()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) colors.primary else ChromeInk,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            val scale = 0.8f + 0.35f * nearness
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.45f + 0.55f * nearness
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

private val ITEM_WIDTH = 104.dp

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
