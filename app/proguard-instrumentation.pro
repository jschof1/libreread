# R8 cannot see calls from the separately packaged instrumentation APK.

-keep class com.github.libretube.reader.** { *; }
-keep class com.github.libretube.helpers.NavigationHelper { *; }
-keep class com.github.libretube.helpers.BackgroundHelper { *; }
-keep class com.github.libretube.util.PlayingQueue { *; }
-keep class com.github.libretube.parcelable.PlayerData { *; }
-keep class com.github.libretube.api.obj.StreamItem { *; }
-keep class com.github.libretube.services.OnlinePlayerService { *; }
-keep class com.github.libretube.services.OfflinePlayerService { *; }
-keep class com.github.libretube.ui.activities.RouterActivity { *; }
-keep class com.github.libretube.R$* { *; }

# Libraries shared by the app and the test APK are packaged only in the app.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.tracing.** { *; }
