package com.scanner.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun overlayPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

@Composable
fun OverlayPermissionScreen(onRecheck: () -> Unit) {
    val context = LocalContext.current

    val bg = Color(0xFF0D0F14)
    val accent = Color(0xFF00E5A0)
    val cardBg = Color(0xFF161A22)
    val textSecondary = Color(0xFF7A8394)
    val orange = Color(0xFFFF9800)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.overlay_permission_title),
            color = orange,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.overlay_permission_body),
            color = textSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.overlay_permission_steps_hint),
                color = Color(0xFF3A4050),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = orange,
                contentColor = Color(0xFF0D0F14)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = stringResource(R.string.overlay_permission_open_settings),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onRecheck,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
            border = BorderStroke(1.dp, accent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = stringResource(R.string.overlay_permission_continue),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}
