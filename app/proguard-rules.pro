# The original APK was built with R8 optimizations but without obfuscation
# (all class/method names are intact), i.e. -dontobfuscate.
-dontobfuscate
# Ktor server/client reference JVM-only classes that do not exist on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn io.ktor.**
# Tink (security-crypto) compile-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
