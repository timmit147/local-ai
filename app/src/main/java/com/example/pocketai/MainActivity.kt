
package com.example.pocketai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private data class ChatAttachment(
    val path: String,
    val name: String,
    val isImage: Boolean
)

private data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val attachmentPath: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        setContent {
            PocketAiApp()
        }
    }
}

private fun loadChat(context: Context): List<ChatMessage> {
    return try {
        val prefs = context.getSharedPreferences(
            "pocket_ai_chat",
            Context.MODE_PRIVATE
        )
        val array = JSONArray(prefs.getString("messages", "[]"))
        val result = mutableListOf<ChatMessage>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val path = item.optString("attachmentPath")
                .takeIf { it.isNotBlank() && it != "null" }

            result.add(
                ChatMessage(
                    text = item.optString("text"),
                    fromUser = item.optBoolean("fromUser"),
                    attachmentPath = path,
                    attachmentName = item.optString("attachmentName")
                        .takeIf { it.isNotBlank() && it != "null" },
                    attachmentIsImage = item.optBoolean("attachmentIsImage")
                )
            )
        }

        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveChat(
    context: Context,
    messages: List<ChatMessage>
) {
    try {
        val array = JSONArray()

        messages.forEach { message ->
            val item = JSONObject()
            item.put("text", message.text)
            item.put("fromUser", message.fromUser)
            item.put("attachmentPath", message.attachmentPath ?: "")
            item.put("attachmentName", message.attachmentName ?: "")
            item.put("attachmentIsImage", message.attachmentIsImage)
            array.put(item)
        }

        context.getSharedPreferences(
            "pocket_ai_chat",
            Context.MODE_PRIVATE
        ).edit().putString("messages", array.toString()).apply()
    } catch (_: Exception) {
        // Keep the chat usable if saving fails.
    }
}

private fun copyAttachment(
    context: Context,
    uri: Uri,
    isImage: Boolean
): ChatAttachment? {
    return try {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        OpenableColumns.DISPLAY_NAME
                    )
                )
            } else null
        } ?: if (isImage) "image.jpg" else "attachment.txt"

        val directory = File(context.filesDir, "attachments")
        if (!directory.exists()) directory.mkdirs()

        val safeName = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100)
            .ifBlank { if (isImage) "image.jpg" else "attachment.txt" }

        val destination = File(
            directory,
            "${UUID.randomUUID()}_$safeName"
        )

        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        ChatAttachment(
            path = destination.absolutePath,
            name = displayName,
            isImage = isImage
        )
    } catch (_: Exception) {
        null
    }
}

private fun readTextAttachment(attachment: ChatAttachment): String? {
    if (attachment.isImage) return null

    val extension = attachment.name.substringAfterLast(
        '.',
        ""
    ).lowercase()

    val supportedExtensions = setOf(
        "txt", "md", "csv", "json", "xml", "html",
        "log", "kt", "java", "py", "js", "css",
        "yaml", "yml", "ini", "conf"
    )

    if (extension !in supportedExtensions) return null

    return try {
        File(attachment.path).inputStream().bufferedReader()
            .use { it.readText().take(12000) }
    } catch (_: Exception) {
        null
    }
}

private fun loadPreviewBitmap(path: String): Bitmap? {
    return try {
        val file = File(path)
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)

        var sample = 1
        while (
            bounds.outWidth / sample > 1000 ||
            bounds.outHeight / sample > 1000
        ) {
            sample *= 2
        }

        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (_: Exception) {
        null
    }
}

