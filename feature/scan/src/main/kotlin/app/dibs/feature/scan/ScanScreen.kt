// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.money.MoneyInput
import app.dibs.core.domain.receipt.ReceiptFlag
import app.dibs.core.domain.receipt.ReceiptHandoff
import app.dibs.core.ui.theme.Dimens
import java.io.File
import androidx.camera.core.Preview as CameraPreview

/**
 * FR-R1/R3/R7: capture (camera or gallery, multi-frame) → mandatory review.
 * Camera being unavailable is never a dead end (edge 33) — gallery and
 * manual entry are always offered, and manual entry is a primary path in the
 * expense editor regardless (FR-R8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanRoute(
    onUseHandoff: (ReceiptHandoff) -> Unit,
    onManualEntry: () -> Unit,
    onBack: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsState()
    val context = LocalContext.current

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { viewModel.addFrame(it.readBytes()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
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
        when (val current = step) {
            is ScanStep.Capture -> CaptureStep(
                step = current,
                onFrame = viewModel::addFrame,
                onRemoveFrame = viewModel::removeLastFrame,
                onParse = viewModel::parse,
                onGallery = { galleryPicker.launch(arrayOf("image/*")) },
                onManualEntry = onManualEntry,
                modifier = Modifier.padding(padding),
            )
            ScanStep.Parsing -> Box(Modifier.padding(padding).fillMaxSize()) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.padding(Dimens.spacingXl),
                )
            }
            is ScanStep.Review -> ReviewStep(
                review = current,
                viewModel = viewModel,
                onUse = {
                    // FR-E8: the photo stays in app-private storage; its name
                    // is the parse fingerprint, which is also the edge-47 key.
                    val bytes = viewModel.firstFrameBytes
                    val ref = if (bytes != null) {
                        val dir = File(context.filesDir, "receipts").apply { mkdirs() }
                        val name = "${current.fingerprint}.jpg"
                        File(dir, name).writeBytes(bytes)
                        name
                    } else {
                        null
                    }
                    viewModel.handoff(ref)?.let(onUseHandoff)
                },
                onManualEntry = onManualEntry,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun CaptureStep(
    step: ScanStep.Capture,
    onFrame: (ByteArray) -> Unit,
    onRemoveFrame: () -> Unit,
    onParse: () -> Unit,
    onGallery: () -> Unit,
    onManualEntry: () -> Unit,
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
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
    ) {
        if (cameraGranted) {
            Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium)
            CameraCapture(onCaptured = onFrame, modifier = Modifier.weight(1f))
        } else {
            Text(stringResource(R.string.scan_permission_denied))
            Spacer(Modifier.weight(1f))
        }

        step.parseFailed?.let { failure ->
            Text(
                stringResource(
                    when (failure) {
                        ScanStep.Capture.Failure.NOTHING_FOUND -> R.string.scan_failed_nothing
                        ScanStep.Capture.Failure.TIMED_OUT -> R.string.scan_failed_timeout
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (step.frameCount > 0) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.scan_frames, step.frameCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRemoveFrame) {
                    Text(stringResource(R.string.scan_remove_frame))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            OutlinedButton(onClick = onGallery, modifier = Modifier.testTag("scan-gallery")) {
                Text(stringResource(R.string.scan_gallery))
            }
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.testTag("scan-manual")) {
                Text(stringResource(R.string.scan_manual))
            }
        }
        Button(
            onClick = onParse,
            enabled = step.frameCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan-parse"),
        ) {
            Text(stringResource(R.string.scan_parse))
        }
        Text(
            stringResource(R.string.scan_nonlatin_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** CameraX preview + capture with the FR-R1 guide frame. */
@Composable
private fun CameraCapture(onCaptured: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }

    Column(modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(Dimens.guideFrameStroke, MaterialTheme.colorScheme.primary),
        ) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).also { previewView ->
                        val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = CameraPreview.Builder().build()
                            preview.surfaceProvider = previewView.surfaceProvider
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                        }, ContextCompat.getMainExecutor(viewContext))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Button(
            onClick = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            image.use { proxy ->
                                val buffer = proxy.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                onCaptured(bytes)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) = Unit
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.spacingSm)
                .testTag("scan-capture"),
        ) {
            Text(stringResource(R.string.scan_capture))
        }
    }
}

