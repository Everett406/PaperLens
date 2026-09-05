// 根构建脚本：仅声明插件版本，各模块自行应用。
// 注：AGP 9.0 起内置 Kotlin 支持，org.jetbrains.kotlin.android 已移除。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
