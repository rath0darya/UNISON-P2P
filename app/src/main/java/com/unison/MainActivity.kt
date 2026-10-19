package com.unison

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UnisonBlue = Color(0xFF1769E0)
private val ReadGreen = Color(0xFF20A56B)
private val AppBg = Color(0xFFF3F6FA)
private val BubbleGreen = Color(0xFFDDF7D2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnisonApp() }
    }
}

private enum class Tab { CHATS, CONTACTS, CALLS, SETTINGS }
private data class ChatItem(val name: String, val preview: String, val time: String, val unread: Int, val color: Color)
private data class ChatMessage(val text: String, val time: String, val outgoing: Boolean, val status: Int, val sentAt: Long, val deliveredAt: Long? = null, val readAt: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnisonApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.CHATS) }
    var activeChat by remember { mutableStateOf<String?>(null) }
    var showIdentity by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var peerCodeInput by remember { mutableStateOf("") }
    var pairMessage by remember { mutableStateOf<String?>(null) }
    var yourDisplayName by remember { mutableStateOf("Me") }
    var peerDisplayName by remember { mutableStateOf("Arjun") }
    var presence by remember { mutableStateOf("Online") }
    var showInfo by remember { mutableStateOf<ChatMessage?>(null) }
    var identity by remember { mutableStateOf("Generating identity…") }
    var pairingCode by remember { mutableStateOf(IdentityVault.newPairingCode()) }
    var messageDraft by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf(
        ChatMessage("Hey! Welcome to UNISON.", "10:42 AM", false, 2, System.currentTimeMillis() - 120000),
        ChatMessage("Private chat, directly between devices where the network allows it.", "10:43 AM", true, 3, System.currentTimeMillis() - 60000, System.currentTimeMillis() - 55000, System.currentTimeMillis() - 40000)
    ) }
    LaunchedEffect(Unit) { identity = runCatching { IdentityVault.publicIdentityFingerprint() }.getOrElse { "Identity unavailable" } }
    BackHandler(enabled = showPairDialog || showIdentity || showInfo != null || activeChat != null || tab != Tab.CHATS) {
        when {
            showPairDialog -> { showPairDialog = false; pairMessage = null }
            showIdentity -> showIdentity = false
            showInfo != null -> showInfo = null
            activeChat != null -> { activeChat = null; messageDraft = "" }
            tab != Tab.CHATS -> tab = Tab.CHATS
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = UnisonBlue,
            background = AppBg,
            surface = Color.White,
            secondary = ReadGreen
        )
    ) {
        Scaffold(
            containerColor = AppBg,
            topBar = {
                if (activeChat == null) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(38.dp).background(UnisonBlue, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Text("U", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                                }
                                Column {
                                    Text("UNISON", fontWeight = FontWeight.Bold)
                                    Text("Peer-to-peer messenger", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showIdentity = true }) { Icon(Icons.Default.Lock, "Identity and security") }
                            IconButton(onClick = { tab = Tab.SETTINGS }) { Icon(Icons.Default.MoreVert, "Settings") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(activeChat ?: "", fontWeight = FontWeight.SemiBold)
                                Text(if (presence == "Offline") "You are offline" else "Direct status not connected", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = { IconButton(onClick = { activeChat = null }) { Icon(Icons.Default.ArrowBack, "Back") } },
                        actions = {
                            IconButton(onClick = {}) { Icon(Icons.Default.Phone, "Voice call") }
                            IconButton(onClick = {}) { Icon(Icons.Default.Videocam, "Video call") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                    )
                }
            },
            bottomBar = {
                if (activeChat == null) NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == Tab.CHATS, onClick = { tab = Tab.CHATS }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text("Chats") })
                    NavigationBarItem(selected = tab == Tab.CONTACTS, onClick = { tab = Tab.CONTACTS }, icon = { Icon(Icons.Default.Contacts, null) }, label = { Text("Contacts") })
                    NavigationBarItem(selected = tab == Tab.CALLS, onClick = { tab = Tab.CALLS }, icon = { Icon(Icons.Default.Call, null) }, label = { Text("Calls") })
                    NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
            floatingActionButton = {
                if (activeChat == null && tab == Tab.CHATS) FloatingActionButton(
                    onClick = { showIdentity = true },
                    containerColor = UnisonBlue,
                    contentColor = Color.White
                ) { Icon(Icons.Default.Add, "Add peer") }
            }
        ) { padding ->
            when {
                activeChat != null -> ChatScreen(
                    modifier = Modifier.padding(padding),
                    peerName = activeChat!!,
                    messages = messages,
                    draft = messageDraft,
                    onDraftChange = { messageDraft = it },
                    onSend = {
                        val body = messageDraft.trim()
                        if (body.isNotEmpty()) {
                            messages.add(ChatMessage(body, nowTime(), true, 1, System.currentTimeMillis()))
                            messageDraft = ""
                        }
                    },
                    onMessageInfo = { showInfo = it }
                )
                tab == Tab.CHATS -> ChatList(Modifier.padding(padding)) { activeChat = it }
                tab == Tab.CONTACTS -> EmptyPanel(Modifier.padding(padding), "Contacts", "Pair with a person using their uppercase pairing code.")
                tab == Tab.CALLS -> EmptyPanel(Modifier.padding(padding), "Calls", "Voice and video call history will appear here when the direct call engine is connected.")
                else -> SettingsPanel(
                    modifier = Modifier.padding(padding),
                    identity = identity,
                    onIdentity = { showIdentity = true },
                    yourDisplayName = yourDisplayName,
                    peerDisplayName = peerDisplayName,
                    onYourDisplayName = { yourDisplayName = it },
                    onPeerDisplayName = { peerDisplayName = it },
                    presence = presence,
                    onPresence = { presence = it },
                    onPair = { showPairDialog = true },
                    onRequestNotifications = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) (context as? ComponentActivity)?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 441)
                        else Toast.makeText(context, "Notifications are already permitted", Toast.LENGTH_SHORT).show()
                    },
                    onBackground = {
                        if (android.os.Build.VERSION.SDK_INT < 33 ||
                            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) context.startForegroundService(Intent(context, BackgroundConnectionService::class.java))
                    }
                )
            }
        }

        if (showIdentity) AlertDialog(
            onDismissRequest = { showIdentity = false },
            title = { Text("Your UNISON identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Public fingerprint", fontWeight = FontWeight.SemiBold)
                    Text(identity, fontSize = 12.sp)
                    Text("Pairing code (34 characters)", fontWeight = FontWeight.SemiBold)
                    Text(pairingCode, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("UNISON pairing code", pairingCode))
                        Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Copy pairing code") }
                    Text("Share this code privately. A code alone cannot bypass carrier NAT or make an unreachable phone connect.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { pairingCode = IdentityVault.newPairingCode() }) { Text("New code") } },
            dismissButton = { TextButton(onClick = { showIdentity = false; showPairDialog = true }) { Text("Pair with code") } }
        )

        if (showPairDialog) AlertDialog(
            onDismissRequest = { showPairDialog = false; pairMessage = null },
            title = { Text("Pair with a person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the other person's UNISON pairing code.")
                    OutlinedTextField(
                        value = peerCodeInput,
                        onValueChange = { value -> peerCodeInput = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(40) },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    pairMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Text("This build can validate the code format, but live peer-to-peer transport is not connected yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val compact = peerCodeInput.replace("-", "")
                    pairMessage = if (compact.length != 28 || compact.any { it !in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }) {
                        "Invalid format. Use 28 uppercase letters/digits with hyphens between groups."
                    } else {
                        "Code format is valid, but a live connection cannot be established in this build yet."
                    }
                }) { Text("Validate code") }
            },
            dismissButton = { TextButton(onClick = { showPairDialog = false; pairMessage = null }) { Text("Close") } }
        )

        showInfo?.let { message ->
            AlertDialog(
                onDismissRequest = { showInfo = null },
                title = { Text("Message info") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(message.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoRow("Sent", formatTime(message.sentAt))
                        InfoRow("Delivered", message.deliveredAt?.let(::formatTime) ?: "Not delivered")
                        InfoRow("Read", message.readAt?.let(::formatTime) ?: "Not read")
                    }
                },
                confirmButton = { TextButton(onClick = { showInfo = null }) { Text("Close") } }
            )
        }
    }
}


