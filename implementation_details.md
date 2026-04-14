# Implementation Details

## Features

### Interception & Delivery

#### OTP Extraction (Default Mode)

The app's primary function is intercepting OTP and verification code messages. When an SMS arrives, a three-phase regex engine analyzes the message body:

- **Phase 1** (high confidence): Structurally unambiguous patterns like `"Your OTP is 123456"`, `"use 890227 to verify"`, and codes in brackets `[123456]`. These run without keyword context.
- **Phase 2** (medium confidence): Patterns that require OTP-related keywords to be present in the message (`otp`, `verification`, `code`, `2fa`, `one-time`, etc.). Word-boundary matching prevents false positives from substrings (e.g. "decode" does not trigger "code").
- **Phase 3** (fallback): Broad numeric matching, heavily gated by keyword context and length heuristics. Prefers 4-6 digit codes.

Messages that don't contain a detected OTP are silently dropped. An optional "Forward all SMS" toggle overrides this to relay every message.

#### Multi-Channel Forwarding

Each intercepted message can be forwarded via two independent channels, each with its own work queue:

- **Email (Gmail)**: Two methods available:
  - **App Password + SMTP** (default, recommended): User enters their Gmail address and a 16-character App Password. The app connects to `smtp.gmail.com:587` with STARTTLS. Credentials stored in AES-256 encrypted SharedPreferences. No Google Cloud Console setup required.
  - **OAuth2 + Gmail REST API** (advanced): Uses Google Sign-In for zero-credential storage. Requires Cloud Console project setup. Messages sent as RFC 2822 MIME via HTTP POST with proper UTF-8/base64 encoding.
- **SMS**: Forwards the extracted OTP (or full message) to configured phone numbers via `SmsManager`. Supports dual-SIM devices with a user-selectable SIM card.

Both channels can be independently enabled/disabled. Email forwarding requires network connectivity (enforced via WorkManager constraints); SMS forwarding has no network constraint and fires immediately.

### Configuration

#### Dual SIM Support

On devices with multiple SIM cards, the app detects available subscriptions via `SubscriptionManager` and presents a dropdown to choose which SIM sends outgoing forwarded SMS. The selection persists across restarts. The dropdown is only visible when SMS forwarding is enabled.

#### Recipient Management

- **Email recipients**: Validated against `Patterns.EMAIL_ADDRESS`. Stored as a JSON array in AES-256 encrypted SharedPreferences.
- **SMS recipients**: Validated against E.164-like format (7-15 digits, optional `+` prefix). Same encrypted storage.
- Add/remove via Material Design dialogs with proper input types (email keyboard, phone keyboard).

### Status & Validation

#### Readiness Checklist

A dynamic status card at the top of the UI checks all prerequisites:

- SMS permissions granted (RECEIVE_SMS, READ_SMS, SEND_SMS)
- Notification permission granted (Android 13+)
- Gmail signed in (if email forwarding is on)
- Email recipients configured (if email forwarding is on)
- SMS recipients configured (if SMS forwarding is on)
- Battery optimization disabled
- Master toggle enabled

Refreshes automatically on every `onResume()`, catching permission revocations that happen while the app is backgrounded.

#### Test Validation

Dedicated "Test Email" and "Test SMS" buttons send a test message to all configured recipients. Useful for verifying the full pipeline before relying on it.

#### Forwarding Log

The last 50 forwarding attempts are stored in encrypted SharedPreferences with status (success/failure), channel, sender, extracted OTP, and timestamp. The UI shows the most recent 10 with relative timestamps and status indicators.

### Reliability

#### Battery & Background Survival

- **OEM Autostart**: Programmatically detects the device manufacturer (Xiaomi, Oppo, Vivo, Huawei, Samsung) and launches the exact OEM-specific autostart/battery settings activity.
- **Battery Optimization**: One-tap to request unrestricted battery usage via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Direct Boot**: `BootReceiver` is `directBootAware`, so the app's process starts after reboot even before the user unlocks the device. Includes `QUICKBOOT_POWERON` for HTC devices.

#### Reset

A "Reset All Settings" button clears all preferences, removes all recipients, wipes the forwarding log, signs out of Gmail, and returns every toggle to its default state. Requires confirmation dialog.

---

## Setup

### Prerequisites

- Android device running API 26+ (Android 8.0 Oreo or later)
- Google Play Services installed (for Gmail OAuth2)
- Sideload-only (uses `SEND_SMS` which Google Play restricts)

### Email Setup

#### Option A: App Password (Recommended -- 2 minutes)

