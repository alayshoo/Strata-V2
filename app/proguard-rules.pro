# SQLCipher is reached through JNI.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.** { *; }

# PdfBox-Android references optional JPX/JBIG2 decoders and desktop classes we never use.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# OkHttp optional platforms.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