private fun openMap(context: Context, coordinates: String) {
    try {
        val uri = Uri.parse(
            "https://www.google.com/maps/search/?api=1&query=$coordinates"
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        // Do not crash the chat if no browser is available.
    }
}

private fun messageWithMapLinks(text: String): AnnotatedString {
    val pattern = Regex(
        """\(?(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\)?"""
    )

    return buildAnnotatedString {
        append(text)

        pattern.findAll(text).forEach { match ->
            val latitude = match.groupValues[1].toDoubleOrNull()
            val longitude = match.groupValues[2].toDoubleOrNull()

            if (
                latitude != null &&
                longitude != null &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0
            ) {
                addStyle(
                    SpanStyle(
                        color = Color(0xFF9AB8FF),
                        textDecoration = TextDecoration.Underline
                    ),
                    match.range.first,
                    match.range.last + 1
                )
                addStringAnnotation(
                    tag = "MAP",
                    annotation = "$latitude,$longitude",
                    start = match.range.first,
                    end = match.range.last + 1
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    attachment: ChatAttachment,
    onImageClick: (String) -> Unit
) {
    if (attachment.isImage) {
        val bitmap = remember(attachment.path) {
            loadPreviewBitmap(attachment.path)
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clickable { onImageClick(attachment.path) }
            )
        } else {
            Text(
                "Image unavailable: ${attachment.name}",
                color = Color.LightGray
            )
        }
    } else {
        Text(
            text = "📎 ${attachment.name}",
            color = Color(0xFFB8C7FF)
        )
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
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var pendingAttachment by remember {
        mutableStateOf<ChatAttachment?>(null)
    }
    var largeImagePath by remember { mutableStateOf<String?>(null) }

    val messages: SnapshotStateList<ChatMessage> = remember(context) {
        androidx.compose.runtime.mutableStateListOf<ChatMessage>().apply {
            addAll(loadChat(context))
            if (isEmpty()) {
                add(
                    ChatMessage(
                        "Welcome to Pocket AI! Download the model to start chatting offline.",
                        false
                    )
                )
            }
        }
    }

    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    copyAttachment(context, uri, true)
                }
                if (attachment != null) {
                    pendingAttachment = attachment
                } else {
                    messages.add(
                        ChatMessage("Could not open that image.", false)
                    )
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    copyAttachment(context, uri, false)
                }
                if (attachment != null) {
                    pendingAttachment = attachment
                } else {
                    messages.add(
                        ChatMessage("Could not open that file.", false)
                    )
                }
            }
        }
    }

    LaunchedEffect(messages.toList()) {
        saveChat(context, messages.toList())
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Pocket AI",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            downloading -> "Downloading model: $downloadProgress%"
                            busy -> "AI is working…"
                            modelReady -> "On-device model ready"
                            else -> "Download the model to chat offline"
                        },
                        color = Color(0xFFB7BAC4),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
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
                                        workManager,
                                        request.id
                                    ) { info ->
                                        if (info != null) {
                                            downloadProgress =
                                                info.progress.getInt(
                                                    "progress",
                                                    downloadProgress
                                                )
                                        }
                                    }

                                    val result =
                                        getWorkInfo(workManager, request.id)

                                    if (
                                        result?.state == WorkInfo.State.SUCCEEDED &&
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
                                        messages.add(
                                            ChatMessage(
                                                "Download failed: " +
                                                    (
                                                        result?.outputData
                                                            ?.getString("error")
                                                            ?: "Please try again."
                                                    ),
                                                false
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    messages.add(
                                        ChatMessage(
                                            "Could not start download: ${e.message}",
                                            false
                                        )
                                    )
                                } finally {
                                    downloading = false
                                }
                            }
                        }
                    ) {
                        Text(if (modelReady) "Ready" else "Download model")
                    }
                }

                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.90f)
                                    .background(
                                        if (message.fromUser) {
                                            Color(0xFF35466D)
                                        } else {
                                            Color(0xFF252831)
                                        },
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (message.text.isNotBlank()) {
                                    val annotated = remember(message.text) {
                                        messageWithMapLinks(message.text)
                                    }

                                    SelectionContainer {
                                        ClickableText(
                                            text = annotated,
                                            style = TextStyle(
                                                color = Color(0xFFF0F0F4),
                                                fontSize = 15.sp
                                            ),
                                            onClick = { offset ->
                                                annotated.getStringAnnotations(
                                                    "MAP",
                                                    offset,
                                                    offset
                                                ).firstOrNull()?.let {
                                                    openMap(context, it.item)
                                                }
                                            }
                                        )
                                    }
                                }

                                if (
                                    message.attachmentPath != null &&
                                    message.attachmentName != null
                                ) {
                                    AttachmentPreview(
                                        attachment = ChatAttachment(
                                            message.attachmentPath,
                                            message.attachmentName,
                                            message.attachmentIsImage
                                        ),
                                        onImageClick = {
                                            largeImagePath = it
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                pendingAttachment?.let { attachment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp)
                            .background(
                                Color(0xFF252831),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (attachment.isImage) {
                                "▧ ${attachment.name}"
                            } else {
                                "📎 ${attachment.name}"
                            },
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )

                        TextButton(
                            onClick = { pendingAttachment = null }
                        ) {
                            Text("Remove")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box {
                        IconButton(
                            onClick = { showAttachmentMenu = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text(
                                "＋",
                                fontSize = 28.sp,
                                color = Color(0xFFB8C7FF)
                            )
                        }

                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = {
                                showAttachmentMenu = false
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add image") },
                                onClick = {
                                    showAttachmentMenu = false
                                    imagePicker.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add file") },
                                onClick = {
                                    showAttachmentMenu = false
                                    filePicker.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .background(
                                Color(0xFF252831),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart
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

                    IconButton(
                        enabled = (
                            input.isNotBlank() ||
                                pendingAttachment != null
                            ) && modelReady && !busy && !downloading,
                        onClick = {
                            val userText = input.trim()
                            val attachment = pendingAttachment

                            if (
                                userText.isBlank() &&
                                attachment == null
                            ) return@IconButton

                            input = ""
                            pendingAttachment = null
                            keyboard?.hide()
                            focusManager.clearFocus()

                            messages.add(
                                ChatMessage(
                                    text = userText,
                                    fromUser = true,
                                    attachmentPath = attachment?.path,
                                    attachmentName = attachment?.name,
                                    attachmentIsImage = attachment?.isImage == true
                                )
                            )

                            if (attachment?.isImage == true) {
                                messages.add(
                                    ChatMessage(
                                        "Your image is attached and saved in this chat. Image understanding is not enabled yet; the current model is text-only. We'll add a compatible vision model next.",
                                        false
                                    )
                                )
                                return@IconButton
                            }

                            val fileText = attachment?.let {
                                readTextAttachment(it)
                            }

                            val prompt = buildString {
                                append(userText.ifBlank {
                                    "Please help me with the attached file."
                                })

                                if (attachment != null) {
                                    if (fileText != null) {
                                        append(
                                            "\n\nAttached file: ${attachment.name}\n"
                                        )
                                        append(fileText)
                                    } else {
                                        append(
                                            "\n\nA file named ${attachment.name} is attached, " +
                                                "but its contents could not be extracted as text."
                                        )
                                    }
                                }
                            }

                            if (!modelFile.exists()) {
                                modelReady = false
                                messages.add(
                                    ChatMessage(
                                        "The model file is missing. Download it again before chatting.",
                                        false
                                    )
                                )
                                return@IconButton
                            }

                            messages.add(
                                ChatMessage(
                                    "Working…",
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
                                        workManager,
                                        request.id
                                    ) { info ->
                                        if (info == null) return@monitorWork

                                        when (info.state) {
                                            WorkInfo.State.ENQUEUED,
                                            WorkInfo.State.BLOCKED -> {
                                                messages[answerIndex] =
                                                    ChatMessage(
                                                        "Waiting for the AI task…",
                                                        false
                                                    )
                                            }

                                            WorkInfo.State.RUNNING -> {
                                                messages[answerIndex] =
                                                    ChatMessage(
                                                        "AI is working…",
                                                        false
                                                    )
                                            }

                                            else -> Unit
                                        }
                                    }

                                    val result =
                                        getWorkInfo(workManager, request.id)

                                    messages[answerIndex] =
                                        when (result?.state) {
                                            WorkInfo.State.SUCCEEDED ->
                                                ChatMessage(
                                                    result.outputData.getString(
                                                        AiTaskWorker.KEY_ANSWER
                                                    ) ?: "No answer was returned.",
                                                    false
                                                )

                                            WorkInfo.State.FAILED ->
                                                ChatMessage(
                                                    "AI task failed: " +
                                                        (
                                                            result.outputData.getString(
                                                                AiTaskWorker.KEY_ERROR
                                                            ) ?: "Unknown error"
                                                        ),
                                                    false
                                                )

                                            WorkInfo.State.CANCELLED ->
                                                ChatMessage(
                                                    "AI task was cancelled.",
                                                    false
                                                )

                                            else ->
                                                ChatMessage(
                                                    "The task did not finish. Please try again.",
                                                    false
                                                )
                                        }
                                } catch (e: Exception) {
                                    messages[answerIndex] = ChatMessage(
                                        "Could not run AI task: ${e.message ?: "Unknown error"}",
                                        false
                                    )
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text(
                            "↑",
                            fontSize = 24.sp,
                            color = if (busy || downloading) {
                                Color.Gray
                            } else {
                                Color(0xFFB8C7FF)
                            }
                        )
                    }
                }
            }
        }
    }

    largeImagePath?.let { path ->
        Dialog(
            onDismissRequest = { largeImagePath = null }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .background(
                        Color(0xFF101114),
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { largeImagePath = null },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(path) {
                    loadPreviewBitmap(path)
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Full image preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                } else {
                    Text(
                        "Image unavailable",
                        color = Color.White
                    )
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