@Composable
private fun ChatList(modifier: Modifier, onOpen: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val chats = listOf(
        ChatItem("UNISON Notes", "Your conversations stay encrypted locally.", "10:44", 0, Color(0xFF4D8DE8)),
        ChatItem("Arjun", "Message delivery depends on a direct connection.", "Yesterday", 2, Color(0xFF16A085)),
        ChatItem("Family group", "No central group server configured", "Mon", 0, Color(0xFF8E68D8))
    )
    Column(modifier.fillMaxSize().background(Color.White)) {
        Surface(color = Color.White) {
            OutlinedTextField(
                value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text("Search chats and usernames") }, singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
        }
        LazyColumn {
            items(chats.filter { it.name.contains(search, ignoreCase = true) || it.preview.contains(search, ignoreCase = true) }) { chat ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(chat.name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(52.dp).background(chat.color, CircleShape), contentAlignment = Alignment.Center) {
                        Text(chat.name.take(1), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold)
                        Text(chat.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Color(0xFF6C7683))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chat.time, fontSize = 11.sp, color = Color(0xFF77818C))
                        if (chat.unread > 0) Box(Modifier.background(UnisonBlue, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)) {
                            Text(chat.unread.toString(), color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFF0F2F5), modifier = Modifier.padding(start = 80.dp))
            }
        }
    }
}

