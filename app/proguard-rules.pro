# The Machine — R8 rules.
#
# The app has no reflection-heavy surface today; these rules exist for the pieces
# that are inherently reflective or crossed by JNI.

# --- Native bridges: JNI looks methods up by name, R8 cannot see the call. ---
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Config objects whose FIELDS are read from C++ by name.
#
# The rule above keeps classes that *have* native methods. It does nothing for a plain
# data class that native code reads with GetFieldID, because nothing about it looks
# native from Kotlin's side. sherpa-onnx is built exactly that way: OfflineTtsConfig is
# an ordinary Kotlin class, and the C++ walks it field by field.
#
# R8 renamed those fields, GetFieldID returned null, and GetObjectField aborted the
# process with "JNI DETECTED ERROR IN APPLICATION: fid == null" the instant the voice
# loaded. Every release build did this; only release, because debug is not minified.
# Twelve identical native crashes on the reference phone, which read as a crash loop
# because the assistant retries the moment it is summoned again.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }

# --- kotlinx.serialization: generated serializers are found reflectively. ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Keep line numbers so stack traces from sideloaded builds stay readable. ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
