-dontwarn java.lang.reflect.AnnotatedType
# Preserve module/runtime names while allowing R8 to optimize the Compose UI.
-keep class !io.github.andrealtb.coloroslyrics.provider.universal.ui.**,io.github.andrealtb.coloroslyrics.provider.universal.** { *; }
-repackageclasses ''
