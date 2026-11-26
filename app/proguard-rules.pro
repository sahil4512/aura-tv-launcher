# Keep line numbers for readable crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# jcifs-ng (network share backup) creates parts of itself by name; keep it whole.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
# Optional parts of jcifs-ng's dependencies that Android doesn't have (servlets, Kerberos, a logger binding).
-dontwarn javax.servlet.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
# MD4 / AES-CMAC for jcifs-ng: the JCA provider creates them by class name.
-keep class com.auratv.launcher.backup.JcifsCrypto { *; }
-keep class com.auratv.launcher.backup.JcifsCrypto$* { *; }
