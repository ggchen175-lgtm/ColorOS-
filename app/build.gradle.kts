plugins { id("com.android.application") }
android {
    namespace = "cn.zhe.chaojibeauty"
    compileSdk = 34
    defaultConfig {
        applicationId = "cn.zhe.chaojibeauty"
        minSdk = 33
        targetSdk = 34
        versionCode = 157
        versionName = "0.4.129"
    }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
