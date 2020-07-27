import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.plugin.*
import java.net.URLClassLoader
import java.io.File

plugins {
	java
	//kotlin("multiplatform") version "1.4-M2"
	//kotlin("multiplatform") version "1.4-M3"
    kotlin("multiplatform") version "1.4.0-rc"
    //kotlin("multiplatform")
}

allprojects {
	repositories {
		mavenCentral()
		maven("https://dl.bintray.com/kotlin/kotlin-eap")
		maven("https://kotlin.bintray.com/kotlinx")
	}
}

val kotlinVersion: String by project
val isKotlinDev = kotlinVersion.contains("-release")
val isKotlinEap = kotlinVersion.contains("-eap") || kotlinVersion.contains("-M") || kotlinVersion.contains("-rc")

allprojects {

	repositories {
		mavenCentral()
		jcenter()
		maven { url = uri("https://plugins.gradle.org/m2/") }
		if (isKotlinDev || isKotlinEap) {
			maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }
			maven { url = uri("https://dl.bintray.com/kotlin/kotlin-dev") }
		}
	}
}

val enableKotlinNative: String by project
val doEnableKotlinNative get() = enableKotlinNative == "true"

val KotlinTarget.isLinux get() = this.name == "linuxX64"
val KotlinTarget.isWin get() = this.name == "mingwX64"
val KotlinTarget.isMacos get() = this.name == "macosX64"
val KotlinTarget.isDesktop get() = isWin || isLinux || isMacos

// Required by RC
kotlin {
    jvm { }
}

subprojects {
	apply(plugin = "kotlin-multiplatform")
	apply(plugin = "maven-publish")

	group = "com.soywiz.korlibs.${project.name}"

	kotlin {
		jvm {
			compilations.all {
				kotlinOptions.jvmTarget = "1.8"
			}
		}
		js(org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType.IR) {
			browser {
				testTask {
					useKarma {
						useChromeHeadless()
					}
				}
			}
		}
		if (doEnableKotlinNative) {
			linuxX64()
            mingwX64()
            macosX64()
		}

		// common
		//    js
		//    concurrent // non-js
		//      jvmAndroid
		//         android
		//         jvm
		//      native
		//         kotlin-native
		//    nonNative: [js, jvmAndroid]
		sourceSets {

            data class PairSourceSet(val main: org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet, val test: org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet) {
                fun get(test: Boolean) = if (test) this.test else this.main
                fun dependsOn(other: PairSourceSet) {
                    main.dependsOn(other.main)
                    test.dependsOn(other.test)
                }
            }

            fun createPairSourceSet(name: String, vararg dependencies: PairSourceSet, block: org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet.(test: Boolean) -> Unit = { }): PairSourceSet {
                val main = maybeCreate("${name}Main").apply { block(false) }
                val test = maybeCreate("${name}Test").apply { block(true) }
                return PairSourceSet(main, test).also {
                    for (dependency in dependencies) {
                        it.dependsOn(dependency)
                    }
                }
            }

            val common = createPairSourceSet("common") { test ->
                dependencies {
                    if (test) {
                        implementation(kotlin("test-common"))
                        implementation(kotlin("test-annotations-common"))
                    } else {
                        implementation(kotlin("stdlib-common"))
                    }
                }
            }

            val concurrent = createPairSourceSet("concurrent", common)
            val nonNativeCommon = createPairSourceSet("nonNativeCommon", common)
            val nonJs = createPairSourceSet("nonJs", common)
            val nonJvm = createPairSourceSet("nonJvm", common)
            val jvmAndroid = createPairSourceSet("jvmAndroid", common)

            // Default source set for JVM-specific sources and dependencies:
            // JVM-specific tests and their dependencies:
            val jvm = createPairSourceSet("jvm", concurrent, nonNativeCommon, nonJs, jvmAndroid) { test ->
                dependencies {
                    if (test) {
                        implementation(kotlin("test-junit"))
                    } else {
                        implementation(kotlin("stdlib-jdk8"))
                    }
                }
            }

            val js = createPairSourceSet("js", common, nonNativeCommon, nonJvm) { test ->
                dependencies {
                    if (test) {
                        implementation(kotlin("test-js"))
                    } else {
                        implementation(kotlin("stdlib-js"))
                    }
                }
            }

			if (doEnableKotlinNative) {
                val nativeCommon = createPairSourceSet("nativeCommon", concurrent)
                val nativeDesktop = createPairSourceSet("nativeDesktop", concurrent)
                val nativePosix = createPairSourceSet("nativePosix", nativeCommon)
                val nativePosixNonApple = createPairSourceSet("nativePosixNonApple", nativePosix)
                val nativePosixApple = createPairSourceSet("nativePosixApple", nativePosix)

                for (target in listOf(linuxX64(), mingwX64(), macosX64())) {
                    val native = createPairSourceSet(target.name, common, nativeCommon, nonJvm, nonJs)
                    if (target.isDesktop) {
                        native.dependsOn(nativeDesktop)
                    }
                    if (target.isLinux || target.isMacos) {
                        native.dependsOn(nativePosix)
                    }
                    if (target.isLinux) {
                        native.dependsOn(nativePosixNonApple)
                    }
                    if (target.isMacos) {
                        native.dependsOn(nativePosixApple)
                    }
                }
			}
		}
	}
}