@Composable
private fun ReviewStep(
    review: ScanStep.Review,
    viewModel: ScanViewModel,
    onUse: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = review.draft
    val currency = review.currency
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
    ) {
        Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.review_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GateBanner(draft.gatePassed, draft.taxMinor != null || draft.tipMinor != null)
        if (ReceiptFlag.TOTAL_ONLY in draft.flags) {
            Text(stringResource(R.string.review_flag_total_only), color = MaterialTheme.colorScheme.tertiary)
        }
        if (ReceiptFlag.UNPARSED_REGIONS in draft.flags) {
            Text(stringResource(R.string.review_flag_unparsed), color = MaterialTheme.colorScheme.tertiary)
        }

        Text(stringResource(R.string.review_items), style = MaterialTheme.typography.labelLarge)
        draft.items.forEachIndexed { index, item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.label,
                    onValueChange = { viewModel.updateItemLabel(index, it) },
                    label = { Text(stringResource(R.string.review_item_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(Dimens.spacingXs))
                MoneyField(
                    minor = item.totalMinor,
                    currency = currency,
                    label = stringResource(R.string.review_item_amount),
                    onMinorChange = { viewModel.updateItemAmount(index, it ?: 0) },
                    modifier = Modifier.weight(MONEY_FIELD_WEIGHT),
                )
                IconButton(onClick = { viewModel.removeItem(index) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.review_item_delete),
                    )
                }
            }
        }
        TextButton(onClick = viewModel::addItem, modifier = Modifier.testTag("review-add-item")) {
            Text(stringResource(R.string.review_add_item))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            MoneyField(
                minor = draft.taxMinor,
                currency = currency,
                label = stringResource(R.string.review_tax),
                onMinorChange = viewModel::setTax,
                modifier = Modifier.weight(1f),
            )
            MoneyField(
                minor = draft.tipMinor,
                currency = currency,
                label = stringResource(R.string.review_tip),
                onMinorChange = viewModel::setTip,
                modifier = Modifier.weight(1f),
            )
            MoneyField(
                minor = draft.totalMinor,
                currency = currency,
                label = stringResource(R.string.review_total),
                onMinorChange = viewModel::setTotal,
                modifier = Modifier.weight(1f),
            )
        }

        if (review.looksAlreadyAdded) {
            Text(stringResource(R.string.review_duplicate), color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            OutlinedButton(onClick = onManualEntry) {
                Text(stringResource(R.string.scan_manual))
            }
            Button(
                onClick = onUse,
                enabled = draft.items.isNotEmpty() || draft.totalMinor != null,
                modifier = Modifier.testTag("review-use"),
            ) {
                Text(stringResource(R.string.review_use))
            }
        }
        Spacer(Modifier.height(Dimens.spacingXl))
    }
}

@Composable
private fun GateBanner(gatePassed: Boolean?, hasTaxOrTip: Boolean) {
    if (gatePassed == null) return
    val suffix = if (hasTaxOrTip) stringResource(R.string.review_gate_taxes) else ""
    if (gatePassed) {
        Text(
            stringResource(R.string.review_gate_ok, suffix),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            stringResource(R.string.review_gate_mismatch, suffix),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Money text field over minor units; blank ↔ null, invalid text keeps the last valid value. */
@Composable
private fun MoneyField(
    minor: Long?,
    currency: CurrencyCode,
    label: String,
    onMinorChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One-way: text drives the ViewModel; never re-derive text from state or
    // the field would reformat under the user's cursor mid-keystroke.
    var text by rememberSaveable {
        mutableStateOf(minor?.let { MoneyInput.toPlainString(Money(it), currency) } ?: "")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            if (newText.isBlank()) {
                onMinorChange(null)
            } else {
                MoneyInput.parseSigned(newText, currency)?.let { onMinorChange(it.minorUnits) }
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

private const val MONEY_FIELD_WEIGHT = 0.7f
