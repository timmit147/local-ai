package com.example.pocketai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_WORK_NAME = "pocket-ai-model-download"
private const val AI_QUEUE_NAME = "pocket-ai-ai-queue"
private const val APP_BACKGROUND = 0xFF101114

private data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val isLoading: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        window.navigationBarColor = AndroidColor.rgb(16, 17, 20)
        window.statusBarColor = AndroidColor.rgb(16, 17, 20)

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).isAppearanceLightNavigationBars = false

        setContent {
            PocketAiApp()
        }
    }
}

@Composable
private fun PocketAiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workManager = remember(context) {
        WorkManager.getInstance(context)
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val modelFile = remember(context) {
        ModelDownloadWorker.modelFile(context)
    }

    var input by remember { mutableStateOf("") }
    var downloadInfo by remember { mutableStateOf<WorkInfo?>(null) }
    var taskInfos by remember { mutableStateOf<List<WorkInfo>>(emptyList()) }

    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentContext by remember { mutableStateOf<String?>(null) }
    var processingAttachment by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var locationStatus by remember { mutableStateOf("Location not shared") }

    val modelReady = modelFile.exists() &&
        modelFile.length() > 100_000_000L

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allowed =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (allowed) {
                scope.launch {
                    locationStatus = "Getting location…"
                    try {
                        currentLocation = getCurrentLocation(context)
                        locationStatus = if (currentLocation != null) {
                            "Location available"
                        } else {
                            "Couldn't get location. Check GPS and try again."
                        }
                    } catch (_: Exception) {
                        locationStatus = "Couldn't get location. Try again."
                    }
                }
            } else {
                locationStatus = "Location permission not granted"
            }
        }

    val imagePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    processingAttachment = true
                    attachmentError = null
                    attachmentName = getDisplayName(context, uri)
                    attachmentContext = null

                    try {
                        attachmentContext = describeImage(context, uri)
                    } catch (e: Exception) {
                        attachmentError =
                            "Couldn't read image: ${e.message ?: "Unknown error"}"
                        attachmentName = null
                    } finally {
                        processingAttachment = false
                    }
                }
            }
        }

    val filePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    processingAttachment = true
                    attachmentError = null
                    attachmentName = getDisplayName(context, uri)
                    attachmentContext = null

                    try {
                        attachmentContext = readDocument(context, uri)
                    } catch (e: Exception) {
                        attachmentError =
                            "Couldn't read file: ${e.message ?: "Unsupported file"}"
                        attachmentName = null
                    } finally {
                        processingAttachment = false
                    }
                }
            }
        }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    // Watch persistent work so the UI recovers when the app is reopened.
    LaunchedEffect(workManager) {
        while (isActive) {
            val results = withContext(Dispatchers.IO) {
                val download = workManager
                    .getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME)
                    .get()
                    .firstOrNull()

                val tasks = workManager
                    .getWorkInfosByTag(AiTaskWorker.TAG)
                    .get()

                download to tasks
            }

            downloadInfo = results.first
            taskInfos = results.second.sortedBy { it.id.toString() }
            delay(1000)
        }
    }

    val downloadState = downloadInfo?.state
    val downloading = downloadState == WorkInfo.State.RUNNING
    val downloadQueued =
        downloadState == WorkInfo.State.ENQUEUED ||
        downloadState == WorkInfo.State.BLOCKED

    val downloadProgress =
        downloadInfo?.progress?.getInt("progress", 0) ?: 0

    val messages = buildList {
        taskInfos.forEach { info ->
            val prompt = info.progress.getString(
                AiTaskWorker.KEY_DISPLAY_PROMPT
            ) ?: info.outputData.getString(
                AiTaskWorker.KEY_DISPLAY_PROMPT
            ) ?: return@forEach

            add(ChatMessage(prompt, true))

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    add(
                        ChatMessage(
                            info.outputData.getString(AiTaskWorker.KEY_ANSWER)
                                ?: "Task finished, but no answer was returned.",
                            false
                        )
                    )
                }

                WorkInfo.State.FAILED -> {
                    add(
                        ChatMessage(
                            "Task failed: " +
                                (info.outputData.getString(AiTaskWorker.KEY_ERROR)
                                    ?: "Unknown error"),
                            false
                        )
                    )
                }

                WorkInfo.State.CANCELLED -> {
                    add(ChatMessage("Task cancelled.", false))
                }

                WorkInfo.State.RUNNING -> {
                    add(ChatMessage("", false, isLoading = true))
                }

                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> {
                    add(ChatMessage("Queued…", false))
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB8C7FF),
            surface = Color(0xFF1B1D22),
            background = Color(APP_BACKGROUND)
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = Color(APP_BACKGROUND)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // No title or subtitle.

                if (!modelReady) {
                    Button(
                        enabled = !downloading && !downloadQueued,
                        onClick = {
                            val request =
                                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                                    .setConstraints(
                                        Constraints.Builder()
                                            .setRequiredNetworkType(
                                                NetworkType.UNMETERED
                                            )
                                            .build()
                                    )
                                    .addTag(ModelDownloadWorker.TAG)
                                    .build()

                            workManager.enqueueUniqueWork(
                                DOWNLOAD_WORK_NAME,
                                ExistingWorkPolicy.KEEP,
                                request
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                downloading -> "Downloading model…"
                                downloadQueued -> "Download queued — Wi-Fi required"
                                else -> "Download AI model (~2 GB)"
                            }
                        )
                    }

                    if (downloading || downloadQueued) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )

                        Text(
                            text = if (downloading) {
                                "Downloading: $downloadProgress%"
                            } else {
                                "Waiting for Wi-Fi"
                            },
                            color = Color(0xFFFFD27D),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else if (downloadState == WorkInfo.State.FAILED) {
                        Text(
                            "Download failed: " +
                                (downloadInfo?.outputData?.getString("error")
                                    ?: "Try again"),
                            color = Color(0xFFFF9A9A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (message.fromUser) {
                                Arrangement.End
                            } else {
                                Arrangement.Start
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .background(
                                        if (message.fromUser) {
                                            Color(0xFF35466D)
                                        } else {
                                            Color(0xFF252831)
                                        },
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                if (message.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFB8C7FF)
                                    )
                                } else {
                                    SelectionContainer {
                                        Text(
                                            message.text,
                                            color = Color(0xFFF0F0F4),
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (processingAttachment) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            "Reading attachment…",
                            color = Color(0xFFB7BAC4),
                            fontSize = 12.sp
                        )
                    }
                }

                if (attachmentName != null && !processingAttachment) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Attached: $attachmentName",
                            color = Color(0xFFB8C7FF),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                attachmentName = null
                                attachmentContext = null
                                attachmentError = null
                            }
                        ) {
                            Text("Remove")
                        }
                    }
                }

                attachmentError?.let {
                    Text(
                        it,
                        color = Color(0xFFFF9A9A),
                        fontSize = 12.sp
                    )
                }

                Text(
                    locationStatus,
                    color = Color(0xFFB7BAC4),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            imagePicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !processingAttachment
                    ) {
                        Text("Image")
                    }

                    TextButton(
                        onClick = {
                            filePicker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "text/*",
                                    "application/json",
                                    "application/xml",
                                    "application/csv"
                                )
                            )
                        },
                        enabled = !processingAttachment
                    ) {
                        Text("File")
                    }

                    TextButton(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            val coarse = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (fine || coarse) {
                                scope.launch {
                                    locationStatus = "Getting location…"
                                    try {
                                        currentLocation = getCurrentLocation(context)
                                        locationStatus =
                                            if (currentLocation != null) {
                                                "Location available"
                                            } else {
                                                "Location unavailable. Check GPS."
                                            }
                                    } catch (_: Exception) {
                                        locationStatus = "Couldn't get location"
                                    }
                                }
                            } else {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Location")
                    }

                    TextButton(
                        onClick = {
                            openMaps(
                                context,
                                input.trim(),
                                currentLocation
                            )
                        }
                    ) {
                        Text("Maps")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                Color(0xFF252831),
                                RoundedCornerShape(18.dp)
                            )
                            .padding(14.dp)
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                "Ask anything…",
                                color = Color(0xFF9296A2)
                            )
                        }

                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.take(2000) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp
                            ),
                            maxLines = 4
                        )
                    }

                    Button(
                        enabled = input.isNotBlank() &&
                            modelReady &&
                            !processingAttachment,
                        onClick = {
                            val displayPrompt = input.trim()
                            val attachedText = attachmentContext
                            val location = currentLocation

                            val fullPrompt = buildString {
                                append(displayPrompt)

                                if (!attachedText.isNullOrBlank()) {
                                    append("\n\nAttachment information:\n")
                                    append(attachedText.take(3500))
                                }

                                if (location != null) {
                                    append(
                                        "\n\nCurrent device location (only use if relevant): "
                                    )
                                    append(
                                        String.format(
                                            Locale.US,
                                            "%.5f, %.5f",
                                            location.latitude,
                                            location.longitude
                                        )
                                    )
                                }
                            }.take(6500)

                            val request =
                                OneTimeWorkRequestBuilder<AiTaskWorker>()
                                    .setInputData(
                                        workDataOf(
                                            AiTaskWorker.KEY_PROMPT to fullPrompt,
                                            AiTaskWorker.KEY_DISPLAY_PROMPT to
                                                displayPrompt.take(2000),
                                            AiTaskWorker.KEY_MODEL_PATH to
                                                modelFile.absolutePath
                                        )
                                    )
                                    .addTag(AiTaskWorker.TAG)
                                    .build()

                            workManager.beginUniqueWork(
                                AI_QUEUE_NAME,
                                ExistingWorkPolicy.APPEND_OR_REPLACE,
                                request
                            ).enqueue()

                            input = ""
                            attachmentName = null
                            attachmentContext = null
                            attachmentError = null

                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun getCurrentLocation(
    context: Context
): Location? = withContext(Dispatchers.IO) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellation = com.google.android.gms.tasks.CancellationTokenSource()

    try {
        Tasks.await(
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellation.token
            ),
            15,
            TimeUnit.SECONDS
        )
    } finally {
        cancellation.cancel()
    }
}

