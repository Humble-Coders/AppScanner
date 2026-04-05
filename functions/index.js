const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

/**
 * HTTP Cloud Function: notifyGuardians
 *
 * Called by the Android app whenever a risky app is detected.
 * Reads all guardian FCM tokens from Firestore and sends each one
 * a data-only FCM message (so the guardian app can show a custom overlay).
 *
 * Expected POST body (JSON):
 *   userId         – anonymous UID of the protected user
 *   appName        – display name of the installed app
 *   packageName    – package name of the installed app
 *   riskLevel      – LOW | MEDIUM | HIGH | CRITICAL
 *   riskScore      – integer 0–100
 *   primaryReason  – (optional) human-readable risk reason
 *   alertKind      – (optional) RISKY_APP | CALL_SCAM | CALL_SAFETY (default RISKY_APP)
 */
exports.notifyGuardians = onRequest(
  { cors: true, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method not allowed" });
    }

    const {
      userId,
      appName,
      packageName,
      riskLevel,
      riskScore,
      primaryReason,
      alertKind,
    } = req.body;

    if (!userId || !appName || !packageName || !riskLevel) {
      return res.status(400).json({ error: "Missing required fields" });
    }

    const kind =
      alertKind === "CALL_SCAM" || alertKind === "CALL_SAFETY"
        ? alertKind
        : "RISKY_APP";

    const db = getFirestore();
    const tokensSnap = await db
      .collection("guardians")
      .doc(userId)
      .collection("tokens")
      .get();

    if (tokensSnap.empty) {
      return res.json({ sent: 0, message: "No guardians linked" });
    }

    const tokens = tokensSnap.docs
      .map((d) => d.data().fcmToken)
      .filter(Boolean);

    if (tokens.length === 0) {
      return res.json({ sent: 0, message: "No valid FCM tokens" });
    }

    const messaging = getMessaging();
    const multicastMessage = {
      // Data-only message so the app handles display (custom overlay)
      data: {
        type: "GUARDIAN_ALERT",
        alertKind: kind,
        protectedUserUid: userId,
        appName: appName,
        packageName: packageName,
        riskLevel: riskLevel,
        riskScore: String(riskScore ?? 0),
        primaryReason: primaryReason ?? "",
      },
      // High priority ensures delivery even when the device is idle
      android: {
        priority: "high",
      },
      tokens: tokens,
    };

    const response = await messaging.sendEachForMulticast(multicastMessage);

    // Remove stale tokens that are no longer valid
    const staleTokenDocs = [];
    response.responses.forEach((resp, idx) => {
      if (
        !resp.success &&
        (resp.error?.code === "messaging/registration-token-not-registered" ||
          resp.error?.code === "messaging/invalid-registration-token")
      ) {
        staleTokenDocs.push(tokensSnap.docs[idx].ref);
      }
    });

    if (staleTokenDocs.length > 0) {
      const batch = db.batch();
      staleTokenDocs.forEach((ref) => batch.delete(ref));
      await batch.commit();
    }

    return res.json({
      sent: response.successCount,
      failed: response.failureCount,
      staleRemoved: staleTokenDocs.length,
    });
  }
);
