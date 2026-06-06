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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                            else -> "No 17-digit recharge key found. Try a sharper photo of the middle strip."
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
        return Regex("(?:\\d[\\s-]*){17}")
            .findAll(text)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length == 17 }
            .toList()
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

        if (Regex("(?:\\d[\\s-]*){17}").containsMatchIn(text)) score += 50
        if (lower.contains("recharge") || lower.contains("key") || lower.contains("airtime")) score += 35
        if (Regex("\\b(?:\\d{4,5}[\\s-]){3}\\d{4,5}\\b").containsMatchIn(text)) score += 25
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

data class RechargeRecord(
    val key: String,
    val scannedAt: Long,
    val redeemedAt: Long? = null
) {
    val expiresAt: Long
        get() = scannedAt + 30L * 24L * 60L * 60L * 1000L

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now >= expiresAt
    }
}

class RechargeStore(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("recharge_store", android.content.Context.MODE_PRIVATE)

    fun loadRecords(): List<RechargeRecord> {
        val raw = prefs.getString("records", "[]").orEmpty()
        val now = System.currentTimeMillis()
        val records = parseRecords(raw)
            .filterNot { it.isExpired(now) }
            .sortedByDescending { it.scannedAt }

        if (records.size != parseRecords(raw).size) {
            saveRecords(records)
        }

        return records
    }

    fun upsertDetectedKeys(keys: List<String>) {
        if (keys.isEmpty()) return

        val now = System.currentTimeMillis()
        val existing = loadRecords().associateBy { it.key }.toMutableMap()

        keys.forEach { key ->
            if (!existing.containsKey(key)) {
                existing[key] = RechargeRecord(key = key, scannedAt = now, redeemedAt = null)
            }
        }

        saveRecords(existing.values.sortedByDescending { it.scannedAt })
    }

    fun markRedeemed(key: String) {
        if (key.isBlank()) return

        val now = System.currentTimeMillis()
        val updated = loadRecords().map { record ->
            if (record.key == key && record.redeemedAt == null) {
                record.copy(redeemedAt = now)
            } else {
                record
            }
        }
        saveRecords(updated)
    }

    private fun saveRecords(records: List<RechargeRecord>) {
        val array = org.json.JSONArray()
        records.filterNot { it.isExpired() }.forEach { record ->
            array.put(
                org.json.JSONObject().apply {
                    put("key", record.key)
                    put("scannedAt", record.scannedAt)
                    if (record.redeemedAt != null) {
                        put("redeemedAt", record.redeemedAt)
                    } else {
                        put("redeemedAt", org.json.JSONObject.NULL)
                    }
                }
            )
        }
        prefs.edit().putString("records", array.toString()).apply()
    }

    private fun parseRecords(raw: String): List<RechargeRecord> {
        if (raw.isBlank()) return emptyList()

        val array = org.json.JSONArray(raw)
        val results = mutableListOf<RechargeRecord>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key").trim()
            if (key.isBlank()) continue

            val scannedAt = obj.optLong("scannedAt", System.currentTimeMillis())
            val redeemedAt = if (obj.isNull("redeemedAt")) null else obj.optLong("redeemedAt")
            results += RechargeRecord(key = key, scannedAt = scannedAt, redeemedAt = redeemedAt)
        }

        return results.distinctBy { it.key }
    }
}

data class AppUiState(
    val screen: AppScreen = AppScreen.Scanner,
    val scan: ScanUiState = ScanUiState(),
    val history: List<RechargeRecord> = emptyList()
)

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
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0E1B16), Color(0xFF143428), Color(0xFF1F5A45))
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Airtime Scanner") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF4F6F4))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Scan every recharge key on the card, then dial them one by one.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = uiState.status)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF11261D))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Current Key", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }

                    OutlinedTextField(
                        value = uiState.activeKey,
                        onValueChange = onPinChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Recharge key") },
                        placeholder = { Text("The current 17-digit key") }
                    )

                    Text(
                        text = "Dial string: ${uiState.dialString.ifBlank { "waiting for scan..." }}",
                        color = Color(0xFFD5E8D4)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onDial,
                            enabled = uiState.activeKey.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Open Dialer")
                        }

                        OutlinedButton(
                            onClick = onMarkUsedAndNext,
                            enabled = uiState.activeKey.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Used & Next")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7F5))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Take a clear photo. Keep all scratch strips visible and upright.")
                    Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scan Scratch Card")
                    }
                    Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Pick Photo from Gallery")
                    }
                    OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Stored Numbers ($historyCount)")
                    }
                }
            }

            if (uiState.detectedKeys.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7F5))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Detected Keys", fontWeight = FontWeight.SemiBold)
                        Text("${uiState.detectedKeys.size} recharge key(s) found on this image.")
                        uiState.detectedKeys.forEachIndexed { index, key ->
                            FilterChip(
                                selected = index == uiState.activeKeyIndex,
                                onClick = { },
                                label = { Text("${index + 1}. $key") }
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                        }
                    }
                }
            }

            if (uiState.rawText.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7F5))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("OCR text", fontWeight = FontWeight.SemiBold)
                        Text(text = uiState.rawText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    records: List<RechargeRecord>,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0B1410), Color(0xFF10261D), Color(0xFF184734))
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Recharge History") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF4F6F4))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Stored recharge keys are kept for 30 days.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("This view shows the scanned numbers, when they were saved, and whether they were marked used.")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) { Text("Back") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }

            if (records.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7F5))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("No stored numbers yet.")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(records) { record ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7F5))
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(record.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Saved: ${record.scannedAt.toDisplayDate()}")
                                Text("Status: ${if (record.redeemedAt == null) "Pending" else "Used"}")
                                Text("Expires: ${record.expiresAt.toDisplayDate()}")
                                if (record.redeemedAt != null) {
                                    Text("Used: ${record.redeemedAt.toDisplayDate()}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AirtimeScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

private fun Long.toDisplayDate(): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return format.format(Date(this))
}
