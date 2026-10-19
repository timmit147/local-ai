package com.example.pocketai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_URL =
    "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf?download=true"

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

private suspend fun downloadModel(
    destination: File,
    onProgress: (Int) -> Unit
): File = withContext(Dispatchers.IO) {
    destination.parentFile?.mkdirs()

    val temporaryFile = File(destination.parentFile, "model.gguf.part")
    val connection = URL(MODEL_URL).openConnection() as HttpURLConnection

    try {
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.connect()

        if (connection.responseCode !in 200..299) {
            throw IOException("Download failed: HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        var lastProgress = -1

        connection.inputStream.use { input ->
            temporaryFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break

                    output.write(buffer, 0, count)
                    downloadedBytes += count

                    if (totalBytes > 0) {
                        val percent =
                            ((downloadedBytes * 100) / totalBytes)
                                .toInt().coerceIn(0, 100)

                        if (percent != lastProgress) {
                            lastProgress = percent
                            withContext(Dispatchers.Main) {
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }

        if (totalBytes > 0 && temporaryFile.length() != totalBytes) {
            throw IOException("Download incomplete. Please try again.")
        }

        if (temporaryFile.length() < 100_000_000L) {
            throw IOException("Downloaded file is too small to be a model.")
        }

        if (destination.exists()) destination.delete()

        if (!temporaryFile.renameTo(destination)) {
            temporaryFile.copyTo(destination, overwrite = true)
            temporaryFile.delete()
        }

        withContext(Dispatchers.Main) {
            onProgress(100)
        }

        destination
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun PocketAiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val modelFile = remember(context) {
        File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "models/model.gguf"
        )
    }

    var modelPath by remember {
        mutableStateOf(
            if (modelFile.exists() &&
                modelFile.length() > 100_000_000L
            ) modelFile.absolutePath else null
        )
    }

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                "Welcome to Pocket AI! Download the AI model to start chatting offline.",
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
                    "Pocket AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "Your private, on-device assistant",
                    color = Color(0xFFB7BAC4),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Button(
                    enabled = !downloading && !busy && modelPath == null,
                    onClick = {
                        scope.launch {
                            downloading = true
                            downloadProgress = 0

                            try {
                                val downloaded = downloadModel(modelFile) {
                                    downloadProgress = it
                                }

                                modelPath = downloaded.absolutePath
                                messages.add(
                                    ChatMessage(
                                        "AI model downloaded! You can now chat offline.",
                                        false
                                    )
                                )
                            } catch (e: Exception) {
                                messages.add(
                                    ChatMessage(
                                        "Download failed: ${e.message ?: "Unknown error"}. Check your internet connection and try again.",
                                        false
                                    )
                                )
                                modelFile.delete()
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            modelPath != null -> "AI model downloaded"
                            downloading -> "Downloading model…"
                            else -> "Download AI model (about 1.93 GB)"
                        }
                    )
                }

                if (downloading) {
                    LinearProgressIndicator(
                        progress = downloadProgress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }

                Text(
                    text = when {
                        downloading -> "Downloading: $downloadProgress%"
                        busy -> "AI is thinking…"
                        modelPath != null -> "Model ready · Works offline"
                        else -> "Download over Wi-Fi recommended"
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
                                "Write a message…",
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
                            modelPath != null && !busy && !downloading,
                        onClick = {
                            val prompt = input.trim()
                            val path = modelPath ?: return@Button

                            messages.add(ChatMessage(prompt, true))
                            messages.add(ChatMessage("Thinking…", false))
                            val thinkingIndex = messages.lastIndex

                            input = ""
                            keyboard?.hide()
                            focusManager.clearFocus()

                            scope.launch {
                                busy = true

                                try {
                                    val answer = withContext(Dispatchers.Default) {
                                        val model = Llama.loadModel(
                                            modelPath = path,
                                            config = LlamaConfig(
                                                contextSize = 2048,
                                                threads = 4
                                            )
                                        )

                                        try {
                                            Llama.complete(
                                                model,
                                                prompt = prompt,
                                                systemPrompt = "You are Pocket AI, a helpful assistant. Give clear and useful answers.",
                                                maxTokens = 256
                                            ).text
                                        } finally {
                                            Llama.releaseModel(model)
                                        }
                                    }

                                    messages[thinkingIndex] =
                                        ChatMessage(answer, false)
                                } catch (e: Exception) {
                                    messages[thinkingIndex] = ChatMessage(
                                        "AI error: ${e.message ?: "Could not generate a response."}",
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
