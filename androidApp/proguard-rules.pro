# R8 rules for the release APK. The libraries' own consumer rules (kotlinx-serialization, Ktor, OkHttp, Koin, Coil,
# Tink) do most of the work; these cover what they leave to the app. Install and smoke-test the release APK after
# changing them, or after adding a library (README, release checklist).

# Crash stack traces stay readable with the mapping file (androidApp/build/outputs/mapping/release/mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-serialization: generated serializers are found through each @Serializable class's companion
# (`serializer()`) or, for an `object` such as a route, its INSTANCE. Kept explicitly for our own classes, so the DTOs,
# routes and tabs.json don't depend on the library's rules matching the plugin's output.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-if @kotlinx.serialization.Serializable class dev.marzahn.sankaku.**
-keepclassmembers class dev.marzahn.sankaku.<1> {
    static dev.marzahn.sankaku.<1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class dev.marzahn.sankaku.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class dev.marzahn.sankaku.**
-keep,allowoptimization class dev.marzahn.sankaku.<1>$$serializer { *; }
-if @kotlinx.serialization.Serializable class dev.marzahn.sankaku.** {
    public static ** INSTANCE;
}
-keepclassmembers class dev.marzahn.sankaku.<1> {
    public static dev.marzahn.sankaku.<1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor: nothing to add. The engine is passed explicitly (`OkHttp.create()` in Engine.android.kt), so no engine is found
# through ServiceLoader. `HttpClient()` without an engine would need `-keep class io.ktor.client.engine.okhttp.** { *; }`.

# Koin: nothing to add. Definitions are lambdas that construct their classes directly, and lookups use KClass
# references, which R8 rewrites together. Creating a class by name (`Class.forName`, Koin's reflection extensions)
# would need a keep rule for it.