private fun openMaps(
    context: Context,
    search: String,
    location: Location?
) {
    val lat = location?.latitude ?: 0.0
    val lon = location?.longitude ?: 0.0

    val query = if (search.isNotBlank()) {
        Uri.encode(search)
    } else if (location != null) {
        Uri.encode("$lat,$lon")
    } else {
        ""
    }

    val mapUri = if (query.isNotBlank()) {
        Uri.parse("geo:$lat,$lon?q=$query")
    } else {
        Uri.parse("geo:0,0")
    }

    val mapsIntent = Intent(Intent.ACTION_VIEW, mapUri)
        .setPackage("com.google.android.apps.maps")

    try {
        context.startActivity(mapsIntent)
    } catch (_: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, mapUri))
    }
}

private fun getDisplayName(
    context: Context,
    uri: Uri
): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) ?: "attachment"
            } else {
                "attachment"
            }
        } ?: "attachment"
    } catch (_: Exception) {
        "attachment"
    }
}

private suspend fun describeImage(
    context: Context,
    uri: Uri
): String = withContext(Dispatchers.IO) {
    val image = InputImage.fromFilePath(context, uri)

    val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.DEFAULT_OPTIONS
    )
    val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    try {
        val labels = Tasks.await(labeler.process(image))
            .take(10)
            .joinToString(", ") { it.text }

        val recognizedText = Tasks.await(recognizer.process(image))
            .text
            .take(2000)

        buildString {
            append("Image labels: ")
            append(if (labels.isBlank()) "No clear labels detected" else labels)

            if (recognizedText.isNotBlank()) {
                append("\nText visible in image:\n")
                append(recognizedText)
            }
        }.take(3500)
    } finally {
        labeler.close()
        recognizer.close()
    }
}

private suspend fun readDocument(
    context: Context,
    uri: Uri
): String = withContext(Dispatchers.IO) {
    val name = getDisplayName(context, uri)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    when (extension) {
        "pdf" -> {
            PDFBoxResourceLoader.init(context)

            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { document ->
                    PDFTextStripper().getText(document).take(3500)
                }
            } ?: error("Could not open PDF")
        }

        "txt", "md", "csv", "json", "xml", "html", "log" -> {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(1024)

                    while (output.length < 3500) {
                        val count = reader.read(buffer)
                        if (count < 0) break

                        val remaining = 3500 - output.length
                        output.append(buffer, 0, minOf(count, remaining))
                    }

                    output.toString()
                }
            } ?: error("Could not open file")
        }

        else -> error(
            "Supported files: PDF, TXT, MD, CSV, JSON, XML, HTML and LOG."
        )
    }.let { text ->
        "File name: $name\nFile content:\n$text"
    }
}
