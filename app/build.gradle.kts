plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android { namespace = "io.spatia.app"; compileSdk = 35; defaultConfig { minSdk = 29 } }
