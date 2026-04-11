# App Documentation

## Overview

This Android app provides:

- **App Install Scanner**: Monitors newly installed apps, verifies them via a remote “AppShield” API, and warns the user if the app looks risky.
- **Guardian Protection**: Lets a trusted contact (“guardian”) receive **real-time alerts** (via Firebase Cloud Messaging) when the protected user installs a risky app or when a call-scam trigger happens.
- **WhatsApp Call Recorder + Scam Triggers**: Detects WhatsApp call start/end (via Accessibility events), records audio in a foreground service, and can optionally run speech-to-text to detect scam phrases and trigger on-screen warnings + guardian alerts.

The UI is Jetpack Compose with a bottom navigation of three tabs: **Scanner**, **Guardian**, and **WhatsApp** (`MainActivity.kt` → `AppShell()`).

---

## Key Concepts

### Roles

- **Protected user**: The person whose device is being monitored for risky installs and call-scam triggers.
- **Guardian**: A trusted person who receives alerts about the protected user and can be prompted to call them in emergencies.

### Main Components (at a glance)

- **Accessibility service**: `AppScannerService` (declared in `AndroidManifest.xml` as an accessibility service)
  - Polls installed packages to detect new installs.
  - Listens to accessibility events to detect WhatsApp call state changes.
  - Shows certain overlays (risk overlays, guardian alert overlays, payment scam overlays).
- **Foreground recording service**: `CallRecordingService`
  - Records audio during WhatsApp calls.
  - Optionally runs captions (Google Speech-to-Text) and detects keywords.
  - Emits recordings to the UI via `CallRecordingEventSource`.
- **Firebase**:
  - Anonymous auth for a stable per-device UID: `GuardianManager.getOrCreateUserId()`
  - Firestore for mapping users ↔ guardians and a phone lookup directory
  - FCM for alert delivery (`GuardianFCMService`)
- **Cloud Function**:
  - `functions/index.js` → `notifyGuardians`: reads guardian tokens from Firestore and sends data-only FCM messages.

---

## User-Facing Features

### 1) App Install Scanner (Scanner tab)

**What it does**

- Monitors for new app installs in the background (even when the UI is closed).
- On each new install, it:
  - Extracts the app’s **certificate SHA-256**, installer package, and requested permissions.
  - Calls the remote **AppShield verify API**.
  - Shows a **notification** and, for non-safe apps, an **on-screen warning overlay**.
  - Notifies guardians for non-safe results.

**Where it is implemented**

- UI: `ScannerScreen()` in `MainActivity.kt`
- Background detection + verification:
  - `AppScannerService.checkForNewPackages()`
  - `AppScannerService.handleAppInstalled(...)`
  - `AppShieldApi.verifyApp(...)` (calls `https://appshield-api.onrender.com/api/v1/verify-app`)
  - Emits UI events: `InstallEventSource.tryEmit(...)`

**Risk responses**

