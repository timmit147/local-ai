package com.example.pocketai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val workManager = remember(context) {
        WorkManager.getInstance(context)
    }

    val modelFile = remember(context) {
        ModelDownloadWorker.modelFile(context)
    }

    var modelReady by remember {
        mutableStateOf(
            modelFile.exists() &&
                modelFile.length() > 100_000_000L
        )
    }

    var input by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                "Welcome to Pocket AI! Download the model to start chatting offline.",
                false
            )
        )
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
                    text = "Pocket AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "Your private, on-device assistant",
                    color = Color(0xFFB7BAC4),
                    modifier = Modifier.padding(
                        top = 4.dp,
                        bottom = 12.dp
                    )
                )

                Button(
                    enabled = !downloading && !busy && !modelReady,
                    onClick = {
                        scope.launch {
                            downloading = true
                            downloadProgress = 0

                            try {
                                val request =
                                    OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                                        .setConstraints(
                                            Constraints.Builder()
                                                .setRequiredNetworkType(
                                                    NetworkType.CONNECTED
                                                )
                                                .build()
                                        )
                                        .build()

                                workManager.enqueueUniqueWork(
                                    "pocket-ai-model-download",
                                    ExistingWorkPolicy.REPLACE,
                                    request
                                )

                                monitorWork(
                                    workManager = workManager,
                                    workId = request.id,
                                    onUpdate = { info ->
                                        if (info != null) {
                                            downloadProgress =
                                                info.progress.getInt(
                                                    "progress",
                                                    downloadProgress
                                                )
                                        }
                                    }
                                )

                                val finalInfo =
                                    getWorkInfo(workManager, request.id)

                                if (
                                    finalInfo?.state ==
                                    WorkInfo.State.SUCCEEDED &&
                                    modelFile.exists() &&
                                    modelFile.length() > 100_000_000L
                                ) {
                                    modelReady = true
                                    downloadProgress = 100
                                    messages.add(
                                        ChatMessage(
                                            "AI model downloaded. You can now chat offline.",
                                            false
                                        )
                                    )
                                } else {
                                    val error = finalInfo?.outputData
                                        ?.getString("error")
                                        ?: "Download did not finish. Check your connection and try again."

                                    messages.add(
                                        ChatMessage(
                                            "Model download: $error",
                                            false
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                messages.add(
                                    ChatMessage(
                                        "Could not start download: ${
                                            e.message ?: "Unknown error"
                                        }",
                                        false
                                    )
                                )
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            modelReady -> "AI model downloaded"
                            downloading -> "Downloading model…"
                            else -> "Download AI model (about 2 GB)"
                        }
                    )
                }

                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }

                Text(
                    text = when {
                        downloading ->
                            "Downloading in the background: $downloadProgress%"
                        busy -> "AI task running in the background…"
                        modelReady -> "Model ready · Works offline"
                        else -> "Internet required for the initial download"
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
                    itemsIndexed(messages) { _, message ->
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
                                        text = message.text,
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
                                text = "Write a message…",
                                color = Color(0xFF9296A2)
                            )
                        }

                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
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
                            !busy &&
                            !downloading,
                        onClick = {
                            val prompt = input.trim()
                            if (prompt.isEmpty()) return@Button

                            if (!modelFile.exists()) {
                                modelReady = false
                                messages.add(
                                    ChatMessage(
                                        "The model file is missing. Download it again before chatting.",
                                        false
                                    )
                                )
                                return@Button
                            }

                            input = ""
                            keyboard?.hide()
                            focusManager.clearFocus()

                            messages.add(ChatMessage(prompt, true))
                            messages.add(
                                ChatMessage(
                                    "Queued. This task can continue if you leave the app.",
                                    false
                                )
                            )

                            val answerIndex = messages.lastIndex
                            busy = true

                            scope.launch {
                                try {
                                    val request =
                                        OneTimeWorkRequestBuilder<AiTaskWorker>()
                                            .setInputData(
                                                workDataOf(
                                                    AiTaskWorker.KEY_PROMPT to prompt,
                                                    AiTaskWorker.KEY_MODEL_PATH to
                                                        modelFile.absolutePath
                                                )
                                            )
                                            .build()

                                    workManager.enqueueUniqueWork(
                                        "pocket-ai-ai-queue",
                                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                                        request
                                    )

                                    monitorWork(
                                        workManager = workManager,
                                        workId = request.id,
                                        onUpdate = { info ->
                                            if (info == null) return@monitorWork

                                            when (info.state) {
                                                WorkInfo.State.ENQUEUED,
                                                WorkInfo.State.BLOCKED -> {
                                                    messages[answerIndex] =
                                                        ChatMessage(
                                                            "Task queued. Waiting to run…",
                                                            false
                                                        )
                                                }

                                                WorkInfo.State.RUNNING -> {
                                                    messages[answerIndex] =
                                                        ChatMessage(
                                                            "AI is working. You can leave the app while it runs…",
                                                            false
                                                        )
                                                }

                                                else -> Unit
                                            }
                                        }
                                    )

                                    val result =
                                        getWorkInfo(workManager, request.id)

                                    messages[answerIndex] =
                                        when (result?.state) {
                                            WorkInfo.State.SUCCEEDED -> {
                                                ChatMessage(
                                                    result.outputData.getString(
                                                        AiTaskWorker.KEY_ANSWER
                                                    ) ?: "Task completed, but no answer was returned.",
                                                    false
                                                )
                                            }

                                            WorkInfo.State.FAILED -> {
                                                ChatMessage(
                                                    "AI task failed: " +
                                                        (
                                                            result.outputData.getString(
                                                                AiTaskWorker.KEY_ERROR
                                                            ) ?: "Unknown error"
                                                        ),
                                                    false
                                                )
                                            }

                                            WorkInfo.State.CANCELLED -> {
                                                ChatMessage(
                                                    "AI task was cancelled.",
                                                    false
                                                )
                                            }

                                            else -> {
                                                ChatMessage(
                                                    "The task has been queued or stopped by Android. Reopen the app to check its status.",
                                                    false
                                                )
                                            }
                                        }
                                } catch (e: Exception) {
                                    messages[answerIndex] =
                                        ChatMessage(
                                            "Could not run AI task: ${
                                                e.message ?: "Unknown error"
                                            }",
                                            false
                                        )
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

private suspend fun getWorkInfo(
    workManager: WorkManager,
    workId: UUID
): WorkInfo? {
    return withContext(Dispatchers.IO) {
        workManager.getWorkInfoById(workId).get()
    }
}

private suspend fun monitorWork(
    workManager: WorkManager,
    workId: UUID,
    onUpdate: (WorkInfo?) -> Unit
) {
    while (true) {
        val info = getWorkInfo(workManager, workId)
        onUpdate(info)

        if (
            info == null ||
            info.state == WorkInfo.State.SUCCEEDED ||
            info.state == WorkInfo.State.FAILED ||
            info.state == WorkInfo.State.CANCELLED
        ) {
            return
        }

        delay(1000)
    }
}
