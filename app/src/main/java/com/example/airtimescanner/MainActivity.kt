package com.example.airtimescanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// rebuild marker to force a real tree update
enum class AppScreen {
    Scanner,
    History
}

data class ScanUiState(
    val status: String = "Take a photo of the scratch card, then scan the recharge keys.",
    val rawText: String = "",
    val detectedKeys: List<String> = emptyList(),
    val activeKeyIndex: Int = 0
) {
    val activeKey: String
        get() = detectedKeys.getOrNull(activeKeyIndex).orEmpty()

    val hasMoreKeys: Boolean
        get() = activeKeyIndex < detectedKeys.lastIndex

    val isComplete: Boolean
        get() = detectedKeys.isNotEmpty() && activeKeyIndex >= detectedKeys.size

    val dialString: String
        get() = if (activeKey.isBlank()) "" else "*121*$activeKey#"
}

data class AppUiState(
    val screen: AppScreen = AppScreen.Scanner,
    val scan: ScanUiState = ScanUiState(),
    val history: List<RechargeRecord> = emptyList()
)

private data class ScanAttempt(
    val rawText: String,
    val detectedKeys: List<String>,
    val score: Int,
    val errorMessage: String = ""
)

class MainActivity : ComponentActivity() {
    private lateinit var store: RechargeStore
    private var pendingImageUri: Uri? = null
    private val appState = mutableStateOf(AppUiState())

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingImageUri
            if (success && uri != null) {
                processImage(uri)
            } else {
                updateStatus("Camera capture was cancelled.")
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                processImage(uri)
            } else {
                updateStatus("No image selected.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RechargeStore(this)
        refreshHistory()

        setContent {
            AirtimeScannerTheme {
                when (appState.value.screen) {
                    AppScreen.Scanner -> ScannerScreen(
                        uiState = appState.value.scan,
                        historyCount = appState.value.history.size,
                        onCapture = { launchCamera() },
                        onPickImage = { pickImageLauncher.launch("image/*") },
                        onOpenHistory = { openHistory() },
                        onPinChanged = { updatedPin ->
                            appState.value = appState.value.copy(
                                scan = appState.value.scan.copy(
                                    detectedKeys = if (updatedPin.isBlank()) emptyList() else listOf(updatedPin),
                                    activeKeyIndex = 0
                                )
                            )
                        },
                        onDial = { dialAirtime(appState.value.scan.activeKey) },
                        onMarkUsedAndNext = { markUsedAndNext() }
                    )

                    AppScreen.History -> HistoryScreen(
                        records = appState.value.history,
                        onBack = { openScanner() },
                        onRefresh = { refreshHistory() }
                    )
                }
            }
        }
    }

    private fun openScanner() {
        refreshHistory()
        appState.value = appState.value.copy(screen = AppScreen.Scanner)
    }

    private fun openHistory() {
        refreshHistory()
        appState.value = appState.value.copy(screen = AppScreen.History)
    }

    private fun refreshHistory() {
        appState.value = appState.value.copy(history = store.loadRecords())
    }

    private fun updateStatus(message: String) {
        appState.value = appState.value.copy(
            scan = appState.value.scan.copy(status = message)
        )
    }

    private fun launchCamera() {
        val photoFile = createImageFile()
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        pendingImageUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun processImage(uri: Uri) {
        updateStatus("Scanning image...")

        thread(name = "airtime-ocr") {
            val bitmaps = loadProcessedBitmaps(uri)
            if (bitmaps.isEmpty()) {
                runOnUiThread { updateStatus("Could not read the image. Try a clearer photo.") }
                return@thread
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val attempts = mutableListOf<ScanAttempt>()

            bitmaps.forEachIndexed { index, bitmap ->
                runOnUiThread { updateStatus("Scanning crop ${index + 1}/${bitmaps.size}...") }

                try {
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    val keys = extractRechargeKeys(text)
                    attempts += ScanAttempt(
                        rawText = text.text,
                        detectedKeys = keys,
                        score = scoreResult(text, keys)
                    )
                } catch (error: Exception) {
                    attempts += ScanAttempt(
                        rawText = "",
                        detectedKeys = emptyList(),
                        score = Int.MIN_VALUE,
                        errorMessage = error.localizedMessage ?: "unknown error"
                    )
                }
            }

            val allKeys = attempts.flatMap { it.detectedKeys }.distinct()
            val bestAttempt = attempts.maxByOrNull { it.score }

            if (allKeys.isNotEmpty()) {
                store.upsertDetectedKeys(allKeys)
            }

            runOnUiThread {
                appState.value = appState.value.copy(
                    history = store.loadRecords(),
                    scan = ScanUiState(
                        status = when {
                            allKeys.isNotEmpty() -> "Found ${allKeys.size} recharge key(s). Use the first one, then mark it used and move to the next."
                            bestAttempt?.errorMessage?.isNotBlank() == true -> "OCR failed: ${bestAttempt.errorMessage}"
                            else -> "No 17-digit recharge key found. Try a sharper photo of the scratch strip."
                        },
                        rawText = bestAttempt?.rawText.orEmpty(),
                        detectedKeys = allKeys,
                        activeKeyIndex = 0
                    )
                )
            }
        }
    }

    private fun markUsedAndNext() {
        val currentKey = appState.value.scan.activeKey
        if (currentKey.isBlank()) return

        store.markRedeemed(currentKey)
        refreshHistory()

        val scan = appState.value.scan
        val nextIndex = scan.activeKeyIndex + 1
        val nextScan = if (nextIndex < scan.detectedKeys.size) {
            scan.copy(
                activeKeyIndex = nextIndex,
                status = "Marked used. Ready for the next recharge key."
            )
        } else {
            scan.copy(
                activeKeyIndex = scan.detectedKeys.size,
                status = "All recharge keys on this card have been processed."
            )
        }

        appState.value = appState.value.copy(scan = nextScan)
    }

    private fun loadProcessedBitmaps(uri: Uri): List<Bitmap> {
        val rawBitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return emptyList()

        val oriented = applyExifRotation(uri, rawBitmap)

        val candidates = listOf(
            oriented,
            cropStrip(oriented, 0.20f, 0.46f),
            cropStrip(oriented, 0.28f, 0.38f),
            cropStrip(oriented, 0.34f, 0.30f)
        )

        return candidates
            .map { enhanceBitmap(it) }
            .distinctBy { it.width to it.height }
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                    else -> bitmap
                }
            } ?: bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropStrip(bitmap: Bitmap, topRatio: Float, heightRatio: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val cropWidth = (width * 0.92f).toInt().coerceAtMost(width)
        val cropHeight = (height * heightRatio).toInt().coerceAtMost(height)
        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = (height * topRatio).toInt().coerceIn(0, (height - cropHeight).coerceAtLeast(0))
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun enhanceBitmap(bitmap: Bitmap): Bitmap {
        val targetWidth = 1600
        val scale = targetWidth.toFloat() / bitmap.width.toFloat()
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, scaledHeight, true)

        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun extractRechargeKeys(text: Text): List<String> {
        val results = mutableListOf<String>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                results += extractKeysFromLine(line.text)
            }
        }

        if (results.isEmpty()) {
            results += extractKeysFromLine(text.text)
        }

        return results.distinct()
    }

    private fun extractKeysFromLine(text: String): List<String> {
        val results = mutableListOf<String>()

        // Primary pattern: strict Econet format XXXXX XXXX XXXX XXXX (5+4+4+4 = 17 digits)
        // Also handles OCR noise like dashes instead of spaces, or slight group size variation
        val strictPattern = Regex("""\d{4,6}[\s\-]+\d{3,5}[\s\-]+\d{3,5}[\s\-]+\d{3,5}""")
        strictPattern.findAll(text).forEach { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length == 17) {
                results += digits
            }
        }

        // Fallback: 17 digits with any whitespace/dashes between them
        if (results.isEmpty()) {
            val fallback = Regex("""(?:\d[\s\-]*){17}""")
            fallback.findAll(text).forEach { match ->
                val digits = match.value.filter(Char::isDigit)
                if (digits.length == 17) {
                    results += digits
                }
            }
        }

        return results.distinct()
    }

