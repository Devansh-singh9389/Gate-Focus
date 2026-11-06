# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep view models and model classes if needed
-keep class com.gatefocus.app.models.** { *; }
