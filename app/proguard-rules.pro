# Add project specific ProGuard rules here.

# Keep Hilt-generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel *;
}

# Keep Room schema metadata
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep our serializable channel payloads
-keep,includedescriptorclasses class io.github.lycheeappf.tmm.**$$serializer { *; }
-keepclassmembers class io.github.lycheeappf.tmm.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.lycheeappf.tmm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep WorkManager workers
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# Keep NotificationListenerService
-keep class * extends android.service.notification.NotificationListenerService { *; }
