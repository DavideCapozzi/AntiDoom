# Room Database Entities
-keep class com.antidoom.app.data.ScrollSession
-keepclassmembers class com.antidoom.app.data.ScrollSession {
    *;
}

# DataStore & Coroutines optimization rules
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.datastore.** { *; }

# Prevent removal of Accessibility Service methods if accessed via reflection
-keep class com.antidoom.app.service.ScrollTrackingService {
    public <init>();
}
