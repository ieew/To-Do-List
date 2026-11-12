import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * 三端一致性门禁：测试向量从仓库根 `shared/testvectors` 复制，禁止手抄；
 * 复制后校验内容哈希与源文件一致。
 */
val sharedTestVectors = rootProject.layout.projectDirectory.dir("../shared/testvectors")
val testVectorsTarget = layout.projectDirectory.dir("src/test/resources/testvectors")

val syncTestVectors = tasks.register<Copy>("syncTestVectors") {
    from(sharedTestVectors) {
        include("*.json")
    }
    into(testVectorsTarget)
    filteringCharset = "UTF-8"
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(syncTestVectors)
}

val verifyTestVectors = tasks.register("verifyTestVectors") {
    dependsOn(syncTestVectors)
    doLast {
        val source = sharedTestVectors.asFile
        val target = testVectorsTarget.asFile
        val names = source.listFiles { file -> file.extension == "json" }.orEmpty().map { it.name }
        check(names.isNotEmpty()) { "shared/testvectors 下没有找到测试向量" }
        names.forEach { name ->
            fun digest(bytes: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val expected = digest(source.resolve(name).readBytes())
            val actual = digest(target.resolve(name).readBytes())
            check(expected == actual) { "测试向量 $name 与仓库根文件不一致（禁止手抄）" }
        }
    }
}

tasks.named("test") {
    dependsOn(verifyTestVectors)
}
