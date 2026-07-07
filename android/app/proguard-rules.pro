# Keep the Neo SDK surface if/when the real jar is added.
-keep class kr.neolab.sdk.** { *; }
-dontwarn kr.neolab.sdk.**

# The strangler-seam classes are loaded reflectively (Class.forName in ServiceLocator) and have NO
# static reference from :app by design — without these keeps, R8 strips/renames them and a minified
# release silently falls back to FakeNeoPenSdk (no pen!) with premium permanently null.
-keep class com.nibhaus.penble.PenBleSdk { *; }
-keep class com.nibhaus.neosdk.NeoSdkAdapter { *; }
-keep class com.nibhaus.premium.PremiumServicesImpl { *; }
-dontwarn com.nibhaus.neosdk.**
-dontwarn com.nibhaus.premium.**

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
