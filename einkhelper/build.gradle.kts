// =====================================================================================================================
// Skriba E-ink: app auxiliar que da a caneta rapida do Viwoods a Skriba da Play Store.
// A caneta rapida (ENoteSetting.initWriting) so funciona em apps com targetSdk <= 30; a Skriba da Play Store tem 36.
// A Skriba liga-se ao InkBridgeService desta app e manda-lhe os pedacos da folha a mostrar; esta app fala com o ecra.
// Instala-se a parte (APK enviado a quem pedir), nunca vai para a Play Store (targetSdk 30 nao e aceite la).
// =====================================================================================================================
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Chave de assinatura da versao publicada (GitHub). Fica FORA do repositorio: einkhelper/keystore.properties com
//   storeFile=C:/caminho/skriba-einkhelper.jks
//   storePassword=...
//   keyAlias=einkhelper
//   keyPassword=...
// Sem esse ficheiro, a versao release sai assinada com a chave de debug (so para testes).
val helperKeystoreProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "pt.flick.skriba.einkhelper"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "pt.flick.skriba.einkhelper"
        minSdk = 29
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 30
        versionCode = 2
        versionName = "1.0"
    }

    signingConfigs {
        if (helperKeystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(helperKeystoreProps.getProperty("storeFile"))
                storePassword = helperKeystoreProps.getProperty("storePassword")
                keyAlias = helperKeystoreProps.getProperty("keyAlias")
                keyPassword = helperKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Chave propria (guardada com cuidado: as atualizacoes tem de usar sempre a mesma chave)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk 30 e de proposito (ver acima): nao e um erro neste modulo
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }
}
