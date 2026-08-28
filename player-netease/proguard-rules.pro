-keep class io.github.andrealtb.coloroslyrics.provider.netease.** { *; }

-keep class com.highcapable.yukihookapi.** { *; }

-keep class io.github.andrealtb.coloroslyrics.provider.netease.HookEntry

-keep class kotlin.Metadata { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

-dontwarn java.lang.reflect.AnnotatedType

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
