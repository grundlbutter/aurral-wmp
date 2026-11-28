-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class org.aurral.android.api.**$$serializer { *; }
-keepclassmembers class org.aurral.android.api.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.slf4j.**
