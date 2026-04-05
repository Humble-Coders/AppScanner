package com.scanner.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@Composable
fun GuardianScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bg = Color(0xFF0D0F14)
    val accent = Color(0xFF00E5A0)
    val cardBg = Color(0xFF161A22)
    val textSecondary = Color(0xFF7A8394)
    val guardianBlue = Color(0xFF448AFF)

    var userId by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var isGuardianLinked by remember { mutableStateOf(false) }
    var guardianLinkError by remember { mutableStateOf(false) }
    var guardianPhoneInput by remember { mutableStateOf("") }
    var phoneLinkMessage by remember { mutableStateOf("") }
    var phoneLinkError by remember { mutableStateOf(false) }
    var phoneLinkBusy by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scannedUid = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true
            statusMessage = ""
            guardianLinkError = false
            try {
                GuardianManager.linkAsGuardian(scannedUid)
                isGuardianLinked = true
                statusMessage = "You are now a guardian! You will receive alerts if they install a risky app."
            } catch (e: Exception) {
                guardianLinkError = true
                statusMessage = "Failed to link: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val uid = GuardianManager.getOrCreateUserId()
            userId = uid
            qrBitmap = generateQrBitmap(uid, 512)
            GuardianManager.refreshFcmToken(context)
        } catch (e: Exception) {
            guardianLinkError = true
            statusMessage = "Initialization failed: ${e.message}"
        } finally {
            isLoading = false
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
                text = "GUARDIAN PROTECTION",
                color = guardianBlue,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Share your QR or your phone number so someone\ncan receive alerts for you",
                color = textSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            GuardianPhoneStore.getMyNormalizedPhone(context)?.let { num ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Your number: $num",
                    color = guardianBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // My QR Code card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "YOUR QR CODE",
                    color = textSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Let your guardian scan this",
                    color = Color(0xFF3A4050),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(20.dp))

                if (isLoading && qrBitmap == null) {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = guardianBlue, strokeWidth = 2.dp)
                    }
                } else if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(10.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "Your Guardian QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                userId?.let { uid ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = uid.take(20) + "…",
                        color = Color(0xFF3A4050),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Add guardian by phone (you = protected, they = guardian)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ADD GUARDIAN BY PHONE",
                    color = textSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Enter your guardian’s mobile number (same digits they entered at signup).\nThey will get your risk alerts.",
                    color = textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = guardianPhoneInput,
                    onValueChange = { guardianPhoneInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Guardian’s number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = guardianBlue,
                        unfocusedBorderColor = textSecondary,
                        focusedLabelColor = guardianBlue,
                        cursorColor = guardianBlue,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                if (phoneLinkMessage.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = phoneLinkMessage,
                        color = if (phoneLinkError) Color(0xFFFF6D00) else accent,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        phoneLinkMessage = ""
                        phoneLinkError = false
                        scope.launch {
                            phoneLinkBusy = true
                            try {
                                GuardianManager.linkGuardianByPhoneNumber(context, guardianPhoneInput)
                                phoneLinkMessage = "They are now your guardian and will get your alerts."
                                phoneLinkError = false
                            } catch (e: Exception) {
                                phoneLinkError = true
                                phoneLinkMessage = e.message ?: "Could not link"
                            } finally {
                                phoneLinkBusy = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !phoneLinkBusy && guardianPhoneInput.any { it.isDigit() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color(0xFF0D0F14)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (phoneLinkBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFF0D0F14),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Add guardian", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Become a Guardian card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "BECOME A GUARDIAN",
                    color = textSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Scan someone's QR code to protect them\nand receive their risk alerts",
                    color = textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))

                if (isGuardianLinked) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Guardian link active",
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan the QR code of the person you want to protect")
                            setBeepEnabled(false)
                            setBarcodeImageEnabled(false)
                        }
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = guardianBlue,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                ) {
                    Text(
                        text = if (isGuardianLinked) "Scan Another QR Code" else "Scan QR Code",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Status message
        if (statusMessage.isNotEmpty()) {
            item {
                Text(
                    text = statusMessage,
                    color = if (guardianLinkError) Color(0xFFFF6D00) else accent,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // How it works card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardBg)
                    .padding(20.dp)
            ) {
                Text(
                    text = "HOW IT WORKS",
                    color = guardianBlue,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    "You enter your phone once at startup (not verified)",
                    "Share your QR or phone number with someone you trust",
                    "They scan your QR or enter your number under Add guardian by phone",
                    "When you install a risky app or a call trigger fires, they get alerted",
                    "You can scan someone else's QR to be their guardian too"
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

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    } catch (e: WriterException) {
        null
    }
}
