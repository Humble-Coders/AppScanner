package com.scanner.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppShieldResponse(
    val status: String,
    val riskScore: Int,
    val riskLevel: String,
    val primaryReason: String?,
    val secondaryFlags: List<String>,
    val matchedEntity: String?
)

object AppShieldApi {

    private const val TAG = "AppShield"
    private const val API_BASE_URL = "https://api.humblesolutions.in"
    // Backend returns 404 "Cannot POST /" if we POST the domain root.
    private const val VERIFY_PATH = "/api/v1/verify-app"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    fun extractCertificateSha256(context: Context, packageName: String): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = context.packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = pkgInfo.signingInfo
                if (signingInfo == null) {
                    null
                } else if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNATURES
                ).signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(signatures[0].toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "extractCertificateSha256 failed for $packageName", e)
            null
        }
    }

    fun extractPermissions(context: Context, packageName: String): List<String> {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(
                packageName, PackageManager.GET_PERMISSIONS
            )
            pkgInfo.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "extractPermissions failed for $packageName", e)
            emptyList()
        }
    }

    fun extractInstallerPackage(context: Context, packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractInstallerPackage failed for $packageName", e)
            null
        }
    }

    fun verifyApp(
        packageName: String,
        certificateSha256: String?,
        installerPackage: String?,
        requestedPermissions: List<String>
    ): AppShieldResponse? {
        return try {
            val url = API_BASE_URL.trimEnd('/') + VERIFY_PATH
            val body = JSONObject().apply {
                put("package_name", packageName)
                if (certificateSha256 != null) put("certificate_sha256", certificateSha256)
                if (installerPackage != null) put("installer_package", installerPackage)
                if (requestedPermissions.isNotEmpty()) {
                    put("requested_permissions", JSONArray(requestedPermissions))
                }
            }

            Log.d(TAG, "Request: $body")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "HTTP $code: $err")
                return null
            }

            Log.d(TAG, "Response ($code): $text")

            val json = JSONObject(text)
            val flags = mutableListOf<String>()
            json.optJSONArray("secondary_flags")?.let { arr ->
                for (i in 0 until arr.length()) flags.add(arr.getString(i))
            }

            AppShieldResponse(
                status = json.optString("status", "unknown"),
                riskScore = json.optInt("risk_score", -1),
                riskLevel = json.optString("risk_level", "UNKNOWN"),
                primaryReason = if (json.isNull("primary_reason")) null else json.optString("primary_reason"),
                secondaryFlags = flags,
                matchedEntity = if (json.isNull("matched_entity")) null else json.optString("matched_entity")
            )
        } catch (e: Exception) {
            Log.e(TAG, "verifyApp failed for $packageName", e)
            null
        }
    }
}
