package com.aprax.qweenide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter

data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101114)) {
                    ChatScreen()
                }
            }
        }
    }
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("QweenPrefs", Context.MODE_PRIVATE)

    // Settings State
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "https://gig-mounted-hepatitis-leading.trycloudflare.com/v1") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey", "TPk4esc84wyzHLkqCE6FWIf9EOx39SvVATIikC_aIRg") ?: "") }
    var modelId by remember { mutableStateOf(prefs.getString("modelId", "Qwen/Qwen2.5-Coder-7B-Instruct") ?: "") }
    var showSettings by remember { mutableStateOf(false) }

    // Folder Picker State
    var savedFolderUri by remember { mutableStateOf(prefs.getString("savedFolderUri", null)) }

    // Chat State
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    // Load local history
    LaunchedEffect(Unit) {
        messages = loadChatHistory(context)
    }

    // Native Folder Picker Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission so we don't have to ask every time
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            savedFolderUri = it.toString()
            prefs.edit().putString("savedFolderUri", savedFolderUri).apply()
            Toast.makeText(context, "Save folder connected!", Toast.LENGTH_SHORT).show()
        }
    }

    // --- UI Layout ---
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Qween IDE", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (savedFolderUri != null) "Folder Linked 📁" else "Local Storage Mode", fontSize = 12.sp, color = Color.Gray)
            }
            Row {
                // Folder Picker Button
                IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                    Icon(
                        imageVector = Icons.Default.Folder, 
                        contentDescription = "Select Folder", 
                        tint = if (savedFolderUri != null) Color(0xFF6378ed) else Color.LightGray
                    )
                }
                // Clear History Button
                IconButton(onClick = {
                    messages = emptyList()
                    saveChatHistory(context, messages)
                }) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", tint = Color.LightGray)
                }
                // Settings Button
                IconButton(onClick = { showSettings = true }) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Chat Area
        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg = msg) { code ->
                    if (savedFolderUri == null) {
                        Toast.makeText(context, "Please tap the Folder icon first to select a save location", Toast.LENGTH_LONG).show()
                    } else {
                        saveCodeToFolder(context, savedFolderUri, code)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Input Area
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Qwen...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6378ed), unfocusedBorderColor = Color.DarkGray)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank() && !isGenerating) {
                        val prompt = inputText
                        inputText = ""
                        
                        val userMsg = ChatMessage("user", prompt)
                        messages = messages + userMsg
                        saveChatHistory(context, messages)
                        
                        sendMessageToApi(
                            prompt = prompt,
                            url = baseUrl,
                            key = apiKey,
                            modelId = modelId,
                            context = context,
                            currentHistory = messages,
                            onLoadingChanged = { isGenerating = it },
                            onHistoryUpdated = { updatedHistory -> messages = updatedHistory }
                        )
                    }
                },
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6378ed))
            ) {
                Text(if (isGenerating) "..." else "Send")
            }
        }
    }

    // Settings Dialog
    if (showSettings) {
        SettingsDialog(
            currentUrl = baseUrl,
            currentKey = apiKey,
            currentModel = modelId,
            onDismiss = { showSettings = false },
            onSave = { newUrl, newKey, newModel ->
                baseUrl = newUrl
                apiKey = newKey
                modelId = newModel
                prefs.edit().putString("baseUrl", newUrl).putString("apiKey", newKey).putString("modelId", newModel).apply()
                showSettings = false
            }
        )
    }
}

