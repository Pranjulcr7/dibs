// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens
import java.util.concurrent.Executors
import androidx.camera.core.Preview as CameraPreview

/**
 * Scans an invite QR code shown on another phone (FR-Y1). The first code
 * whose text [accept] recognises is handed to [onScanned], exactly once.
 * Without camera permission this is never a dead end (edge 33): the screen
 * says so and sends the person back to paste a link or open a file.
 */
@Composable
fun QrScanRoute(
    accept: (String) -> Boolean,
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionAsked = true
        cameraGranted = granted
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    QrScanScreen(cameraGranted = cameraGranted, onBack = onBack, modifier = modifier) {
        QrCamera(accept = accept, onScanned = onScanned, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    cameraGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.scan_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        ) {
            if (cameraGranted) {
                Text(stringResource(R.string.qr_hint), style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(Dimens.guideFrameStroke, MaterialTheme.colorScheme.primary)
                        .testTag("qr-camera"),
                ) { camera() }
            } else {
                Text(
                    stringResource(R.string.qr_permission_denied),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("qr-no-camera"),
                )
                OutlinedButton(onClick = onBack, modifier = Modifier.testTag("qr-back")) {
                    Text(stringResource(R.string.qr_use_other_way))
                }
            }
        }
    }
}

/** CameraX preview plus an analysis stream decoded off the main thread. */
@Composable
private fun QrCamera(accept: (String) -> Boolean, onScanned: (String) -> Unit, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAccept by rememberUpdatedState(accept)
    val currentOnScanned by rememberUpdatedState(onScanned)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                val mainExecutor = ContextCompat.getMainExecutor(viewContext)
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = CameraPreview.Builder().build()
                    preview.surfaceProvider = previewView.surfaceProvider
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    var delivered = false
                    analysis.setAnalyzer(analysisExecutor) { image ->
                        val text = image.use(::decodeFrame)
                        if (text != null && !delivered && currentAccept(text)) {
                            delivered = true
                            mainExecutor.execute {
                                provider.unbindAll()
                                currentOnScanned(text)
                            }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, mainExecutor)
            }
        },
    )
}

/** The Y plane of a YUV_420_888 frame is exactly the luminance image zxing wants. */
private fun decodeFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val luminance = ByteArray(buffer.remaining())
    buffer.get(luminance)
    return QrDecoder.decode(luminance, plane.rowStride, image.width, image.height)
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun QrScanPreviewLight() {
    DibsTheme(dynamicColor = false) { QrScanScreen(cameraGranted = true, onBack = {}) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun QrScanPreviewDark() {
    DibsTheme(dynamicColor = false) { QrScanScreen(cameraGranted = false, onBack = {}) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun QrScanPreviewLargeFont() {
    DibsTheme(dynamicColor = false) { QrScanScreen(cameraGranted = false, onBack = {}) }
}
