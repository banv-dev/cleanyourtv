# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Google OAuth's JSON models are populated reflectively by google-http-client.
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

# TokenResponse/GoogleTokenResponse are instantiated by the JSON parser via no-arg constructors.
# JVM tests do not shrink these models; retain constructors and generic/annotation metadata in release APKs.
-keepattributes Signature,RuntimeVisibleAnnotations
-keep class * extends com.google.api.client.json.GenericJson {
    public <init>();
}
