# osmdroid looks up some classes reflectively (tile sources, overlays).
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep file names and line numbers so crash reports can be decoded with the saved mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
