# KavaRef contains optional JVM type-annotation inspection paths. Android's
# java.lang.reflect surface does not ship AnnotatedType; Provider hooks do not
# invoke those optional paths, so R8 must not treat the reference as fatal.
-dontwarn java.lang.reflect.AnnotatedType
