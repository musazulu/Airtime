package com.example.airtimescanner

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingImageUri: Uri? = null
    private val screenState = mutableStateOf(
        ScanUiState(
            status = "Take a photo of the scratch card, then confirm the detected PIN.",
            rawText = "",
            detectedPin = "",
            dialString = ""
        )
    )

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingImageUri
            if (success && uri != null) {
                processImage(uri)
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                processImage(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AirtimeScannerTheme {
                ScannerScreen(
                    uiState = screenState.value,
                    onCapture = { launchCamera() },
                    onPickImage = { pickImageLauncher.launch("image/*") },
                    onPinChanged = { updatedPin ->
                        screenState.value = screenState.value.copy(
                            detectedPin = updatedPin,
                            dialString = buildDialString(updatedPin)
                        )
                    },
                    onDial = { dialAirtime(screenState.value.detectedPin) }
                )
            }
        }
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
        runOnUiThread {
            screenState.value = screenState.value.copy(status = "Scanning image...")
        }

        val bitmap = loadProcessedBitmap(uri)
        if (bitmap == null) {
            runOnUiThread {
                screenState.value = screenState.value.copy(
                    status = "Could not read the image. Try a clearer photo."
                )
            }
            return
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val rawText = result.text
                val detectedPin = extractRechargeKey(result)
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        status = if (detectedPin.isBlank()) {
                            "No 17-digit recharge key found. Try a sharper photo of the middle strip."
                        } else {
                            "PIN detected. Review it before dialing."
                        },
                        rawText = rawText,
                        detectedPin = detectedPin,
                        dialString = buildDialString(detectedPin)
                    )
                }
            }
            .addOnFailureListener { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        status = "OCR failed: ${error.localizedMessage ?: "unknown error"}"
                    )
                }
            }
    }

    private fun loadProcessedBitmap(uri: Uri): Bitmap? {
        val rawBitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        val oriented = applyExifRotation(uri, rawBitmap)
        return cropMiddleStrip(oriented)
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
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropMiddleStrip(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val cropWidth = (width * 0.92f).toInt().coerceAtMost(width)
        val cropHeight = (height * 0.42f).toInt().coerceAtMost(height)
        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun extractRechargeKey(result: Text): String {
        val candidates = mutableListOf<ScoredKey>()

        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val digits = line.text.filter(Char::isDigit)
                if (digits.length == 17) {
                    candidates += ScoredKey(digits, scoreLine(line.text))
                }

                Regex("(?:\\d[\\s-]*){17}").findAll(line.text).forEach { match ->
                    val normalized = match.value.filter(Char::isDigit)
                    if (normalized.length == 17) {
                        candidates += ScoredKey(normalized, scoreLine(line.text) + 20)
                    }
                }
            }
        }

        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { it.score }?.value.orEmpty()
        }

        val fallback = Regex("(?:\\d[\\s-]*){17}").findAll(result.text)
            .map { it.value.filter(Char::isDigit) }
            .firstOrNull { it.length == 17 }

        return fallback.orEmpty()
    }

    private fun scoreLine(text: String): Int {
        val lower = text.lowercase(Locale.US)
        var score = 0

        if (Regex("(?:\\d[\\s-]*){17}").containsMatchIn(text)) {
            score += 50
        }
        if (lower.contains("recharge") || lower.contains("key") || lower.contains("airtime")) {
            score += 35
        }
        if (Regex("\\b(?:\\d{4,5}[\\s-]){3}\\d{4,5}\\b").containsMatchIn(text)) {
            score += 25
        }
        if (lower.contains("serial")) {
            score -= 40
        }
        if (lower.contains("batch")) {
            score -= 40
        }
        if (lower.contains("barcode")) {
            score -= 20
        }
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

data class ScanUiState(
    val status: String,
    val rawText: String,
    val detectedPin: String,
    val dialString: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerScreen(
    uiState: ScanUiState,
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onPinChanged: (String) -> Unit,
    onDial: () -> Unit
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
                        text = "Scan a scratch card, detect the PIN, then open the dialer.",
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
                        Text(
                            text = "Detected PIN",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedTextField(
                        value = uiState.detectedPin,
                        onValueChange = onPinChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Airtime PIN") },
                        placeholder = { Text("Enter or edit the detected PIN") }
                    )

                    Text(
                        text = "Dial string: ${uiState.dialString.ifBlank { "waiting for scan..." }}",
                        color = Color(0xFFD5E8D4)
                    )

                    Button(
                        onClick = onDial,
                        enabled = uiState.detectedPin.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Open Dialer")
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
                    Text(
                        text = "Capture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Take a clear photo. Keep the scratch digits centered and well lit.")
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

@Composable
private fun AirtimeScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
