# Keep the Provider hook entry and implementation classes used through Xposed/YukiHookAPI.
-keep class io.github.proify.lyricon.metrolistprovider.** { *; }
-keep class com.highcapable.yukihookapi.** { *; }

-keep class kotlin.Metadata { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.reflect.AnnotatedType
