package com.example.airtimescanner

import android.content.Intent
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
import com.google.mlkit.vision.common.InputImage
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
        val image = InputImage.fromFilePath(this, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val rawText = result.text
                val detectedPin = extractPin(rawText)
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        status = if (detectedPin.isBlank()) {
                            "No valid airtime PIN found. Try again with better lighting."
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

    private fun extractPin(text: String): String {
        val lineCandidates = text
            .lineSequence()
            .map { line -> line.filter(Char::isDigit) }
            .filter { it.length in 10..16 }
            .toList()

        if (lineCandidates.isNotEmpty()) {
            return lineCandidates.maxByOrNull { it.length }.orEmpty()
        }

        val compact = text.filter(Char::isDigit)
        if (compact.length in 10..16) {
            return compact
        }

        return Regex("\\d{10,16}").find(text)
            ?.value
            .orEmpty()
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
