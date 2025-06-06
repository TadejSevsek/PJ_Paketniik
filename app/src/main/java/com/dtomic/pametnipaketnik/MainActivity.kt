package com.dtomic.pametnipaketnik

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dtomic.pametnipaketnik.ui.theme.PametniPaketnikTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PametniPaketnikTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QRCodeScannerScreen()
                }
            }
        }
    }
}

@Composable
fun QRCodeScannerScreen() {
    val context = LocalContext.current
    var scannedBoxId by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(true) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showScanner = true
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            scannedBoxId = result.contents
            showScanner = false
        }
    }

    LaunchedEffect(hasCameraPermission, showScanner) {
        if (showScanner) {
            if (!hasCameraPermission) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan a QR code")
                    setCameraId(0)
                    setBeepEnabled(false)
                    setBarcodeImageEnabled(true)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(options)
            }
        }
    }

    if (showScanner) {
        Box(modifier = Modifier.fillMaxSize())
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            scannedBoxId?.let { boxId ->
                Text(
                    text = "Scanned Box ID: $boxId",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                TestWithBoxId(boxId = boxId)

                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .fillMaxWidth(0.8f)
                ) {
                    Text("Scan Another QR Code")
                }
            }
        }
    }
}

@Composable
fun TestWithBoxId(boxId: String) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Processing...") }

    LaunchedEffect(boxId) {
        withContext(Dispatchers.IO) {
            try {
                status = "Connecting to server..."
                val client = OkHttpClient()

                val mediaType = "application/json".toMediaType()
                val requestBody = """
                    {
                      "deliveryId": 12345,
                      "boxId": $boxId,
                      "tokenFormat": 4,
                      "latitude": 46.056946,
                      "longitude": 14.505751,
                      "qrCodeInfo": null,
                      "terminalSeed": 111222,
                      "isMultibox": false,
                      "doorIndex": 0,
                      "addAccessLog": true
                    }
                """.trimIndent().toRequestBody(mediaType)

                status = "Sending request..."
                val request = Request.Builder()
                    .url("https://api-d4me-stage.direct4.me/sandbox/v1/Access/openbox")
                    .addHeader("Authorization", "Bearer 9ea96945-3a37-4638-a5d4-22e89fbc998f")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful) {
                    status = "Processing response..."
                    val gson = com.google.gson.Gson()
                    val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)

                    val base64Data = apiResponse.data

                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

                    val success = saveBase64ToFile(context, "test.zip", decodedBytes)

                    if (success) {
                        Log.i("FileSave", "Saved to: ${context.filesDir.absolutePath}/test.zip")
                        val extractDir = File(context.cacheDir, "extracted_audio")
                        extractDir.mkdirs()

                        val extractedFiles = extractZip(context, "test.zip", extractDir)

                        Log.i("ExtractedFiles", extractedFiles.joinToString())

                        if (extractedFiles.contains("token.wav")) {
                            status = "Playing audio..."
                            playAudio(context, File(extractDir, "token.wav").absolutePath)
                            status = "Success! Audio played"
                        } else {
                            status = "Error: No audio file found"
                            Log.e("ZIP", "No audio file found in ZIP archive")
                        }
                    } else {
                        status = "Error: Failed to save file"
                        Log.e("FileSave", "Failed to save file")
                    }
                } else {
                    status = "Error: ${response.code} - ${response.message}"
                }
            } catch (e: Exception) {
                status = "Error: ${e.localizedMessage}"
                Log.e("NetworkError", "Failed to make request", e)
            }
        }
    }

    Text(
        text = status,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

data class ApiResponse(
    val data: String,
    val result: Int,
    val errorNumber: Number
)

fun saveBase64ToFile(context: android.content.Context, fileName: String, decodedBytes: ByteArray): Boolean {
    return try {
        val fos: FileOutputStream = context.openFileOutput(fileName, android.content.Context.MODE_PRIVATE)
        fos.write(decodedBytes)
        fos.close()
        true
    } catch (e: IOException) {
        e.printStackTrace()
        false
    }
}

fun extractZip(context: android.content.Context, zipFileName: String, destinationDir: File): List<String> {
    val extractedFiles = mutableListOf<String>()

    val zipFile = File(context.filesDir, zipFileName)
    val zis = ZipInputStream(zipFile.inputStream())

    var entry = zis.nextEntry
    while (entry != null) {
        val entryName = entry.name
        val outputFile = File(destinationDir, entryName)

        if (entry.isDirectory) {
            outputFile.mkdirs()
        } else {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                zis.copyTo(fos)
            }
        }

        extractedFiles.add(entryName)
        entry = zis.nextEntry
    }

    zis.closeEntry()
    zis.close()

    return extractedFiles
}

fun playAudio(context: android.content.Context, filePath: String) {
    val mediaPlayer = android.media.MediaPlayer().apply {
        try {
            setDataSource(filePath)
            prepare()
            start()
            Log.i("Playback", "Playing audio from: $filePath")
        } catch (e: IOException) {
            Log.e("Playback", "Error preparing or starting playback", e)
        } finally {
            setOnCompletionListener { release() }
        }
    }
}
