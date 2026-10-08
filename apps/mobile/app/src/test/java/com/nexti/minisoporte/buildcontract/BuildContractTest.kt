/*
 * US-011 build-contract unit tests — the JVM twin of
 * scripts/verify-build-contract.sh, so the mechanical assertions run
 * under the regular `check` suite too (CA-02 network pins, CA-04, CA-05).
 *
 * Plain JUnit4 over repository files: no Android runtime, no Robolectric.
 * The gate literals below are assembled from fragments so no tracked file
 * contains an unwrapped pattern that the tracked-tree self-audit would match.
 */
package com.nexti.minisoporte.buildcontract

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildContractTest {
    @Test
    fun versionCodeIsAnIntegerLiteralOfAtLeastOne() {
        val text = readModuleFile("app/build.gradle.kts").readText()
        val match =
            requireNotNull(VERSION_CODE_REGEX.find(text)) {
                "app/build.gradle.kts must declare an integer literal versionCode"
            }
        assertTrue(
            "versionCode must be >= 1 (CA-05), got " + match.groupValues[1],
            match.groupValues[1].toInt() >= 1,
        )
    }

    @Test
    fun versionNameIsSemverishWithInternalSuffix() {
        val text = readModuleFile("app/build.gradle.kts").readText()
        val match =
            requireNotNull(VERSION_NAME_REGEX.find(text)) {
                "app/build.gradle.kts must declare a quoted versionName"
            }
        assertTrue(
            "versionName must match major.minor.patch(-suffix)? (CA-05), got " + match.groupValues[1],
            VERSION_NAME_PATTERN.matches(match.groupValues[1]),
        )
    }

    @Test
    fun buildConfigAndCatalogDeclareNoSigningSecrets() {
        val files =
            listOf(
                readModuleFile("app/build.gradle.kts"),
                readModuleFile("gradle/libs.versions.toml"),
            )
        val offenders =
            files.flatMap { file ->
                forbiddenSigningPatterns()
                    .filter { pattern -> file.readText().contains(pattern) }
                    .map { pattern -> file.name + " -> " + pattern }
            }
        assertTrue(
            "no signing configuration/secrets may be declared anywhere (CA-04), offenders: " + offenders,
            offenders.isEmpty(),
        )
    }

    @Test
    fun versionCatalogPinsTheContractedNetworkStack() {
        val codeLines =
            readModuleFile("gradle/libs.versions.toml")
                .readLines()
                .map { line -> line.substringBefore("#") }
                .filter { codeLine -> codeLine.isNotBlank() }
        val requiredPins =
            mapOf(
                "retrofit" to "3.0.0",
                "okhttp" to "5.5.0",
                "kotlinxSerializationJson" to "1.11.0",
            )
        val missing = mutableListOf<String>()
        for ((key, version) in requiredPins) {
            val pinRegex = Regex("^${Regex.escape(key)}\\s*=\\s*\"${Regex.escape(version)}\"$")
            val pinned = codeLines.any { codeLine -> pinRegex.containsMatchIn(codeLine.trim()) }
            if (!pinned) {
                missing.add(key + " = \"" + version + "\"")
            }
        }
        assertTrue(
            "US-012 network stack must be pinned at the contracted versions (CA-02), missing: " + missing,
            missing.isEmpty(),
        )
    }

    @Test
    fun sourceManifestDeclaresExactlyOneInternetGrantPlusStrippers() {
        val text = readModuleFile("app/src/main/AndroidManifest.xml").readText()
        val elements = MANIFEST_PERMISSION_REGEX.findAll(text).map { it.value }.toList()
        assertTrue(
            "the androidx.core permission stripper must stay declared so the merged manifest carries only INTERNET",
            elements.any { element -> element.contains("tools:node=\"remove\"") },
        )
        // CA-01 / BR-031: exactly ONE real permission request, android.permission.INTERNET,
        // consumed solely by the US-012 network layer.
        val grants = elements.filter { element -> !element.contains("tools:node=\"remove\"") }
        assertTrue(
            "manifest must declare exactly one permission grant (CA-01/BR-031), got: " + grants,
            grants.size == 1,
        )
        assertTrue(
            "the sole permission grant must be android.permission.INTERNET, got: " + grants,
            grants.all { element -> element.contains("android.permission.INTERNET") },
        )
    }

    private fun readModuleFile(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir =
                requireNotNull(dir.parentFile) {
                    "settings.gradle.kts not found above " + System.getProperty("user.dir")
                }
        }
        val file = dir.resolve(relativePath)
        require(file.exists()) { "missing module file: " + file.absolutePath }
        return file
    }

    private fun forbiddenSigningPatterns(): List<String> =
        listOf(
            "signing" + "Configs",
            "key" + "store",
            "key" + ".properties",
            "ANDROID_" + "KEY",
        )

    private companion object {
        val VERSION_CODE_REGEX = Regex("versionCode\\s*=\\s*([0-9]+)")
        val VERSION_NAME_REGEX = Regex("versionName\\s*=\\s*\"([^\"]+)\"")
        val VERSION_NAME_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9_.]+)?$")
        val MANIFEST_PERMISSION_REGEX = Regex("<uses-permission[^>]*>")
    }
}
