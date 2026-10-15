# sherpa-onnx is called from native code through JNI; keep it whole.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.serialization: keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.nudge.**$$serializer { *; }
-keepclassmembers class app.nudge.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.nudge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
