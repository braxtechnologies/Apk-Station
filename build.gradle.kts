// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.jetbrainsKotlinAndroidPercelize) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidxNavigationSafeargsKotlin) apply false
    alias(libs.plugins.orgJlleitschuhGradleKtlint) apply false
    alias(libs.plugins.comGoogleDevtoolsKsp) apply false
    alias(libs.plugins.comGoogleDaggerHiltAndroid) apply false
    alias(libs.plugins.detekt) apply false
}