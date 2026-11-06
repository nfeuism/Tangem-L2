# ─────────────────────────────────────────────────────────────────────────────
# R8 / ProGuard rules for Tangem L2 Recovery
#
# This is a wallet: a missing class at *signing time* is far worse than a slightly
# larger APK. The keeps below are deliberately conservative for the NFC / card
# stack, which is loaded lazily and uses reflection (Moshi, Kotlin reflect, JCA
# providers) that static analysis cannot always see.
#
# Even with these keeps, R8 shrinks the app from ~31.9 MB (debug) to ~5.9 MB by
# removing the unused parts of web3j's transitive tree, the unused Material icon
# set, and unused Compose code paths.
# ─────────────────────────────────────────────────────────────────────────────

# Keep readable stack traces. This project is open source and distributes logs to
# the user for diagnostics, so obfuscation buys nothing and hurts debugging.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ── Tangem SDK ───────────────────────────────────────────────────────────────
# Card commands, APDU/TLV codecs, HD derivation and the NFC session dialog are
# reached through the SDK's own dispatch tables and Kotlin reflection. Keep all
# of it; it is ~1.5k classes and not a meaningful size contributor.
-keep class com.tangem.** { *; }
-dontwarn com.tangem.**

# ── web3j ───────────────────────────────────────────────────────────────────
# We only call org.web3j.crypto.* (Hash, Sign, ECDSASignature, TransactionEncoder,
# TransactionDecoder, Keys, ECKeyPair) and org.web3j.utils.Numeric. Keep that API
# surface intact; R8 is free to drop the rest of the SDK (RxJava / OkHttp /
# Retrofit / Jackson / BouncyCastle / SpongyCastle / jnr live in other packages
# and are stripped).
-keep class org.web3j.** { *; }
-dontwarn org.web3j.**

# ── Application code ─────────────────────────────────────────────────────────
# Compose @Composable functions, ViewModel factories and StateFlow types are
# resolved by the framework. Keep the app package wholesale.
-keep class com.example.tangeml2.** { *; }

# ── QR scanner (zxing-android-embedded) ──────────────────────────────────────
# CaptureActivity is declared in the merged manifest and instantiated by the OS.
-keep class com.journeyapps.** { *; }
-dontwarn com.journeyapps.**

# ── Optional / platform-only references inside third-party jars ───────────────
# These classes are absent from android.jar. They are never reached at runtime on
# Android, but R8 refuses to build without acknowledging them.
-dontwarn org.slf4j.**
-dontwarn sun.security.**
-dontwarn net.i2p.**
-dontwarn java.lang.invoke.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn io.reactivex.**
-dontwarn com.fasterxml.jackson.**
-dontwarn jnr.**
-dontwarn okhttp3.**
-dontwarn retrofit2.**
