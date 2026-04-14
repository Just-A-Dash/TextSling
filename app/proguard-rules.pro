# Keep WorkManager worker classes
-keep class com.example.smsforwarder.ForwardingWorker { *; }

# Keep Google Sign-In
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Keep BroadcastReceivers referenced in manifest
-keep class com.example.smsforwarder.SmsReceiver { *; }
-keep class com.example.smsforwarder.BootReceiver { *; }

# Keep Application class
-keep class com.example.smsforwarder.ForwarderApplication { *; }

# Strip debug/verbose/info logs in release builds (learned from SecureOTP)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