    private fun scoreResult(text: Text, keys: List<String>): Int {
        var score = keys.size * 100
        score += scoreLine(text.text)
        score += text.textBlocks.size * 2
        return score
    }

    private fun scoreLine(text: String): Int {
        val lower = text.lowercase(Locale.US)
        var score = 0

        // Reward strict 17-digit grouped key pattern (XXXXX XXXX XXXX XXXX)
        if (Regex("""\d{4,6}[\s\-]+\d{3,5}[\s\-]+\d{3,5}[\s\-]+\d{3,5}""").containsMatchIn(text)) score += 60
        if (Regex("""(?:\d[\s\-]*){17}""").containsMatchIn(text)) score += 40
        if (lower.contains("recharge") || lower.contains("key") || lower.contains("airtime")) score += 35
        if (lower.contains("econet") || lower.contains("telecel")) score += 20
        // Penalise lines that are clearly serial/batch/barcode metadata
        if (lower.contains("serial")) score -= 40
        if (lower.contains("batch")) score -= 40
        if (lower.contains("barcode")) score -= 20
        return score
    }

    private fun buildDialString(pin: String): String {
        return if (pin.isBlank()) "" else "*121*$pin#"
    }

    private fun dialAirtime(pin: String) {
        if (pin.isBlank()) return
        val ussd = buildDialString(pin)
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(ussd)}")
        }
        startActivity(intent)
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "images").apply { mkdirs() }
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }
}