- Risk levels are treated as strings from the API (e.g. `SAFE`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
- For `MEDIUM` and above, the overlay shows an **Uninstall** button (see `showWarningOverlay()` in `AppScannerService`).
- If the AppShield API is unavailable, it falls back to a heuristic: **warn when the installer is not Play Store**.

### 2) Guardian Protection (Guardian tab)

**What it does**

- Creates a stable user identity via **Firebase anonymous auth**.
- Lets the protected user:
  - Share their **QR code** (contains their anonymous `uid`)
  - Or link a guardian using the guardian’s **phone number** (digits only)
- Lets a guardian:
  - Scan a protected user’s QR code to start receiving their alerts.
- Plays an emergency sound on incoming guardian alerts and can show a full-screen overlay.

**Where it is implemented**

- UI: `GuardianScreen.kt`
- Identity + linking + token registration: `GuardianManager.kt`
- Local profile storage (name/phone/onboarding gate): `GuardianPhoneStore.kt`

**Linking methods**

- **QR-based guardian linking**
  - Protected user displays QR of `uid`.
  - Guardian scans QR → `GuardianManager.linkAsGuardian(protectedUserId)`
  - Writes Firestore: `guardians/{protectedUserId}/tokens/{guardianUid}` with guardian’s FCM token.

- **Phone-based guardian linking (protected adds guardian)**
  - Protected user enters guardian phone number.
  - App looks up Firestore `phoneDirectory/{normalizedPhone}` to find guardian’s `userId` + `fcmToken`.
  - Writes Firestore: `guardians/{myUid}/tokens/{guardianUid}`.

### 3) WhatsApp Call Recorder (WhatsApp tab)

**What it does**

- Automatically records WhatsApp calls when:
  - Accessibility service is enabled, and
  - Microphone permission is granted
- Saves recordings in the app’s external files directory:
  - `.../Android/data/<appId>/files/WhatsAppRecordings/`
- The WhatsApp tab lists recordings and supports:
  - Play / pause
  - Progress bar
  - Delete

**Where it is implemented**

- UI list + playback: `WhatsAppRecorderScreen.kt` + `RecordingPlaybackManager.kt`
- Call detection: `WhatsAppCallDetector.kt` (driven by `AppScannerService.onAccessibilityEvent`)
- Recording pipeline: `CallRecordingService.kt`, `RecordingEngine.kt`, `AudioEncoder.kt`
- Recording events:
  - Emits: `CallRecordingEventSource.tryEmit(recording)`
  - Loads existing files: `CallRecordingEventSource.loadExistingRecordings(dir)`

### 4) Call Scam Triggers (captions + phrase detection)

**What it does**

While recording a WhatsApp call, if captions are enabled (API key present), the app:

- Streams PCM audio chunks into a captions pipeline.
- Looks for certain keywords/phrases in transcripts:
  - **“digital arrest”** (flexible matching) → triggers **SCAM WARNING**
  - **“humble”** (exact word match) → triggers **SAFETY ALERT**
- When triggered, it:
  - Shows a full-screen overlay in-call (`caption_overlay.xml`)
  - Notifies guardians with alert kind:
    - `CALL_SCAM` for “digital arrest”
    - `CALL_SAFETY` for “humble”

**Where it is implemented**

- Caption pipeline + STT client: `CaptionPipeline.kt`, `SpeechToTextClient.kt`
- Triggering logic: `CallRecordingService.onTranscriptReceived(...)`
- Guardian alert sending: `GuardianManager.notifyGuardians(... alertKind=...)`

### 5) Payment Scam Warning Overlay (Google Pay)

**What it does**

If a scam phrase was detected during a call, the accessibility service watches for a payment app launch:

- If **Google Pay** is opened within a time window after scam detection, it shows a **payment scam warning** overlay (`payment_scam_warning.xml`).

**Where it is implemented**

- In-memory state: `ScamAlertState.kt`
- Trigger + overlay: `AppScannerService.maybeShowScamPaymentWarning(...)` and `showPaymentScamWarningOverlay()`

Important: `ScamAlertState` is intentionally in-memory, so process death clears it.

---

## App Workflows (Step-by-step)

### A) First launch (phone onboarding)

1. App launches `MainActivity` → `AppShell()`.
2. If onboarding not done (`GuardianPhoneStore.isOnboardingDone(context)` is false), app shows `PhoneOnboardingScreen`.
3. User enters **name + phone** (no OTP).
4. App saves profile locally (`SharedPreferences`) and publishes phone mapping:
   - Signs in anonymously to get `uid`
   - Stores FCM token under `users/{uid}`
   - Stores phone lookup under `phoneDirectory/{normalizedPhone}`

### B) Enabling overlays

Some features require “Display over other apps”:

1. `AppShell()` checks `overlayPermissionGranted(context)`.
2. If missing, app shows `OverlayPermissionScreen` and deep-links to:
   - Android “Manage overlay permission” settings for this app.

### C) Scanner workflow (new install)

1. Accessibility service is enabled by the user in system settings.
2. `AppScannerService` starts polling installed packages (`POLL_INTERVAL_MS`).
3. When a new package appears, `handleAppInstalled()` runs:
   - Calls AppShield verification API
   - Emits an `InstallEvent` to UI
   - Shows notification
   - If risk is not `SAFE`, shows warning overlay and notifies guardians.

### D) Guardian alert delivery workflow

1. Protected device triggers `GuardianManager.notifyGuardians(...)`.
2. HTTP POST is sent to Cloud Function `notifyGuardians` (`functions/index.js`).
3. Cloud Function reads Firestore:
   - `guardians/{userId}/tokens/*` → list of guardian FCM tokens
4. Cloud Function sends FCM **data-only** message (`type=GUARDIAN_ALERT`).
5. Guardian phone receives it in `GuardianFCMService.onMessageReceived(...)`:
   - Plays emergency sound
   - Emits alert to `GuardianAlertSource`
6. `AppScannerService` (running) collects `GuardianAlertSource.alerts` and shows a full-screen guardian overlay (`guardian_alert_overlay.xml`).
   - For call alerts, overlay can show **Call them** (opens dial/call).

### E) WhatsApp call recording workflow

1. Accessibility events flow into `AppScannerService.onAccessibilityEvent`.
2. `WhatsAppCallDetector` infers call state transitions.
3. On call start:
   - Service schedules recording start after a delay (`RECORDING_START_DELAY_MS`) to stabilize audio.
   - Starts `CallRecordingService` as foreground service with microphone type.
4. Recording continues until call end is confirmed:
   - Call end is debounced (`CALL_END_DEBOUNCE_MS`) to avoid splitting recordings on brief app switches.
5. On stop:
   - `RecordingEngine` produces PCM
   - `AudioEncoder` encodes to `.m4a`
   - A `CallRecording` is emitted and appears in `WhatsAppRecorderScreen`.

---

## Permissions & Why They’re Needed

Declared in `app/src/main/AndroidManifest.xml`:

- **POST_NOTIFICATIONS**: show scanner/recording notifications.
- **QUERY_ALL_PACKAGES**: used by the scanner to list installed packages for baseline + detection.
- **INTERNET**: AppShield API calls + Firebase + Speech-to-Text (REST).
- **CAMERA**: QR scanning in Guardian tab.
- **RECORD_AUDIO**: WhatsApp call recording + captions.
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_MICROPHONE**: keep recording running reliably while in call.
- **SYSTEM_ALERT_WINDOW**: show overlays (captions, warnings) over other apps (note: accessibility overlays also used in the accessibility service).
- **READ_PHONE_STATE / CALL_PHONE**: guardian overlay can initiate a call when permission is granted (falls back to dialer if not).
- **WAKE_LOCK**: keep CPU awake during recording.
- **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS**: supports long-running background behavior on some devices (user may need to whitelist).
- **RECEIVE_BOOT_COMPLETED**: to restart parts after reboot (see `BootReceiver.kt`).
- **MODIFY_AUDIO_SETTINGS**: used by recording strategies / audio pipeline.

---

## Data Model (Firestore)

This app uses Firestore collections (as implemented in `GuardianManager.kt` and `functions/index.js`):

- `users/{uid}`
  - `fcmToken`: current device token
  - `updatedAt`: server timestamp

- `phoneDirectory/{normalizedPhone}`
  - `userId`: uid who registered that phone
  - `fcmToken`: their current device token
  - `displayName`: optional display name
  - `updatedAt`

- `guardians/{protectedUserId}/tokens/{guardianUid}`
  - `fcmToken`: guardian device token used for alert delivery
  - `guardianUid`
  - `linkedAt`

---

## Backend: Cloud Function `notifyGuardians`

**Location**

- `functions/index.js`

**Purpose**

- Receives an HTTP POST from the app.
- Looks up all guardians’ FCM tokens for a protected user.
- Sends a multicast data-only FCM message with:
  - `type=GUARDIAN_ALERT`
  - `alertKind` = `RISKY_APP` / `CALL_SCAM` / `CALL_SAFETY`
  - plus metadata needed for overlays.
- Removes stale invalid tokens from Firestore if FCM reports them as invalid.

**Expected request body**

- `userId`, `appName`, `packageName`, `riskLevel`
- optional: `riskScore`, `primaryReason`, `alertKind`, `protectedUserPhone`

---

## Setup / Build Notes

### Firebase

- The app depends on:
  - `firebase-auth` (anonymous auth)
  - `firebase-firestore`
  - `firebase-messaging`
- Ensure `google-services.json` is configured for the Android app (not included in this document).

### Captions (Speech-to-Text)

Captions are enabled only if `GOOGLE_CLOUD_SPEECH_API_KEY` is set.

- See `CAPTIONS_SETUP.md`.
- In `app/build.gradle.kts`, the key is wired via:
  - `buildConfigField("String", "GOOGLE_CLOUD_SPEECH_API_KEY", ...)`

### Recording storage location

- Recordings are stored under app-scoped external storage, so they are removed when the app is uninstalled.
- The UI loads from the `WhatsAppRecordings` folder on launch.

---

## Troubleshooting

- **Scanner/Recorder says “Inactive”**
  - Enable the accessibility service in system settings.
  - (WhatsApp) Ensure microphone permission is granted.

- **Overlays don’t show**
  - Grant “Display over other apps” permission (the app will prompt via `OverlayPermissionScreen`).

- **Captions not working**
  - Ensure `GOOGLE_CLOUD_SPEECH_API_KEY` is set (see `CAPTIONS_SETUP.md`).
  - Watch logcat tags: `WARecorder` and caption pipeline logs.

- **Guardian not receiving alerts**
  - Guardian must open the app at least once so an FCM token is registered.
  - Verify Firestore docs exist:
    - `guardians/{protectedUid}/tokens/...`
  - Confirm the Cloud Function URL in `GuardianManager.kt` matches your deployed function endpoint.

---

## Source Entry Points (Code Map)

- **App entry**: `app/src/main/java/com/scanner/app/MainActivity.kt`
- **Accessibility service**: `app/src/main/java/com/scanner/app/Appscannerservice.kt`
- **Call recording service**: `app/src/main/java/com/scanner/app/CallRecordingService.kt`
- **Guardian UI + linking**: `app/src/main/java/com/scanner/app/GuardianScreen.kt`, `GuardianManager.kt`
- **WhatsApp UI**: `app/src/main/java/com/scanner/app/WhatsAppRecorderScreen.kt`
- **Cloud Function**: `functions/index.js`

