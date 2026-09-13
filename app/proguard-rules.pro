# OpenCV JNI bridge classes are reached from native code.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Tesseract JNI bridge. The artifact is cz.adaptech.tesseract4android, but the
# classes it ships live under the upstream com.googlecode.tesseract package.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**

# Room generated code.
-keep class androidx.room.** { *; }
