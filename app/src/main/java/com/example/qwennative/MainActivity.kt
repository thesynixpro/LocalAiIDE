package com.example.qwennative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.UUID

data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAuth.getInstance().signInAnonymously()
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
    val db = FirebaseFirestore.getInstance()
    val user = FirebaseAuth.getInstance().currentUser

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var chatId by remember { mutableStateOf<String?>(null) }
    
    // API Settings
    val apiBaseUrl = "https://gig-mounted-hepatitis-leading.trycloudflare.com/v1"
    val apiKey = "TPk4esc84wyzHLkqCE6FWIf9EOx39SvVATIikC_aIRg"

    var fileContentToSave by remember { mutableStateOf("") }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    val writer = OutputStreamWriter(stream)
                    writer.write(fileContentToSave)
                    writer.close()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    LaunchedEffect(user) {
        if (user != null && chatId == null) {
            val newChatId = UUID.randomUUID().toString()
            chatId = newChatId
            db.collection("chats").document(newChatId).set(
                mapOf("uid" to user.uid, "title" to "New Chat", "updatedAt" to System.currentTimeMillis())
            )
        }
    }

    LaunchedEffect(chatId) {
        chatId?.let { id ->
            db.collection("chats").document(id).collection("messages")
                .addSnapshotListener { snapshot, _ ->
                    snapshot?.let {
                        val fetched = it.documents.mapNotNull { doc ->
                            val role = doc.getString("role") ?: return@mapNotNull null
                            val content = doc.getString("content") ?: return@mapNotNull null
                            ChatMessage(role, content)
                        }.sortedBy { it.timestamp }
                        messages = fetched
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Qwen Native", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Firebase Synced", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg = msg) { code ->
                    fileContentToSave = code
                    filePickerLauncher.launch("code_${System.currentTimeMillis()}.txt")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

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
                        sendMessageToApi(prompt, apiBaseUrl, apiKey, chatId, db) { isGenerating = it }
                    }
                },
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6378ed))
            ) {
                Text(if (isGenerating) "..." else "Send")
            }
        }
    }
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
                // FIXED: Removed the backslashes
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

fun sendMessageToApi(prompt: String, url: String, key: String, chatId: String?, db: FirebaseFirestore, onLoadingChanged: (Boolean) -> Unit) {
    onLoadingChanged(true)
    val userMsg = mapOf("role" to "user", "content" to prompt, "timestamp" to System.currentTimeMillis())
    chatId?.let { db.collection("chats").document(it).collection("messages").add(userMsg) }

    val client = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
    val json = JSONObject().apply {
        put("model", "Qwen/Qwen2.5-Coder-7B-Instruct")
        put("stream", false)
        put("max_tokens", 2048)
        put("temperature", 0.7)
        put("messages", org.json.JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }))
    }

    val request = Request.Builder().url("$url/chat/completions").post(json.toString().toRequestBody("application/json".toMediaType())).header("Authorization", "Bearer $key").build()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                val answer = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val aiMsg = mapOf("role" to "assistant", "content" to answer, "timestamp" to System.currentTimeMillis())
                chatId?.let { db.collection("chats").document(it).collection("messages").add(aiMsg) }
            }
        } catch (e: Exception) { e.printStackTrace() } finally {
            withContext(Dispatchers.Main) { onLoadingChanged(false) }
        }
    }
}