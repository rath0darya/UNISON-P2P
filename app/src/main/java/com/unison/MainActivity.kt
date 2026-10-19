package com.unison

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.verticalScroll
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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
    val localPrefs = remember { context.getSharedPreferences("unison_user_preferences", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(Tab.CHATS) }
    var activeChat by remember { mutableStateOf<String?>(null) }
    var showIdentity by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var peerCodeInput by remember { mutableStateOf("") }
    var pairMessage by remember { mutableStateOf<String?>(null) }
    var yourDisplayName by remember { mutableStateOf(localPrefs.getString("your_display_name", "Me") ?: "Me") }
    var peerDisplayName by remember { mutableStateOf(localPrefs.getString("peer_display_name", "") ?: "") }
    var presence by remember { mutableStateOf(localPrefs.getString("presence", "Online") ?: "Online") }
    var showInfo by remember { mutableStateOf<ChatMessage?>(null) }
    var identity by remember { mutableStateOf("Generating identity…") }
    var pairingCode by remember {
        mutableStateOf(localPrefs.getString("pairing_code", null) ?: IdentityVault.newPairingCode().also {
            localPrefs.edit().putString("pairing_code", it).apply()
        })
    }
    var messageDraft by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var notificationAllowed by remember { mutableStateOf((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var showPermissionGate by remember { mutableStateOf(false) }
    var showPermissionIntroduction by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val allowed = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                notificationAllowed = allowed
                showPermissionGate = !allowed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationAllowed = granted
        showPermissionGate = !granted
    }
    val optionalPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { !it }) Toast.makeText(context, "Some optional features will ask again when you use them.", Toast.LENGTH_LONG).show()
        localPrefs.edit().putBoolean("optional_permissions_prompted", true).apply()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else if (!notificationAllowed) showPermissionGate = true
    }
    LaunchedEffect(notificationAllowed) {
        if (notificationAllowed && !localPrefs.getBoolean("optional_permissions_prompted", false)) {
            showPermissionIntroduction = true
        }
    }
    val optionalPermissions = remember {
        buildList {
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) {
                add("android.permission.READ_MEDIA_IMAGES")
                add("android.permission.READ_MEDIA_VIDEO")
                add("android.permission.READ_MEDIA_AUDIO")
                if (Build.VERSION.SDK_INT >= 34) add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            } else add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
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
                                    Text("Unison", fontWeight = FontWeight.Bold)
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
                            Column(Modifier.clickable { showPairDialog = true }) {
                                Text(activeChat ?: "", fontWeight = FontWeight.SemiBold)
                                Text(if (presence == "Offline") "You are offline" else "Direct status not connected", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = { IconButton(onClick = { activeChat = null }) { Icon(Icons.Default.ArrowBack, "Back") } },
                        actions = {
                            IconButton(onClick = {
                                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Toast.makeText(context, "Microphone permission is ready. The direct voice-call engine is not connected yet.", Toast.LENGTH_LONG).show()
                                else (context as? ComponentActivity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 442)
                            }) { Icon(Icons.Default.Phone, "Voice call") }
                            IconButton(onClick = {
                                val missing = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                                if (missing.isEmpty()) Toast.makeText(context, "Camera and microphone permissions are ready. The direct video-call engine is not connected yet.", Toast.LENGTH_LONG).show()
                                else (context as? ComponentActivity)?.requestPermissions(missing.toTypedArray(), 443)
                            }) { Icon(Icons.Default.Videocam, "Video call") }
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
                        if (messageDraft.isNotBlank()) {
                            Toast.makeText(context, "Message not sent: live peer-to-peer transport is not implemented yet.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onMessageInfo = { showInfo = it }
                )
                tab == Tab.CHATS -> ChatList(Modifier.padding(padding), peerDisplayName) { activeChat = it }
                tab == Tab.CONTACTS -> EmptyPanel(Modifier.padding(padding), "Contacts", "Pair with a person using their uppercase pairing code.")
                tab == Tab.CALLS -> EmptyPanel(Modifier.padding(padding), "Calls", "Voice and video call history will appear here when the direct call engine is connected.")
                else -> SettingsPanel(
                    modifier = Modifier.padding(padding),
                    identity = identity,
                    onIdentity = { showIdentity = true },
                    yourDisplayName = yourDisplayName,
                    peerDisplayName = peerDisplayName,
                    onYourDisplayName = { yourDisplayName = it; localPrefs.edit().putString("your_display_name", it).apply() },
                    onPeerDisplayName = { peerDisplayName = it; localPrefs.edit().putString("peer_display_name", it).apply() },
                    presence = presence,
                    onPresence = { presence = it; localPrefs.edit().putString("presence", it).apply() },
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

        if (showPermissionIntroduction && notificationAllowed) AlertDialog(
            onDismissRequest = {
                showPermissionIntroduction = false
                localPrefs.edit().putBoolean("optional_permissions_prompted", true).apply()
            },
            title = { Text("Set up your permissions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose which features Unison can use on this phone.")
                    listOf(
                        "Microphone — voice calls and voice messages",
                        "Camera — video calls and taking photos",
                        "Photos and videos — choose media to share",
                        "Audio and files — select documents and recordings"
                    ).forEach { line ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, null, tint = UnisonBlue, modifier = Modifier.size(18.dp))
                            Text(line, fontSize = 13.sp)
                        }
                    }
                    Text("You can deny optional permissions and enable them later in Android Settings. File pickers may work without broad storage access.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionIntroduction = false
                    optionalPermissionLauncher.launch(optionalPermissions.toTypedArray())
                }) { Text("Review permissions") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionIntroduction = false
                    localPrefs.edit().putBoolean("optional_permissions_prompted", true).apply()
                }) { Text("Not now") }
            }
        )

        if (showPermissionGate) AlertDialog(
            onDismissRequest = { },
            title = { Text("Notifications are required") },
            text = { Text("Unison needs notifications enabled for message and call alerts. Allow notifications to continue, or enable them in Android App info. Camera, microphone and media access remain your choice.") },
            confirmButton = { TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Allow notifications") } },
            dismissButton = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }) { Text("Open app settings") } }
        )

        if (showIdentity) AlertDialog(
            onDismissRequest = { showIdentity = false },
            title = { Text("Your Unison identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Public fingerprint", fontWeight = FontWeight.SemiBold)
                    Text(identity, fontSize = 12.sp)
                    Text("Pairing code (34 characters)", fontWeight = FontWeight.SemiBold)
                    Text(pairingCode, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Unison pairing code", pairingCode))
                        Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Copy pairing code") }
                    Text("Share this code privately. A code alone cannot bypass carrier NAT or make an unreachable phone connect.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = {
                pairingCode = IdentityVault.newPairingCode()
                localPrefs.edit().putString("pairing_code", pairingCode).apply()
            }) { Text("New code") } },
            dismissButton = { TextButton(onClick = { showIdentity = false; showPairDialog = true }) { Text("Pair with code") } }
        )

        if (showPairDialog) AlertDialog(
            onDismissRequest = { showPairDialog = false; pairMessage = null },
            title = { Text("Pair with a person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the other person's Unison pairing code.")
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
private fun ChatList(modifier: Modifier, peerDisplayName: String, onOpen: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val chats = emptyList<ChatItem>()
    Column(modifier.fillMaxSize().background(Color(0xFFF4F7FB))) {
        Surface(color = Color.White, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(UnisonBlue, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("U", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Unison", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17263C))
                        Text("Your conversations, your space", fontSize = 12.sp, color = Color(0xFF718096))
                    }
                    Surface(color = Color(0xFFEAF2FF), shape = CircleShape) {
                        Icon(Icons.Default.Lock, "Privacy", tint = UnisonBlue, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                }
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search people and messages") },
                    leadingIcon = { Icon(Icons.Default.Chat, null, tint = Color(0xFF8290A2)) },
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFFF5F7FA),
                        focusedContainerColor = Color(0xFFF5F7FA),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = UnisonBlue
                    )
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF1769E0),
            shadowElevation = 4.dp
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(54.dp).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Chat, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Start a conversation", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Pair directly with someone to get started.", color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
                }
                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MESSAGES", fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8390A1))
            Spacer(Modifier.weight(1f))
            Text("0 chats", fontSize = 12.sp, color = Color(0xFF8390A1))
        }
        if (chats.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(116.dp).background(Color(0xFFE6EEFC), CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(78.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Chat, null, tint = UnisonBlue, modifier = Modifier.size(38.dp))
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("Your chat list is waiting", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C2B40))
                Spacer(Modifier.height(8.dp))
                Text(
                    "No sample conversations here. Pairing and live message delivery must be connected before real chats can appear.",
                    color = Color(0xFF718096), fontSize = 13.sp, lineHeight = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Surface(color = Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(Color(0xFFE3A33B), CircleShape))
                        Text("Connection engine not ready", color = Color(0xFF6D7888), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(chats.filter { it.name.contains(search, ignoreCase = true) || it.preview.contains(search, ignoreCase = true) }) { chat ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(chat.name) }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(54.dp).background(chat.color, CircleShape), contentAlignment = Alignment.Center) {
                        Text(chat.name.take(1), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C2B40))
                        Text(chat.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Color(0xFF718096))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chat.time, fontSize = 11.sp, color = Color(0xFF8390A1))
                        if (chat.unread > 0) Box(Modifier.background(UnisonBlue, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)) {
                            Text(chat.unread.toString(), color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFE8EDF4), modifier = Modifier.padding(start = 85.dp))
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
    val context = LocalContext.current
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Toast.makeText(context, "Photo selected, but file delivery is not connected yet.", Toast.LENGTH_LONG).show()
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Toast.makeText(context, "Video selected, but file delivery is not connected yet.", Toast.LENGTH_LONG).show()
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Toast.makeText(context, "Document selected, but file delivery is not connected yet.", Toast.LENGTH_LONG).show()
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Toast.makeText(context, "Audio selected, but file delivery is not connected yet.", Toast.LENGTH_LONG).show()
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) Toast.makeText(context, "Photo captured, but file delivery is not connected yet.", Toast.LENGTH_LONG).show()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraPicker.launch(null)
        else Toast.makeText(context, "Camera permission is needed to take a photo.", Toast.LENGTH_LONG).show()
    }
    var showAttachments by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Color(0xFFE8EFF6))) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 54.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(92.dp).background(Color.White.copy(alpha = 0.86f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Chat, null, tint = UnisonBlue, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Say hello", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF23364D))
                    Spacer(Modifier.height(6.dp))
                    Text("Your messages will appear here after a real connection is established.", fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF708198), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
                    Spacer(Modifier.height(16.dp))
                    Surface(color = Color(0xFFDCE7F4), shape = RoundedCornerShape(12.dp)) {
                        Text("No demo messages", Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 12.sp, color = Color(0xFF526780))
                    }
                }
            }
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.outgoing) Color(0xFFD8F7C8) else Color.White,
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (message.outgoing) 18.dp else 5.dp, bottomEnd = if (message.outgoing) 5.dp else 18.dp),
                        shadowElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 320.dp).clickable { onMessageInfo(message) }
                    ) {
                        Column(Modifier.padding(start = 13.dp, top = 10.dp, end = 11.dp, bottom = 7.dp)) {
                            Text(message.text, fontSize = 15.sp, lineHeight = 21.sp, color = Color(0xFF17283C))
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(message.time, fontSize = 10.sp, color = Color(0xFF748174))
                                if (message.outgoing) TickMark(message.status)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = Color.White, shadowElevation = 8.dp) {
            Row(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                IconButton(onClick = { showAttachments = true }, modifier = Modifier.size(44.dp).background(Color(0xFFF1F5FA), CircleShape)) {
                    Icon(Icons.Default.Add, "Attach file", tint = Color(0xFF536A85))
                }
                OutlinedTextField(
                    value = draft, onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = Color(0xFF8B98A9)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFFF8FAFC),
                        focusedContainerColor = Color(0xFFF8FAFC),
                        unfocusedBorderColor = Color(0xFFE7ECF2),
                        focusedBorderColor = UnisonBlue
                    )
                )
                IconButton(onClick = onSend, modifier = Modifier.size(46.dp).background(UnisonBlue, CircleShape)) {
                    Icon(Icons.Default.Send, "Send message", tint = Color.White)
                }
            }
        }
    }
    if (showAttachments) AlertDialog(
        onDismissRequest = { showAttachments = false },
        title = { Text("Share with $peerName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentTile("Gallery", "Photos", Icons.Default.Contacts) { showAttachments = false; galleryPicker.launch("image/*") }
                    AttachmentTile("Camera", "Take photo", Icons.Default.Videocam) {
                        showAttachments = false
                        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraPicker.launch(null)
                        else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentTile("Video", "Clips", Icons.Default.Videocam) { showAttachments = false; videoPicker.launch("video/*") }
                    AttachmentTile("Documents", "PDF and files", Icons.Default.Info) { showAttachments = false; documentPicker.launch(arrayOf("*/*")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentTile("Audio", "Music and voice", Icons.Default.Call) { showAttachments = false; audioPicker.launch(arrayOf("audio/*")) }
                    AttachmentTile("Location", "Places", Icons.Default.Info) { showAttachments = false; Toast.makeText(context, "Location sharing is not connected yet.", Toast.LENGTH_LONG).show() }
                }
                Row { AttachmentTile("Contact", "Share a contact", Icons.Default.Contacts) { showAttachments = false; Toast.makeText(context, "Contact sharing is not connected yet.", Toast.LENGTH_LONG).show() } }
                Text("Picker works on this device. Sending attachments requires the live transport, which is not implemented yet.", fontSize = 11.sp, color = Color(0xFF8A5A19))
            }
        },
        confirmButton = { TextButton(onClick = { showAttachments = false }) { Text("Close") } }
    )
}

@Composable
private fun RowScope.AttachmentTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        Modifier.weight(1f).clickable(onClick = onClick),
        color = Color(0xFFF3F6FA),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE7EDF5))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
            Box(Modifier.size(38.dp).background(Color(0xFFE2ECFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = UnisonBlue, modifier = Modifier.size(21.dp))
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF24364B))
            Text(subtitle, fontSize = 10.sp, color = Color(0xFF748399), maxLines = 2)
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
    Column(modifier.fillMaxSize().background(Color(0xFFF4F7FB)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(104.dp).background(Color(0xFFE4EDFC), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(72.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (title == "Calls") Icons.Default.Call else Icons.Default.Contacts, null, tint = UnisonBlue, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D2D42))
        Spacer(Modifier.height(8.dp))
        Text(description, color = Color(0xFF718096), fontSize = 13.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp) {
            Text("Ready when connection is available", Modifier.padding(horizontal = 15.dp, vertical = 11.dp), color = Color(0xFF60718A), fontSize = 12.sp)
        }
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
    Column(modifier.fillMaxSize().background(Color(0xFFF4F7FB)).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Surface(color = Color.White, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Settings", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B2B40))
                Text("Make Unison feel like yours.", fontSize = 13.sp, color = Color(0xFF718096))
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF1769E0),
            shadowElevation = 3.dp
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(58.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(yourDisplayName.trim().take(1).ifEmpty { "U" }.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(yourDisplayName.ifBlank { "Your name" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Personal profile on this device", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
                Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.9f))
            }
        }
        SettingsSectionTitle("PROFILE")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8EDF4))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = yourDisplayName, onValueChange = onYourDisplayName, label = { Text("Your display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = peerDisplayName, onValueChange = onPeerDisplayName, label = { Text("Other person's local name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    Text("Names are stored on this phone. They do not create an online account.", fontSize = 11.sp, color = Color(0xFF7B8797))
                }
            }
        }
        SettingsSectionTitle("PEOPLE & PAIRING")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SettingsItem("My identity & pairing code", "Copy your code or view your identity", Icons.Default.Lock, onIdentity)
            SettingsItem("Pair with a person", "Enter their uppercase pairing code", Icons.Default.Add, onPair)
        }
        SettingsSectionTitle("AVAILABILITY")
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8EDF4))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Show me as", fontWeight = FontWeight.SemiBold, color = Color(0xFF27384D))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FilterChip(selected = presence == "Online", onClick = { onPresence("Online") }, label = { Text("Online") }, leadingIcon = { Box(Modifier.size(7.dp).background(Color(0xFF28B878), CircleShape)) })
                    FilterChip(selected = presence == "Offline", onClick = { onPresence("Offline") }, label = { Text("Offline") }, leadingIcon = { Box(Modifier.size(7.dp).background(Color(0xFF9AA5B3), CircleShape)) })
                }
                Text("This is currently a local preference; it does not report live network presence.", fontSize = 11.sp, color = Color(0xFF7B8797))
            }
        }
        SettingsSectionTitle("APP & PERMISSIONS")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SettingsItem("Notifications", "Required for message and call alerts", Icons.Default.Info, onRequestNotifications)
            SettingsItem("Background activity", "Show the app's foreground-service notification", Icons.Default.Call, onBackground)
        }
        SettingsSectionTitle("ABOUT UNISON")
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8EDF4))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unison", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D2D42))
                Text("Version 1.0.0", fontSize = 12.sp, color = Color(0xFF7B8797))
                Text("The direct messaging, delivery receipt and voice/video calling engine is still not connected. Pairing code validation alone does not establish a live connection.", fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF8A5A19))
                Text("Identity fingerprint", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(identity, fontSize = 11.sp, color = Color(0xFF718096))
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(start = 21.dp, top = 9.dp, bottom = 9.dp), fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8491A2))
}

@Composable
private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8EDF4))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(42.dp).background(Color(0xFFEAF1FF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = UnisonBlue, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF26374B), fontSize = 14.sp)
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF7B8797))
            }
            Icon(Icons.Default.MoreVert, null, tint = Color(0xFF9AA5B3), modifier = Modifier.size(18.dp))
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
