package com.scanner.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhatsAppRecorderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bg = Color(0xFF0D0F14)
    val whatsappOrange = Color(0xFFFF9800)
    val cardBg = Color(0xFF161A22)
    val textSecondary = Color(0xFF7A8394)
    val accent = Color(0xFF00E5A0)

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    val recordings = remember { mutableStateListOf<CallRecording>() }
    val playbackManager = remember { RecordingPlaybackManager(scope) }
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val currentRecordingId by playbackManager.currentRecordingId.collectAsState()
    val progress by playbackManager.progress.collectAsState()

    val isActive = remember { mutableStateOf(isAccessibilityEnabled(context)) }

    var customSafeWords by remember {
        mutableStateOf(SafeWordStore.getCustomSafeWords(context))
    }
    var draftSafeWord by remember { mutableStateOf("") }

    // Load existing recordings on launch
    LaunchedEffect(Unit) {
        val dir = File(context.getExternalFilesDir(null), "WhatsAppRecordings")
        Log.d("WARecorder", "[UI] Loading existing recordings from: ${dir.absolutePath}")
        val existing = CallRecordingEventSource.loadExistingRecordings(dir)
        recordings.addAll(existing)
        Log.i("WARecorder", "[UI] Loaded ${existing.size} existing recordings, total=${recordings.size}")
    }

    // Collect new recordings
    LaunchedEffect(Unit) {
        Log.d("WARecorder", "[UI] Starting to collect new recording events from SharedFlow")
        CallRecordingEventSource.recordings.collectLatest { recording ->
            Log.i("WARecorder", "[UI] New recording received via SharedFlow: ${recording.fileName} duration=${recording.durationMs}ms")
            if (recordings.none { it.filePath == recording.filePath || it.id == recording.id }) {
                recordings.add(0, recording)
                Log.d("WARecorder", "[UI] Added to list, total recordings=${recordings.size}")
            } else {
                Log.d("WARecorder", "[UI] Duplicate recording ignored (same filePath or id): ${recording.filePath}")
            }
        }
    }

    // Update accessibility status on resume
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isActive.value = isAccessibilityEnabled(context)
                hasAudioPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                customSafeWords = SafeWordStore.getCustomSafeWords(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            playbackManager.release()
        }
    }

    fun tryAddSafeWord() {
        val ok = SafeWordStore.addCustomSafeWord(context, draftSafeWord)
        if (ok) {
            draftSafeWord = ""
            customSafeWords = SafeWordStore.getCustomSafeWords(context)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        item {
            Spacer(Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.whatsapp_recorder_title),
                color = whatsappOrange,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(48.dp))
        }

        // Status indicator
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive.value && hasAudioPermission)
                                whatsappOrange.copy(alpha = 0.15f)
                            else cardBg
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive.value && hasAudioPermission)
                                    whatsappOrange.copy(alpha = 0.3f)
                                else Color(0xFF2A3040)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive.value && hasAudioPermission)
                                        whatsappOrange
                                    else Color(0xFF3A4050)
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (isActive.value && hasAudioPermission) stringResource(R.string.recorder_ready)
                else stringResource(R.string.recorder_inactive),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (isActive.value && hasAudioPermission)
                    stringResource(R.string.recorder_ready_desc)
                else if (!hasAudioPermission)
                    stringResource(R.string.recorder_inactive_mic)
                else
                    stringResource(R.string.recorder_inactive_accessibility),
                color = textSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
        }

        // Permission button if needed
        if (!hasAudioPermission) {
            item {
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = whatsappOrange,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.grant_audio_permission),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Accessibility button if needed
        if (!isActive.value) {
            item {
                Button(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasAudioPermission) whatsappOrange else cardBg,
                        contentColor = if (hasAudioPermission) Color.White else whatsappOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.enable_accessibility_service),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item {
            SafeWordsCard(
                cardBg = cardBg,
                whatsappOrange = whatsappOrange,
                textSecondary = textSecondary,
                customWords = customSafeWords,
                draftWord = draftSafeWord,
                onDraftChange = { draftSafeWord = it },
                onAdd = { tryAddSafeWord() },
                onRemove = { w ->
                    SafeWordStore.removeCustomSafeWord(context, w)
                    customSafeWords = SafeWordStore.getCustomSafeWords(context)
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // Recordings header
        if (recordings.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.recordings_header),
                    color = whatsappOrange,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            items(recordings, key = { it.id }) { recording ->
                RecordingCard(
                    recording = recording,
                    isCurrentPlaying = currentRecordingId == recording.id && isPlaying,
                    isCurrent = currentRecordingId == recording.id,
                    progress = if (currentRecordingId == recording.id) progress else 0f,
                    onPlayPause = { playbackManager.play(recording) },
                    onDelete = {
                        playbackManager.stop()
                        File(recording.filePath).delete()
                        recordings.remove(recording)
                    },
                    cardBg = cardBg,
                    whatsappOrange = whatsappOrange,
                    textSecondary = textSecondary
                )
                Spacer(Modifier.height(8.dp))
            }
        } else {
            // Empty state
            item {
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardBg)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.no_recordings),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_recordings_desc),
                        color = textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // How it works
        item {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardBg)
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.whatsapp_how_title),
                    color = whatsappOrange,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    stringResource(R.string.whatsapp_how_1),
                    stringResource(R.string.whatsapp_how_2),
                    stringResource(R.string.whatsapp_how_3),
                    stringResource(R.string.whatsapp_how_4),
                    stringResource(R.string.whatsapp_how_5),
                ).forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}.  $step",
                        color = textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 22.sp
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SafeWordsCard(
    cardBg: Color,
    whatsappOrange: Color,
    textSecondary: Color,
    customWords: List<String>,
    draftWord: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.safety_words_title),
            color = whatsappOrange,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.safety_words_body, SafeWordStore.BUILT_IN_SAFE_WORD),
            color = textSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null, // decorative
                tint = whatsappOrange,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = SafeWordStore.BUILT_IN_SAFE_WORD,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.always_on),
                color = textSecondary,
                fontSize = 12.sp
            )
        }
        customWords.forEach { w ->
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = w,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemove(w) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_remove_word),
                        tint = textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draftWord,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.safe_word_placeholder), color = textSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = whatsappOrange,
                    unfocusedBorderColor = Color(0xFF3A4050),
                    cursorColor = whatsappOrange,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onAdd,
                enabled = draftWord.trim().isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = whatsappOrange.copy(alpha = 0.22f),
                    contentColor = whatsappOrange,
                    disabledContainerColor = Color(0xFF2A3040),
                    disabledContentColor = textSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_word),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: CallRecording,
    isCurrentPlaying: Boolean,
    isCurrent: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    cardBg: Color,
    whatsappOrange: Color,
    textSecondary: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(whatsappOrange.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = if (isCurrentPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isCurrentPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                    tint = whatsappOrange,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Recording info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.fileName.replace("whatsapp_", "WhatsApp "),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        text = formatDuration(recording.durationMs),
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "  •  ",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatFileSize(recording.fileSize),
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "  •  ",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatTimestamp(recording.timestamp),
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_recording),
                    tint = textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Progress bar (shown when this recording is selected)
        if (isCurrent) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = whatsappOrange,
                trackColor = Color(0xFF2A3040),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    return sdf.format(Date(timestamp))
}
