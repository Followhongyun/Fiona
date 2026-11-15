package com.wangzan101.mika.fiona

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One line in the conversation. */
data class FionaMessage(val id: String, val mine: Boolean, val text: String, val at: Long)

/**
 * Fiona's home: a clean conversation window with 大肥鱼.
 *
 * The phone only draws the conversation — her memory, her judgement and her work all live in the
 * cloud. Messages go through the small bridge that speaks her channel API.
 */
class FionaChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FionaChatTheme { ChatScreen() } }
    }
}

private val FishBlue = Color(0xFF2E6BE6)
private val FishBubble = Color(0xFFF1F5FF)
private val Ink = Color(0xFF14161A)
private val Sub = Color(0xFF8A9099)

@Composable
private fun FionaChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = FishBlue, background = Color(0xFFFBFCFE), surface = Color.White),
    ) { content() }
}

@Composable
private fun ChatScreen() {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<FionaMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun refresh() {
        val r = runCatching { FionaApi.history() }
        r.onSuccess { messages = it; error = null }
            .onFailure { if (messages.isEmpty()) error = it.message ?: "连不上她" }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(2500)
            runCatching { FionaApi.history() }.onSuccess {
                if (it != messages) messages = it
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFFBFCFE))) {
        ChatHeader()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Center { Text("正在连她…", color = Sub, fontSize = 14.sp) }
                error != null && messages.isEmpty() -> Center {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = Color(0xFFB3261E), fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { scope.launch { loading = true; refresh() } }) { Text("重试") }
                    }
                }
                messages.isEmpty() -> Center {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("大肥鱼在这儿", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text("说点什么，她在云端听着", color = Sub, fontSize = 13.sp)
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { m -> Bubble(m) }
                    if (sending) item { TypingBubble() }
                }
            }
        }

        Composer(
            input = input,
            onInput = { input = it },
            sending = sending,
            onSend = {
                val text = input.trim()
                if (text.isEmpty() || sending) return@Composer
                input = ""
                sending = true
                scope.launch {
                    val optimistic = FionaMessage("local-" + System.currentTimeMillis(), true, text, System.currentTimeMillis())
                    messages = messages + optimistic
                    runCatching { FionaApi.send(text) }
                        .onSuccess { delay(600); refresh() }
                        .onFailure { error = it.message }
                    sending = false
                }
            },
        )
    }
}

@Composable
private fun ChatHeader() {
    Surface(color = Color.White, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4C8DFF), Color(0xFF1E4FD8)))),
                contentAlignment = Alignment.Center,
            ) {
                Text("鱼", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("大肥鱼", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF35C759)))
                    Spacer(Modifier.width(5.dp))
                    Text("在云端 · 在线", fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: FionaMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!m.mine) {
            Box(
                Modifier.size(28.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4C8DFF), Color(0xFF1E4FD8)))),
                contentAlignment = Alignment.Center,
            ) { Text("鱼", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            color = if (m.mine) FishBlue else Color.White,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (m.mine) 16.dp else 4.dp,
                bottomEnd = if (m.mine) 4.dp else 16.dp,
            ),
            shadowElevation = if (m.mine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(
                    m.text,
                    color = if (m.mine) Color.White else Ink,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    FionaTime.clock(m.at),
                    color = if (m.mine) Color(0x99FFFFFF) else Sub,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = FishBubble, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(4.dp)) {
            Text("她正在看…", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Sub, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Composer(input: String, onInput: (String) -> Unit, sending: Boolean, onSend: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("跟她说点什么…", color = Sub, fontSize = 14.sp) },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = FishBubble,
                    unfocusedContainerColor = FishBubble,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = input.isNotBlank() && !sending,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = FishBlue),
            ) { Text("↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** The bridge on our own server; it speaks her channel API so the app never holds those credentials. */
private object FionaApi {
    private const val KEY = BuildConfig.FIONA_BRIDGE_KEY
    private const val BASE = BuildConfig.FIONA_BRIDGE_URL

    suspend fun history(): List<FionaMessage> = withContext(Dispatchers.IO) {
        val body = call("GET", "$BASE/history?limit=60", null)
        val arr = JSONObject(body).optJSONArray("messages") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FionaMessage(
                id = o.optString("id"),
                mine = o.optString("role") == "you",
                text = o.optString("text"),
                at = o.optLong("at"),
            )
        }
    }

    suspend fun send(text: String): Unit = withContext(Dispatchers.IO) {
        call("POST", "$BASE/send", JSONObject().put("text", text).toString())
    }

    private fun call(method: String, url: String, body: String?): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("X-Fiona-Key", KEY)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { input ->
                ByteArrayOutputStream().also { out -> input.copyTo(out) }.toString("UTF-8")
            } ?: ""
            if (code !in 200..299) error("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}

private object FionaTime {
    fun clock(at: Long): String {
        if (at <= 0) return ""
        val ms = if (at < 10_000_000_000L) at * 1000 else at
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        return f.format(java.util.Date(ms))
    }
}