// --- NATIVE FOLDER SAVE LOGIC ---
fun saveCodeToFolder(context: Context, folderUriString: String?, content: String) {
    if (folderUriString == null) return
    try {
        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        
        // Generate a filename
        val fileName = "code_${System.currentTimeMillis()}.txt"
        
        // Create the file in the selected folder
        val newFile = folder?.createFile("text/plain", fileName)
        if (newFile != null) {
            context.contentResolver.openOutputStream(newFile.uri)?.use { stream ->
                val writer = OutputStreamWriter(stream)
                writer.write(content)
                writer.close()
                Toast.makeText(context, "Saved: $fileName", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Failed to create file", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Save Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// --- LOCAL FILE BACKEND (Chat History) ---
fun getHistoryFile(context: Context): File {
    val dir = context.getExternalFilesDir(null)
    if (dir != null && !dir.exists()) dir.mkdirs()
    return File(dir, "chat_history.json")
}

fun loadChatHistory(context: Context): List<ChatMessage> {
    val file = getHistoryFile(context)
    if (!file.exists()) return emptyList()
    return try {
        val list = mutableListOf<ChatMessage>()
        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(ChatMessage(
                role = obj.getString("role"),
                content = obj.getString("content"),
                timestamp = obj.getLong("timestamp")
            ))
        }
        list.sortedBy { it.timestamp }
    } catch (e: Exception) { emptyList() }
}

fun saveChatHistory(context: Context, messages: List<ChatMessage>) {
    try {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            obj.put("timestamp", msg.timestamp)
            jsonArray.put(obj)
        }
        getHistoryFile(context).writeText(jsonArray.toString())
    } catch (e: Exception) { e.printStackTrace() }
}

// --- NETWORKING ---
fun sendMessageToApi(
    prompt: String, url: String, key: String, modelId: String, context: Context,
    currentHistory: List<ChatMessage>,
    onLoadingChanged: (Boolean) -> Unit,
    onHistoryUpdated: (List<ChatMessage>) -> Unit
) {
    onLoadingChanged(true)
    val client = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
    val cleanUrl = url.trimEnd('/')
    
    val apiMessagesArray = JSONArray()
    currentHistory.takeLast(10).forEach {
        apiMessagesArray.put(JSONObject().apply { put("role", it.role); put("content", it.content) })
    }

    val json = JSONObject().apply {
        put("model", modelId)
        put("stream", false)
        put("max_tokens", 2048)
        put("temperature", 0.7)
        put("messages", apiMessagesArray)
    }

    val request = Request.Builder().url("$cleanUrl/chat/completions").post(json.toString().toRequestBody("application/json".toMediaType())).header("Authorization", "Bearer $key").build()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            withContext(Dispatchers.Main) {
                if (response.isSuccessful && responseBody != null) {
                    val answer = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    val aiMsg = ChatMessage("assistant", answer)
                    val newHistory = currentHistory + aiMsg
                    saveChatHistory(context, newHistory)
                    onHistoryUpdated(newHistory)
                } else {
                    val errorMsg = ChatMessage("assistant", "API Error: ${response.code}")
                    val newHistory = currentHistory + errorMsg
                    saveChatHistory(context, newHistory)
                    onHistoryUpdated(newHistory)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                val errorMsg = ChatMessage("assistant", "Network Error: ${e.message}")
                val newHistory = currentHistory + errorMsg
                saveChatHistory(context, newHistory)
                onHistoryUpdated(newHistory)
            }
        } finally {
            withContext(Dispatchers.Main) { onLoadingChanged(false) }
        }
    }
}

@Composable
fun SettingsDialog(
    currentUrl: String, currentKey: String, currentModel: String,
    onDismiss: () -> Unit, onSave: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var key by remember { mutableStateOf(currentKey) }
    var model by remember { mutableStateOf(currentModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API Settings", color = Color.White) },
        containerColor = Color(0xFF1c1e25),
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url, key, model) }) { Text("Save", color = Color(0xFF6378ed)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        }
    )
}

@Composable
fun MessageBubble(msg: ChatMessage, onSaveCode: (String) -> Unit) {
    val isUser = msg.role == "user"
    val bgColor = if (isUser) Color(0xFF303c70) else Color(0xFF22242b)
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier.background(bgColor, RoundedCornerShape(12.dp)).padding(12.dp).widthIn(max = 300.dp)
        ) {
            Column {
                Text(text = msg.content, color = Color.White, fontSize = 15.sp)
                if (!isUser && msg.content.contains("```")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onSaveCode(msg.content) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF363944)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("💾 Save to Folder", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
