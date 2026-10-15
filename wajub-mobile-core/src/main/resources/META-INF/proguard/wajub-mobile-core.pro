# Consumer R8/ProGuard rules for wajub-mobile-core, applied to the app that
# depends on it.

# pusher-java-client (realtime status): its Gson-mapped messages are read by
# reflection, and its WebSocket library refers to an SLF4J binder that Android
# does not ship.
-keep class com.pusher.client.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
