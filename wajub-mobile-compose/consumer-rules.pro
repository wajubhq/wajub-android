# Consumer R8/ProGuard rules for wajub-mobile-compose, applied to the app that
# depends on it.
#
# This module's own code uses no reflection (its view model is built by an
# explicit factory). stripe-android, Coil, OkHttp and Jetpack Compose ship
# their own consumer rules. wajub-mobile-core's rules (pusher-java-client) come
# with its jar (META-INF/proguard); they are repeated here for build setups
# that do not read rules from plain jars.
-keep class com.pusher.client.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
