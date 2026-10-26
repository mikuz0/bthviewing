# Правила ProGuard/R8 для BTHome Viewer

# Сохраняем все классы приложения
-keep class com.example.bthviewing.** { *; }

# Критично для androidx.startup (инициализация при старте)
-keep class androidx.startup.InitializationProvider { *; }
-keep class * extends androidx.startup.Initializer { *; }

# Kotlin — чтобы R8 не ломал структуру внутренних классов
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata <fields>;
}
-keep class kotlin.collections.** { *; }

# Compose — R8 может ломать внутренние классы, особенно аннотации
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-dontwarn androidx.compose.**

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# BLE (на случай рефлексии в стеке)
-keep class android.bluetooth.** { *; }
