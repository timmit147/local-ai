package com.example.pocketai

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                "Welcome to Pocket AI. Select a GGUF model file to get started.",
                false
            )
        )
    }

    var input by remember { mutableStateOf("") }
    var modelPath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val destination = withContext(Dispatchers.IO) {
                        val directory = File(
                            context.getExternalFilesDir("models"),
                            ""
                        )
                        directory.mkdirs()

                        val file = File(directory, "model.gguf")

                        context.contentResolver.openInputStream(uri).use { stream ->
                            requireNotNull(stream) {
                                "Could not open the selected file."
                            }.copyTo(file.outputStream())
                        }

                        file.absolutePath
                    }

                    modelPath = destination
                    messages.add(
                        ChatMessage(
                            "Model file selected successfully. Send a message to start local inference.",
                            false
                        )
                    )
                } catch (e: Exception) {
                    messages.add(
                        ChatMessage(
                            "Could not copy the model: ${e.message}",
                            false
                        )
                    )
                } finally {
                    busy = false
                }
            }
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
                    .padding(horizontal = 16.dp)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "Pocket AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "Private AI running on your phone",
                    color = Color(0xFFB7BAC4),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Button(
                    enabled = !busy,
                    onClick = {
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (modelPath == null) {
                            "Choose GGUF model file"
                        } else {
                            "Choose a different model"
                        }
                    )
                }

                Text(
                    text = when {
                        busy -> "Working…"
                        modelPath != null -> "Model file ready"
                        else -> "Select a downloaded .gguf model first"
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
                            modelPath != null && !busy,
                        onClick = {
                            val prompt = input.trim()
                            val path = modelPath ?: return@Button

                            messages.add(ChatMessage(prompt, true))
                            input = ""
                            keyboard?.hide()
                            focusManager.clearFocus()

                            scope.launch {
                                busy = true
                                try {
                                    messages.add(
                                        ChatMessage("Generating locally…", false)
                                    )

                                    val answer = withContext(Dispatchers.IO) {
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
                                                systemPrompt =
                                                    "You are Pocket AI, a helpful assistant. Answer clearly and concisely.",
                                                maxTokens = 256
                                            ).text
                                        } finally {
                                            Llama.releaseModel(model)
                                        }
                                    }

                                    messages.removeAt(messages.lastIndex)
                                    messages.add(ChatMessage(answer, false))
                                } catch (e: Exception) {
                                    messages.removeAt(messages.lastIndex)
                                    messages.add(
                                        ChatMessage(
                                            "AI error: ${e.message ?: "Unknown error"}",
                                            false
                                        )
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
