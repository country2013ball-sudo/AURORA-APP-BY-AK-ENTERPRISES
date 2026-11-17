-keepattributes *Annotation*,InnerClasses,Signature
-keep class rikka.shizuku.** { *; }
-keep class com.akenterprises.auroraengine.graphics.NativeFrameProcessor { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
