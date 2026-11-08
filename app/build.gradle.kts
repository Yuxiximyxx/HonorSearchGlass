import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自有 release 签名。*.jks 在 .gitignore 里，CI 由 secret HONORSEARCHGLASS_KEYSTORE_B64
// 解码到 app/signing/honorsearchglass.jks；口令与 keystores/.honorsearchglass_pass 一致。
val releaseKeystore = file("signing/honorsearchglass.jks")

android {
    namespace = "io.github.yuxiximyxx.honorsearchglass"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.yuxiximyxx.honorsearchglass"
        // libxposed API 102 要求 minSdk >= 26
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // 调试签名放工作区内：受限沙箱里 ~/.android 不可写会让 validateSigning 失败（NavWake 踩过）
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = releaseKeystore
            storePassword = "30IqHImbgtMeWVRqwPzh"
            keyAlias = "honorsearchglass"
            keyPassword = "30IqHImbgtMeWVRqwPzh"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            // 关闭混淆：LSPosed 靠 java_init.list 的类全名反射加载入口，
            // 混淆改名 = 构建成功但模块静默不生效。
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                println("HonorSearchGlass: 未找到 $releaseKeystore，assembleRelease 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 打包后校验 LSPosed 现代模块声明（META-INF/xposed 三件套 + 作用域含荣耀搜索）。
// 缺 java_init.list 的 APK 在 LSPosed 里根本不显示，构建却照样「成功」——强制校验。
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "校验 APK 内 META-INF/xposed 模块声明完整性"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else {
            emptyList()
        }
        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 下没有 APK，跳过")
            return@doLast
        }
        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 检查 ${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    val javaInit = zip.getEntry("META-INF/xposed/java_init.list")
                    if (javaInit == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 java_init.list —— LSPosed 不会识别为模块")
                    } else {
                        val entries = zip.getInputStream(javaInit).bufferedReader()
                            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (entries.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] java_init.list 为空")
                        } else {
                            entries.forEach { logger.lifecycle("  [OK] 入口类: $it") }
                        }
                    }
                    val prop = zip.getEntry("META-INF/xposed/module.prop")
                    if (prop == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 module.prop")
                    } else {
                        val map = zip.getInputStream(prop).bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                            .associate { line ->
                                val idx = line.indexOf('=')
                                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }
                        logger.lifecycle("  [OK] module.prop: $map")
                        if (!map.containsKey("minApiVersion") || !map.containsKey("targetApiVersion")) {
                            failed = true
                            logger.error("  [FAIL] module.prop 缺少 minApiVersion/targetApiVersion")
                        }
                    }
                    val scope = zip.getEntry("META-INF/xposed/scope.list")
                    if (scope == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 scope.list")
                    } else {
                        val scopes = zip.getInputStream(scope).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        scopes.forEach { logger.lifecycle("  [OK] 作用域: $it") }
                        if (!scopes.contains("com.hihonor.search")) {
                            failed = true
                            logger.error("  [FAIL] scope.list 不含 com.hihonor.search")
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 读取 ${apk.name} 失败: ${error.message}")
            }
        }
        if (failed) {
            throw GradleException("verifyXposedModule 失败：APK 内 LSPosed 声明不完整")
        }
        logger.lifecycle("verifyXposedModule: 全部通过")
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy(verifyXposedModule)
}

dependencies {
    // libxposed：运行期由 LSPosed 注入，只编译期可见
    compileOnly("io.github.libxposed:api:102.0.0")
}