1. On your Google account, enable **2-Step Verification** at [myaccount.google.com/security](https://myaccount.google.com/security)
2. Go to [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
3. Generate an App Password (select "Other", name it "TextSling")
4. In the app, tap **Configure App Password** and enter your Gmail address + the 16-character password
5. Done.

#### Option B: OAuth2 (Advanced -- requires Google Cloud Console)

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Create a project, enable the **Gmail API**
3. Create an **OAuth 2.0 Client ID** (type: Android) with:
   - Package name: `com.example.smsforwarder`
   - SHA-1: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
4. Replace `YOUR_CLIENT_ID.apps.googleusercontent.com` in `GmailHelper.kt`
5. In the app, switch to "OAuth2 (Advanced)" in the Email Account card and sign in

### Building

1. Open `customSMS2Send/` in Android Studio
2. Android Studio will prompt to generate Gradle wrapper files -- accept
3. Sync Gradle, build, and run on a device (not emulator -- SMS requires a real modem)

### First Launch

1. Enable the master toggle -- the app will request SMS permissions
2. Configure email: tap **Configure App Password** (or switch to OAuth2 for advanced setup)
3. Add at least one recipient (email and/or phone)
4. Disable battery optimization and configure OEM autostart
5. Use the Test buttons to verify the pipeline
6. Check the readiness card -- all items should be green

---

## Technical Architecture

### Core Pipeline

#### Event-Driven, Zero-Baseline Power

The app draws **zero power when idle**. There are no background services, no polling loops, no wake locks, and no persistent processes. The entire architecture is event-driven:

```
SMS arrives (OS event)
  -> SmsReceiver (BroadcastReceiver, instantiated by OS)
     -> OtpDetector (pure function, ~0.1ms)
        -> WorkManager enqueue (SQLite write, ~1ms)
           -> OS kills process (returns to zero power)

Network available (OS event, email path only)
  -> WorkManager wakes ForwardingWorker
     -> SMTP send (~500ms) or Gmail API POST (~200ms)
     -> Process dies again
```

The cellular radio is activated only for the duration of the HTTP POST (email) or SmsManager call (SMS), then returns to standby. WorkManager's built-in exponential backoff handles transient failures without custom alarm managers or thread sleeping.

#### OTP Detection Engine

Adapted from the [SecureOTP](https://github.com/26JATIN/SecureOTP) open-source project's extraction pipeline. Key adaptations:

- Operates on SMS body text (BroadcastReceiver) rather than notification text (NLS)
- Word-boundary keyword matching via a pre-compiled `Pattern` to prevent substring false positives
- Phase-1 broad pattern `(?:is|are|:)\s*([0-9]{3,12})` moved to Phase-2 (keyword-gated) to avoid matching "price is 999"
- Ignore filters: 13+ digit tracking numbers, years (1900-2099), all-same-digit strings, 1-3 digit numbers

#### WorkManager Strategy

Each forward is split into separate work requests per channel:

- **Email path**: `OneTimeWorkRequest` with `NetworkType.CONNECTED` constraint + `setExpedited`. Guaranteed delivery even if the device is offline when the SMS arrives -- WorkManager persists the job in SQLite and executes when connectivity returns.
- **SMS path**: `OneTimeWorkRequest` with no constraints. `SmsManager` doesn't need network, so the forward fires immediately.

Both paths use `setForeground(getForegroundInfo())` to promote to a foreground service with `remoteMessaging` type, which is exempt from Android 15's strict background FGS launch restrictions.

Retry policy: WorkManager's built-in exponential backoff, up to 5 attempts. After exhaustion, the job returns `Result.failure()` (not silent success) and logs the failure.

### Platform Integration

#### Why BroadcastReceiver over NotificationListenerService

The Gemini research document analyzed both approaches. We chose `BroadcastReceiver` for `SMS_RECEIVED` because:

| Factor | BroadcastReceiver | NotificationListenerService |
|--------|------------------|-----------------------------|
| Android 15 OTP Redaction | Unaffected (direct PDU access) | OTPs redacted by OS |
| Battery | Event-driven, zero baseline | Passive observer, near-zero baseline |
| Permission model | Standard dangerous permissions | Special access (harder UX) |
| Scope | SMS only (focused) | All notifications (over-broad) |
| Future-proofing | Stable API since API 1 | Subject to increasing restrictions |

#### Dual SIM Implementation

Uses `SubscriptionManager.getActiveSubscriptionInfoList()` to detect available SIMs. The selected subscription ID is persisted in `ForwarderPrefs`. Both `ForwardingWorker` and the test SMS path call `SmsManager.getSmsManagerForSubscriptionId(subId)` when a non-default SIM is chosen. Requires `READ_PHONE_STATE` permission (requested once via `ActivityResultContracts`; tracks denial state to avoid re-prompting).

#### Reboot Survival

- `BootReceiver` listens for `BOOT_COMPLETED` and `QUICKBOOT_POWERON` (HTC). It fires after user unlock, when credential-encrypted storage is available, and pokes WorkManager to re-schedule any pending jobs.
- `SmsReceiver` is a static manifest-registered receiver -- the OS instantiates it automatically for `SMS_RECEIVED` broadcasts regardless of app state or lock screen.
- WorkManager persists its job queue in SQLite, so any pending forwards survive reboots.

#### SMS Loop Prevention

If the device's own number is added as an SMS recipient, an infinite forwarding loop would occur (receive -> forward to self -> receive again). The `SmsReceiver` guards against this by comparing the incoming sender's last 10 digits against all configured SMS recipients before forwarding. Matches are silently dropped.

### Safety

#### Security Model

All security uses Android-native platform APIs -- no custom cryptography.

- **OAuth2 tokens** (OAuth2 mode): Never stored by our code. Google Play Services manages the entire lifecycle. We call `GoogleAuthUtil.getToken()` per send; Play Services returns a cached or silently-refreshed token from its own secure storage.
- **SMTP credentials** (App Password mode): Stored in a dedicated `EncryptedSharedPreferences` file (`encrypted_smtp`), separate from recipient data. Same AES-256-GCM encryption with hardware-backed MasterKey.
- **Recipient data (PII)**: Stored in `EncryptedSharedPreferences` using AES-256-GCM values with AES-256-SIV keys, backed by a `MasterKey` in the Android Keystore (hardware-backed on API 23+). On transient Keystore failures, falls back to plain SharedPreferences for that call only (not cached -- next call retries encrypted).
- **Network**: `network_security_config.xml` enforces `cleartextTrafficPermitted="false"`. All Gmail API traffic is TLS-only.
- **Backup**: `android:allowBackup="false"` prevents `adb backup` extraction.
- **Release builds**: R8 minification with ProGuard rules that strip `Log.d/v/i` calls. `Log.e/w` are retained for production diagnostics.

#### Thread Safety

- `RecipientManager`: All mutating methods (`addEmail`, `removeEmail`, `addPhone`, `removePhone`) are `synchronized(this)` to prevent load-modify-save race conditions between the UI thread and background workers.
- `ForwardingLog`: All reads and writes are `synchronized(this)`.
- `EncryptedSharedPreferences` instances are memoized with `@Volatile` + double-checked locking to avoid redundant Keystore hits.
- `MainActivity` uses `lifecycleScope` (auto-cancels on destroy) instead of raw `MainScope()`.

### Design Tradeoffs

| Decision | Tradeoff |
|----------|----------|
| `EncryptedSharedPreferences` over Room | Simpler, no schema migrations, sufficient for small recipient lists. Limits scalability to ~100s of recipients. |
| JSON arrays over SQLite for log | Capped at 50 entries. Avoids Room dependency and database overhead. Would need migration for larger history. |
| `security-crypto:1.1.0-alpha06` (alpha) | Only available version of `EncryptedSharedPreferences`. API is stable in practice; fallback to plain prefs guards against init failures. |
| Dual email methods (SMTP + OAuth2) | App Password + SMTP is the default because it requires zero Cloud Console setup. OAuth2 is offered for users who prefer zero-credential storage. SMTP handshake (~500ms) is slightly longer than HTTP POST (~200ms) but negligible for a few OTPs/day. Both avoid the `javax.mail` dependency -- SMTP uses raw socket with STARTTLS. |
| No persistent foreground notification | Avoids constant notification bar clutter and baseline battery drain. Tradeoff: user has no always-visible indicator that the service is "running" (mitigated by readiness checklist). |
| `SMS_RECEIVED` max priority (2147483647) | Ensures OTP interception before other apps. May delay other SMS apps' receivers on some devices. |
| ProGuard strips `Log.d/v/i` only | `Log.e/w` retained for production crash diagnostics. These may contain PII (sender numbers, recipient emails) in logcat. Acceptable for sideload-only personal use. |

---

## Project Structure

```
customSMS2Send/
├── build.gradle.kts              # Root: AGP 8.2.2 + Kotlin 1.9.22
├── settings.gradle.kts           # Repositories + module include
├── gradle.properties             # AndroidX, parallel builds
└── app/
    ├── build.gradle.kts          # SDK 35, viewBinding, R8, dependencies
    ├── proguard-rules.pro        # Keep rules + Log stripping
    └── src/main/
        ├── AndroidManifest.xml   # Permissions, receivers, FGS type
        ├── java/.../smsforwarder/
        │   ├── ForwarderApplication.kt   # Notification channel
        │   ├── MainActivity.kt           # UI, readiness, SIM, log, reset
        │   ├── SmsReceiver.kt            # SMS_RECEIVED -> WorkManager
        │   ├── BootReceiver.kt           # Direct boot re-arm
        │   ├── ForwardingWorker.kt       # Email + SMS forwarding
        │   ├── OtpDetector.kt            # Three-phase OTP extraction
        │   ├── GmailHelper.kt            # OAuth2 + Gmail REST API
        │   ├── SmtpHelper.kt            # App Password + SMTP (default)
        │   ├── SimManager.kt             # Dual SIM detection
        │   ├── RecipientManager.kt       # Encrypted recipient CRUD
        │   ├── ForwardingLog.kt          # Encrypted forwarding history
        │   └── BatteryOptimizationHelper.kt  # OEM autostart intents
        └── res/
            ├── layout/activity_main.xml  # Material3 card-based UI
            ├── drawable/status_dot.xml   # Gmail status indicator
            ├── values/colors.xml         # Material3 teal-green palette
            ├── values/themes.xml         # Theme.SmsForwarder (M3 Light)
            └── xml/network_security_config.xml  # HTTPS-only
```
