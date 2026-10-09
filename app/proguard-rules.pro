# Add project specific ProGuard rules here.

# JGit loads transports, filters and text resources reflectively (ServiceLoader, ResourceBundle).
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**

# The Anthropic SDK (de)serializes its models with Jackson, which needs the original classes,
# constructors and annotations.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.fasterxml.jackson.**
-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn java.beans.**
-dontwarn java.lang.reflect.AnnotatedType
-keep class kotlin.Metadata { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RuntimeVisibleParameterAnnotations
