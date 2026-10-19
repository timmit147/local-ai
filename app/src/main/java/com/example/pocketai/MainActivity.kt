package com.example.pocketai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val DOWNLOAD_WORK_NAME = "pocket-ai-model-download"
private const val AI_QUEUE_NAME = "pocket-ai-ai-queue"

private data class ChatMessage(
    val text: String,
    val fromUser: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        setContent {
            PocketAiApp()
        }
    }
}

@Composable
private fun PocketAiApp() {
    val context = LocalContext.current
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

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

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

    // Observe persisted work so the UI can recover after reopening the app.
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

    val modelReady = modelFile.exists() &&
        modelFile.length() > 100_000_000L

    val downloadState = downloadInfo?.state
    val downloading = downloadState == WorkInfo.State.RUNNING
    val downloadQueued =
        downloadState == WorkInfo.State.ENQUEUED ||
        downloadState == WorkInfo.State.BLOCKED

    val downloadProgress =
        downloadInfo?.progress?.getInt("progress", 0) ?: 0

    val messages = buildList {
        add(
            ChatMessage(
                "Welcome to Pocket AI. Download the model once, " +
                    "then send prompts. Background tasks can continue " +
                    "when you leave the screen.",
                false
            )
        )

        taskInfos.forEach { info ->
            val prompt = info.inputData.getString(
                AiTaskWorker.KEY_PROMPT
            ) ?: return@forEach

            add(ChatMessage(prompt, true))

            val answer = when (info.state) {
                WorkInfo.State.SUCCEEDED ->
                    info.outputData.getString(AiTaskWorker.KEY_ANSWER)
                        ?: "Task completed, but no answer was returned."

                WorkInfo.State.FAILED ->
                    "Task failed: " +
                        (info.outputData.getString(AiTaskWorker.KEY_ERROR)
                            ?: "Unknown error")

                WorkInfo.State.CANCELLED -> "Task cancelled."

                WorkInfo.State.RUNNING -> "Working in the background…"

                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> "Queued for background processing…"
            }

            add(ChatMessage(answer, false))
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB8C7FF),
            surface = Color(0xFF1B1D22)
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            color = Color(0xFF101114)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Pocket AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "Local AI · Background task queue",
                    color = Color(0xFFB7BAC4),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Button(
                    enabled = !modelReady &&
                        !downloading &&
                        !downloadQueued,
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
                            modelReady -> "AI model ready"
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
                }

                Text(
                    text = when {
                        modelReady -> "Model ready · Inference runs on-device"
                        downloading ->
                            "Downloading in background: $downloadProgress%"
                        downloadQueued -> "Waiting for an unmetered network (usually Wi-Fi)"
                        downloadState == WorkInfo.State.FAILED ->
                            "Download failed: " +
                                (downloadInfo?.outputData?.getString("error")
                                    ?: "Try again")
                        else -> "Download once; chat offline afterward"
                    },
                    color = Color(0xFFFFD27D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

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
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.88f)
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
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
                                "Write a task or question…",
                                color = Color(0xFF9296A2)
                            )
                        }

                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.take(4000) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp
                            ),
                            maxLines = 4
                        )
                    }

                    Button(
                        enabled = input.isNotBlank() && modelReady,
                        onClick = {
                            val prompt = input.trim()
                            if (prompt.isEmpty()) return@Button

                            val request =
                                OneTimeWorkRequestBuilder<AiTaskWorker>()
                                    .setInputData(
                                        workDataOf(
                                            AiTaskWorker.KEY_PROMPT to prompt,
                                            AiTaskWorker.KEY_MODEL_PATH to
                                                modelFile.absolutePath
                                        )
                                    )
                                    .addTag(AiTaskWorker.TAG)
                                    .build()

                            // Serial queue: avoid loading multiple large models
                            // into RAM at the same time.
                            workManager.beginUniqueWork(
                                AI_QUEUE_NAME,
                                ExistingWorkPolicy.APPEND_OR_REPLACE,
                                request
                            ).enqueue()

                            input = ""
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    ) {
                        Text("Queue")
                    }
                }
            }
        }
    }
}