@Composable
private fun ChatScreen(
    modifier: Modifier,
    peerName: String,
    messages: List<ChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMessageInfo: (ChatMessage) -> Unit
) {
    Column(modifier.fillMaxSize().background(Color(0xFFEAF0F5))) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            reverseLayout = false,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Surface(color = Color(0xFFE0EAF5), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text("End-to-end encrypted design • direct peer engine not connected in this build", Modifier.padding(10.dp), fontSize = 11.sp, color = Color(0xFF425A70))
                }
            }
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.outgoing) BubbleGreen else Color.White,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 320.dp).clickable { onMessageInfo(message) }
                    ) {
                        Column(Modifier.padding(start = 12.dp, top = 9.dp, end = 10.dp, bottom = 6.dp)) {
                            Text(message.text, fontSize = 15.sp, color = Color(0xFF17202A))
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(message.time, fontSize = 10.sp, color = Color(0xFF66736A))
                                if (message.outgoing) TickMark(message.status)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft, onValueChange = onDraftChange, modifier = Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 4,
                shape = RoundedCornerShape(26.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSend, modifier = Modifier.size(48.dp).background(UnisonBlue, CircleShape)) {
                Icon(Icons.Default.Send, "Send message", tint = Color.White)
            }
        }
    }
}

@Composable
private fun TickMark(status: Int) {
    Canvas(Modifier.width(if (status >= 2) 19.dp else 11.dp).height(10.dp)) {
        val color = if (status >= 3) ReadGreen else Color(0xFF7B8780)
        fun tick(x: Float) {
            val path = Path().apply {
                moveTo(x, size.height * 0.52f)
                lineTo(x + size.width * 0.20f, size.height * 0.82f)
                lineTo(x + size.width * 0.58f, size.height * 0.16f)
            }
            drawPath(path, color, style = Stroke(width = size.height * 0.14f, cap = StrokeCap.Round))
        }
        tick(0f)
        if (status >= 2) tick(size.width * 0.40f)
    }
}

@Composable
private fun EmptyPanel(modifier: Modifier, title: String, description: String) {
    Column(modifier.fillMaxSize().background(Color.White).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Info, null, tint = UnisonBlue, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = Color(0xFF6C7683))
    }
}

@Composable
private fun SettingsPanel(
    modifier: Modifier,
    identity: String,
    onIdentity: () -> Unit,
    yourDisplayName: String,
    peerDisplayName: String,
    onYourDisplayName: (String) -> Unit,
    onPeerDisplayName: (String) -> Unit,
    presence: String,
    onPresence: (String) -> Unit,
    onPair: () -> Unit,
    onRequestNotifications: () -> Unit,
    onBackground: () -> Unit
) {
    Column(modifier.fillMaxSize().background(Color.White).padding(18.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Privacy, identity and local storage", color = Color(0xFF687481))
        SettingsItem("My identity & pairing code", "Copy or share your pairing code", onIdentity)
        SettingsItem("Pair with a person", "Enter another person's pairing code", onPair)
        Text("Names on this phone", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = yourDisplayName, onValueChange = onYourDisplayName, label = { Text("Your display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = peerDisplayName, onValueChange = onPeerDisplayName, label = { Text("Other person's display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Names are local labels only; they do not create an account or connect to a backend.", fontSize = 12.sp, color = Color(0xFF687481))
        Text("Availability preference", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = presence == "Online", onClick = { onPresence("Online") }, label = { Text("Online") })
            FilterChip(selected = presence == "Offline", onClick = { onPresence("Offline") }, label = { Text("Offline") })
        }
        SettingsItem("Allow notifications", "Request Android notification permission", onRequestNotifications)
        SettingsItem("Background connection", "Start the foreground service notification", onBackground)
        Text("Private fingerprint", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(identity, fontSize = 11.sp, color = Color(0xFF687481))
        Text("The live P2P transport, real delivery receipts, and incoming-call engine are not connected yet. Availability above is currently a local preference.", fontSize = 12.sp, color = Color(0xFF8A4D16))
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { onClick() }, color = AppBg, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF6C7683))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Info, null, tint = if (label == "Read" && value != "Not read") ReadGreen else UnisonBlue, modifier = Modifier.size(20.dp))
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(value, color = Color(0xFF697583), fontSize = 12.sp)
        }
    }
}

private fun nowTime(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
private fun formatTime(epoch: Long): String = SimpleDateFormat("dd MMM yyyy, h:mm:ss a", Locale.getDefault()).format(Date(epoch))