private data class ScoredKey(
    val value: String,
    val score: Int
)

// ── Design tokens ────────────────────────────────────────────────────────────
private val BgDeep      = Color(0xFF0A1612)
private val BgMid       = Color(0xFF0F2218)
private val BgCard      = Color(0xFF152E20)
private val Accent      = Color(0xFF34C76F)
private val AccentDim   = Color(0xFF1E7A45)
private val OnAccent    = Color(0xFF001A0B)
private val Surface1    = Color(0xFF1A3828)
private val TextPrimary = Color(0xFFE8F5EC)
private val TextSecond  = Color(0xFF8FBFA0)
private val TextHint    = Color(0xFF4E7A60)
private val Danger      = Color(0xFFFF6B6B)
private val Gold        = Color(0xFFFFCC44)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerScreen(
    uiState: ScanUiState,
    historyCount: Int,
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onOpenHistory: () -> Unit,
    onPinChanged: (String) -> Unit,
    onDial: () -> Unit,
    onMarkUsedAndNext: () -> Unit
) {
    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Airtime Scanner",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "Econet Zimbabwe",
                            color = TextSecond,
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    BadgedBox(
                        badge = {
                            if (historyCount > 0) {
                                Badge(
                                    containerColor = Accent,
                                    contentColor = OnAccent
                                ) { Text("$historyCount", fontSize = 10.sp) }
                            }
                        }
                    ) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "View history",
                                tint = Accent,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgMid)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDeep)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            // ── Status banner ──────────────────────────────────────────────
            item {
                StatusBanner(status = uiState.status, hasKeys = uiState.detectedKeys.isNotEmpty())
            }

            // ── Active key card ────────────────────────────────────────────
            item {
                ActiveKeyCard(
                    uiState = uiState,
                    onPinChanged = onPinChanged,
                    onDial = onDial,
                    onMarkUsedAndNext = onMarkUsedAndNext
                )
            }

            // ── Capture card ───────────────────────────────────────────────
            item {
                CaptureCard(onCapture = onCapture, onPickImage = onPickImage)
            }

            // ── Detected keys list ─────────────────────────────────────────
            if (uiState.detectedKeys.isNotEmpty()) {
                item {
                    DetectedKeysCard(
                        keys = uiState.detectedKeys,
                        activeIndex = uiState.activeKeyIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: String, hasKeys: Boolean) {
    val bg = if (hasKeys) Color(0xFF0E3320) else Color(0xFF111E17)
    val accent = if (hasKeys) Accent else TextSecond
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = status,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActiveKeyCard(
    uiState: ScanUiState,
    onPinChanged: (String) -> Unit,
    onDial: () -> Unit,
    onMarkUsedAndNext: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Current Key",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (uiState.detectedKeys.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AccentDim
                    ) {
                        Text(
                            "${uiState.activeKeyIndex + 1} / ${uiState.detectedKeys.size}",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Key display / input
            OutlinedTextField(
                value = uiState.activeKey,
                onValueChange = onPinChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Recharge Key", color = TextSecond) },
                placeholder = { Text("17-digit recharge key", color = TextHint) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Surface1,
                    cursorColor = Accent
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Dial string chip
            if (uiState.dialString.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface1)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Text(
                        uiState.dialString,
                        color = Accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDial,
                    enabled = uiState.activeKey.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent,
                        disabledContainerColor = Surface1,
                        disabledContentColor = TextHint
                    )
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Dial Now", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onMarkUsedAndNext,
                    enabled = uiState.activeKey.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Surface1,
                        contentColor = TextPrimary,
                        disabledContainerColor = BgCard,
                        disabledContentColor = TextHint
                    )
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Used & Next", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CaptureCard(onCapture: () -> Unit, onPickImage: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Capture Card",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Keep all scratch strips visible and in good light.",
                color = TextSecond,
                fontSize = 13.sp
            )

            Button(
                onClick = onCapture,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan Scratch Card", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }

            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentDim),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick from Gallery", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun DetectedKeysCard(keys: List<String>, activeIndex: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Detected Keys", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${keys.size} found", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            keys.forEachIndexed { index, key ->
                val isActive = index == activeIndex
                val isDone = index < activeIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isActive -> Color(0xFF0E3320)
                                isDone -> Color(0xFF101A13)
                                else -> Surface1
                            }
                        )
                        .then(
                            if (isActive) Modifier.border(1.dp, Accent, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isActive) Accent else if (isDone) TextHint else Surface1),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${index + 1}",
                                color = if (isActive) OnAccent else TextSecond,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            key,
                            color = when {
                                isActive -> TextPrimary
                                isDone -> TextHint
                                else -> TextSecond
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 1.sp
                        )
                    }
                    if (isDone) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Used", tint = TextHint, modifier = Modifier.size(16.dp))
                    } else if (isActive) {
                        Surface(shape = RoundedCornerShape(6.dp), color = Accent) {
                            Text("Active", color = OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── History Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    records: List<RechargeRecord>,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("History", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("${records.size} stored key(s)", color = TextSecond, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Accent)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgMid)
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgDeep)
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = TextHint, modifier = Modifier.size(56.dp))
                    Text("No stored keys yet", color = TextSecond, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Scan a scratch card to get started.", color = TextHint, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgDeep)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                itemsIndexed(records) { _, record ->
                    HistoryRecordCard(record = record)
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(record: RechargeRecord) {
    val isUsed = record.redeemedAt != null
    val statusColor = if (isUsed) TextHint else Accent
    val statusBg = if (isUsed) Color(0xFF111A13) else Color(0xFF0E3320)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    record.key,
                    color = if (isUsed) TextHint else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = statusBg) {
                    Text(
                        if (isUsed) "Used" else "Pending",
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LabelValue("Saved", record.scannedAt.toDisplayDate())
                LabelValue("Expires", record.expiresAt.toDisplayDate())
            }

            if (record.redeemedAt != null) {
                LabelValue("Used on", record.redeemedAt.toDisplayDate())
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextHint, fontSize = 11.sp)
        Text(value, color = TextSecond, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AirtimeScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = BgDeep,
            surface = BgCard,
            onSurface = TextPrimary,
            onBackground = TextPrimary
        ),
        content = content
    )
}

private fun Long.toDisplayDate(): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return format.format(Date(this))
}
