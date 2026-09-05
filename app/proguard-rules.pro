# —— kotlinx.serialization（官方建议 + 双保险） ——
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.paperlens.app.**$$serializer { *; }
-keepclassmembers class com.paperlens.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.paperlens.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# —— OkHttp / Retrofit（三方自身已带 consumer rules，这里补充常见 dontwarn） ——
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# —— XmlPullParser（部分 ROM 的 runtime 缺 API 文档类，防御性保留） ——
-dontwarn org.xmlpull.**

# —— Miuix / Haze / Coil 未上报混淆问题，如遇 keep 需求在此追加 ——
