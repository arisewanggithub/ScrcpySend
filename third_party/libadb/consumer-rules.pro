# spake2-android 2.2.1 has no consumer rules. JNI_OnLoad calls FindClass with this
# literal name and RegisterNatives with the four native method names/signatures.
-keep,allowoptimization class io.github.muntashirakon.crypto.spake2.Spake2Context {
    native <methods>;
}

# SslUtils' reflected OpenSSLProvider constructor and PairingConnectionCtx's
# reflected Conscrypt.exportKeyingMaterial are covered by conscrypt-android's
# own consumer rules. PRNGFixes' legacy Android reflection is below minSdk 30.