open class KorgeJavaExec : JavaExec() {
    private val jvmCompilation by lazy { project.kotlin.targets.getByName("jvm").compilations as NamedDomainObjectSet<*> }
    private val mainJvmCompilation by lazy { jvmCompilation.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation }

    @get:InputFiles
    val korgeClassPath by lazy {
        mainJvmCompilation.runtimeDependencyFiles + mainJvmCompilation.compileDependencyFiles + mainJvmCompilation.output.allOutputs + mainJvmCompilation.output.classesDirs
    }

    init {
        systemProperties = (System.getProperties().toMutableMap() as MutableMap<String, Any>) - "java.awt.headless"
        val useZgc = (System.getenv("JVM_USE_ZGC") == "true") || (javaVersion.majorVersion.toIntOrNull() ?: 8) >= 14

        doFirst {
            if (useZgc) {
                println("Using ZGC")
            }
        }

        if (useZgc) {
            jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC")
        }
        project.afterEvaluate {
            //if (firstThread == true && OS.isMac) task.jvmArgs("-XstartOnFirstThread")
            classpath = korgeClassPath
        }
    }
}

fun Project.samples(block: Project.() -> Unit) {
    subprojects {
        if (project.path.startsWith(":samples:")) {
            block()
        }
    }
}

fun getKorgeProcessResourcesTaskName(target: org.jetbrains.kotlin.gradle.plugin.KotlinTarget, compilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>): String =
    "korgeProcessedResources${target.name.capitalize()}${compilation.name.capitalize()}"


