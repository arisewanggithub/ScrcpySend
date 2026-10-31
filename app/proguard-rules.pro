# C++ exports Java_dev_screencopy_sender_SrtNative_* by declaring class and method name.
# Root the binding as well as its native methods; other members remain shrinkable.
-keep,allowoptimization class dev.screencopy.sender.SrtNative {
    native <methods>;
}

# Shizuku loads this Binder UserService outside the manifest via its class name
# and public no-argument constructor. AIDL calls are ordinary reachable code.
-keep,allowoptimization class dev.screencopy.sender.ShellCaptureService {
    public <init>();
}

# LocalAdb creates a BC provider for SHA256withRSA identity signing and X.509
# conversion. BC constructs these mappings and JCA SPI implementations by name.
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.RSA$Mappings {
    public <init>();
}
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.X509$Mappings {
    public <init>();
}
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.rsa.DigestSignatureSpi$SHA256 {
    public <init>();
}
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory {
    public <init>();
}

# Instrumentation is a separate APK: applying a mapping cannot resurrect an
# inlined/removed identity API. Retain only the existing identity construction
# boundary, not LocalAdb's connection/capture methods or the whole ADB package.
-keep,allowoptimization,includedescriptorclasses class dev.screencopy.sender.LocalAdb {
    public <init>(dev.screencopy.sender.Secrets);
    public java.security.PrivateKey getPrivateKey();
    public java.security.cert.Certificate getCertificate();
}
-keepclassmembers,allowoptimization class dev.screencopy.sender.Secrets {
    public <init>(android.content.Context, java.lang.String, java.lang.String);
}

# Manifest components (including UpdateFileProvider/ShizukuProvider) use AGP's
# generated rules. Conscrypt and Shizuku BinderContainer use their AAR consumer
# rules. The separate pinned scrcpy server and supervisor are assets, not app
# DEX; their app_process/executable entry points are outside R8.

# Conscrypt 2.5.3's upstream consumer rules retain compatibility socket adapters
# but omit their platform-private constructor parameter types. Its
# Platform.wrapSocketFactoryIfNeeded selects these only at SDK<19 / SDK<22,
# below this app's minSdk30. Modern sockets use bundled org.conscrypt types.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
