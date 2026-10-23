# ============================================================================
# ProGuard / R8 rules for T-Emulator
# ============================================================================

# --- JNI Native Bridge ---
# Keep the EmulatorNative object and all its native methods.
# R8 must not rename or remove this class since the C++ JNI bridge
# references it by fully-qualified name (Java_com_temulator_app_EmulatorNative_*).
-keep class com.temulator.app.EmulatorNative {
    native <methods>;
    *;
}

# --- View Binding ---
# Keep generated ViewBinding classes (used by viewBinding = true).
-keep class com.temulator.app.databinding.** { *; }

# --- Kotlin Metadata & Reflection Attributes ---
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# --- General ---
# Keep source file names and line numbers for readable stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Room Database ---
-keep class com.temulator.app.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase

# --- Google Play In-App Billing ---
-keep class com.android.billingclient.api.** { *; }

# --- Credential Manager & Google Auth ---
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.firebase.auth.** { *; }