samples {

    // @TODO: Move to KorGE plugin
    project.tasks {
        val jvmMainClasses by getting
        val runJvm by creating(KorgeJavaExec::class) {
            group = "run"
            main = "MainKt"
        }
        val runJs by creating {
            group = "run"
            dependsOn("jsBrowserDevelopmentRun")
        }

        //val jsRun by creating { dependsOn("jsBrowserDevelopmentRun") } // Already available
        val jvmRun by creating {
            group = "run"
            dependsOn(runJvm)
        }
        //val run by getting(JavaExec::class)

        //val processResources by getting {
        //	dependsOn(processResourcesKorge)
        //}
    }

    kotlin {
        jvm {
        }
        js {
            browser {
                binaries.executable()
            }
        }
        if (doEnableKotlinNative) {
            for (target in listOf(linuxX64(), mingwX64(), macosX64())) {
                target.apply {
                    binaries {
                        executable {
                            entryPoint("entrypoint.main")
                        }
                    }
                }
            }

            val nativeDesktopFolder = File(project.buildDir, "platforms/nativeDesktop")
            //val nativeDesktopEntryPointSourceSet = kotlin.sourceSets.create("nativeDesktopEntryPoint")
            //nativeDesktopEntryPointSourceSet.kotlin.srcDir(nativeDesktopFolder)
            sourceSets.getByName("nativeCommonMain") { kotlin.srcDir(nativeDesktopFolder) }

            val createEntryPointAdaptorNativeDesktop = tasks.create("createEntryPointAdaptorNativeDesktop") {
                val mainEntrypointFile = File(nativeDesktopFolder, "entrypoint/main.kt")

                outputs.file(mainEntrypointFile)

                // @TODO: Determine the package of the main file
                doLast {
                    mainEntrypointFile.also { it.parentFile.mkdirs() }.writeText("""
                        package entrypoint

                        import kotlinx.coroutines.*
                        import main

                        fun main(args: Array<String>) {
                            runBlocking {
                                main()
                            }
                        }
                    """.trimIndent())
                }
            }

            val nativeDesktopTargets = listOf(linuxX64(), mingwX64(), macosX64())
            val allNativeTargets = nativeDesktopTargets

            //for (target in nativeDesktopTargets) {
                //target.compilations["main"].defaultSourceSet.dependsOn(nativeDesktopEntryPointSourceSet)
            //    target.compilations["main"].defaultSourceSet.kotlin.srcDir(nativeDesktopFolder)
            //}

            for (target in allNativeTargets) {
                for (binary in target.binaries) {
                    val compilation = binary.compilation
                    val copyResourcesTask = tasks.create("copyResources${target.name.capitalize()}${binary.name.capitalize()}", Copy::class) {
                        dependsOn(getKorgeProcessResourcesTaskName(target, compilation))
                        group = "resources"
                        val isDebug = binary.buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG
                        val isTest = binary.outputKind == org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind.TEST
                        val compilation = if (isTest) target.compilations["test"] else target.compilations["main"]
                        //target.compilations.first().allKotlinSourceSets
                        val sourceSet = compilation.defaultSourceSet
                        from(sourceSet.resources)
                        from(sourceSet.dependsOn.map { it.resources })
                        into(binary.outputDirectory)
                    }

                    //compilation.compileKotlinTask.dependsOn(copyResourcesTask)
                    binary.linkTask.dependsOn(copyResourcesTask)
                    binary.compilation.compileKotlinTask.dependsOn(createEntryPointAdaptorNativeDesktop)
                }
            }
        }
    }

    project.tasks {
        val runJvm by getting(KorgeJavaExec::class)
        val jvmMainClasses by getting(Task::class)

        //val prepareResourceProcessingClasses = create("prepareResourceProcessingClasses", Copy::class) {
        //    dependsOn(jvmMainClasses)
        //    afterEvaluate {
        //        from(runJvm.korgeClassPath.toList().map { if (it.extension == "jar") zipTree(it) else it })
        //    }
        //    into(File(project.buildDir, "korgeProcessedResources/classes"))
        //}

        for (target in kotlin.targets) {
            for (compilation in target.compilations) {
                val processedResourcesFolder = File(project.buildDir, "korgeProcessedResources/${target.name}/${compilation.name}")
                compilation.defaultSourceSet.resources.srcDir(processedResourcesFolder)
                val korgeProcessedResources = create(getKorgeProcessResourcesTaskName(target, compilation)) {
                    //dependsOn(prepareResourceProcessingClasses)
                    dependsOn(jvmMainClasses)

                    doLast {
                        processedResourcesFolder.mkdirs()
                        //URLClassLoader(prepareResourceProcessingClasses.outputs.files.toList().map { it.toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader()).use { classLoader ->
                        URLClassLoader(runJvm.korgeClassPath.toList().map { it.toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader()).use { classLoader ->
                            val clazz = classLoader.loadClass("com.soywiz.korge.resources.ResourceProcessorRunner")
                            val folders = compilation.allKotlinSourceSets.flatMap { it.resources.srcDirs }.filter { it != processedResourcesFolder }.map { it.toString() }
                            //println(folders)
                            try {
                                clazz.methods.first { it.name == "run" }.invoke(null, classLoader, folders, processedResourcesFolder.toString(), compilation.name)
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                val re = (e.targetException ?: e)
                                re.printStackTrace()
                                System.err.println(re.toString())
                            }
                        }
                        System.gc()
                    }
                }
                //println(compilation.compileKotlinTask.name)
                //println(compilation.compileKotlinTask.name)
                //compilation.compileKotlinTask.finalizedBy(processResourcesKorge)
                //println(compilation.compileKotlinTask)
                //compilation.compileKotlinTask.dependsOn(processResourcesKorge)
                if (compilation.compileKotlinTask.name != "compileKotlinJvm") {
                    compilation.compileKotlinTask.dependsOn(korgeProcessedResources)
                } else {
                    compilation.compileKotlinTask.finalizedBy(korgeProcessedResources)
                    getByName("runJvm").dependsOn(korgeProcessedResources)

                }
                //println(compilation.output.allOutputs.toList())
                //println("$target - $compilation")

            }
        }
    }
}
