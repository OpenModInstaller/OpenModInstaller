package com.gestormc.gestor.launcher.core

import com.gestormc.gestor.launcher.OpenLauncher
import com.gestormc.gestor.task.downloadFile
import com.gestormc.gestor.util.plusAssign
import kotlinx.serialization.json.*
import org.apache.commons.lang3.SystemUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks and installs Minecraft's libraries
 */
object LibraryManager {
    // Variants for natives names in different version.json formats
    private val NATIVES_WINDOWS_VARIANTS = listOf("natives_windows", "natives-windows")
    private val NATIVES_LINUX_VARIANTS = listOf("natives_linux", "natives-linux")
    private val NATIVES_OSX_VARIANTS = listOf("natives_macos", "natives_mac", "natives_osx", "natives-osx")

    /**
     * A [MutableList] of all natives
     */
    private val nativeLibraries: MutableList<JsonElement> = mutableListOf()

    /**
     * A [MutableList] of all natives which are not supported on the current user's OS
     */
    private val unsupportedNativeLibraries: MutableList<JsonElement> = mutableListOf()

    /**
     * The path to the root directory of the game (for example AppData/Roaming/.minecraft on Windows)
     */
    private lateinit var gamePath: String

    /**
     * Checks all libraries and installs missing ones.
     *
     * Internal. Can be run via [SetupManager]
     */
    internal fun checkLibraries(
        /**
         * The path to the game folder root
         */
        gamePath: String,
        /**
         * The root [JsonObject] for the version info JSON
         */
        versionInfoObject: JsonObject,
        /**
         * A [JsonArray] of libraries for the game
         */
        librariesArray: JsonArray,
        /**
         * The path to natives of the game
         */
        nativesPath: String
    ) {

        // Empty everything
        nativeLibraries.clear()
        unsupportedNativeLibraries.clear()

        if (versionInfoObject.contains("inheritsFrom")) {
            // Check parent libraries
            checkAndDownload(OpenLauncher.getParentObject(versionInfoObject, gamePath)["libraries"]!!.jsonArray, nativesPath)
        }

        // Save game path for later
        LibraryManager.gamePath = gamePath

        // Check the main libraries array
        checkAndDownload(librariesArray, nativesPath)
    }

    /**
     * Scans and returns all Minecraft libraries currently in place
     */
    internal fun getLibrariesFormatted(
        /**
         * Root game path
         */
        gamePath: String,
        /**
         * [JsonObject] for the Mojang version info JSON
         */
        versionInfoObject: JsonObject): String {

        val librariesArray = versionInfoObject["libraries"]!!.jsonArray
        val builder = StringBuilder()

        // Normal libraries
        librariesArray.forEach { library ->
            val libraryObject = library.jsonObject

            // Get the name and cut it into pieces for making an absolute path for the library
            val name = libraryObject["name"]!!.jsonPrimitive.content

            val cut1: String = name.substring(0, name.lastIndexOf(":")).replace(".", "/").replace(":", "/")
            val cut2: String = name.substring(name.lastIndexOf(":") + 1)
            val cut3: String = name.substring(name.indexOf(":") + 1).replace(":", "-")

            // Join the pieces together into an absolute path
            val libraryPath = "$gamePath/libraries/$cut1/$cut2/$cut3.jar"

            builder += "$libraryPath;"
        }

        for (library in nativeLibraries) {
            if (unsupportedNativeLibraries.contains(library)) continue

            val libraryObject = library.jsonObject
            val name = libraryObject["name"]!!.jsonPrimitive.content

            // Get path
            val cut1: String = name.substring(0, name.lastIndexOf(":")).replace(".", "/").replace(":", "/")
            val cut2: String = name.substring(name.lastIndexOf(":") + 1)
            val cut3: String = name.substring(name.indexOf(":") + 1).replace(":", "-")

            val libraryPathJAR = "$gamePath/libraries/$cut1/$cut2/$cut3-natives-${when {
                SystemUtils.IS_OS_WINDOWS -> "windows"
                SystemUtils.IS_OS_LINUX -> "linux"
                SystemUtils.IS_OS_MAC_OSX -> "macos"
                else -> throw RuntimeException("Java Edition not run on Windows, Linux or Mac OSX")
            }}.jar"

            builder += "$libraryPathJAR;"
        }

        return builder.toString()
    }

    /**
     * Checks a separate [JsonArray] of Minecraft libraries and downloads some/all of them if necessary
     */
    private fun checkAndDownload(
        /**
         * The checked libraries [JsonArray]
         */
        librariesArray: JsonArray,
        /**
         * The path to the natives folder
         */
        nativesFolder: String) {

        for (library in librariesArray) {
            // Extract the JsonObject
            val libraryObject = library.jsonObject

            // Get the name and cut it into pieces for making an absolute path for the library
            val name = libraryObject["name"]!!.jsonPrimitive.content

            val cut1: String = name.substring(0, name.lastIndexOf(":")).replace(".", "/").replace(":", "/")
            val cut2: String = name.substring(name.lastIndexOf(":") + 1)
            val cut3: String = name.substring(name.indexOf(":") + 1).replace(":", "-")

            // Join the pieces together into an absolute path
            val libraryPath = "$gamePath/libraries/$cut1/$cut2/$cut3.jar"
            val libraryFile = File(libraryPath)

            // Separate handling for FabricMC library format
            if (!libraryObject.contains("downloads")) {
                if (!libraryFile.exists()) {
                    val url1 = "https://maven.fabricmc.net" // the base domain
                    val url2 = name.substring(0, name.lastIndexOf(":")).replace(":", "/").replace(".", "/") // the name
                    val url3 = name.substring(name.lastIndexOf(":") + 1, name.lastIndex + 1) // the version
                    val url4 = "${name.split(":")[1]}-$url3.jar"// the filename

                    val url = "$url1/$url2/$url3/$url4"
                    downloadFile(url, libraryPath)
                }
                continue
            }

            if (libraryObject["downloads"]!!.jsonObject.contains("classifiers")) {
                val libraryPathAppended = "$gamePath/libraries/$cut1/$cut2/$cut3-natives-${when {
                    SystemUtils.IS_OS_WINDOWS -> "windows"
                    SystemUtils.IS_OS_LINUX -> "linux"
                    SystemUtils.IS_OS_MAC_OSX -> "macos"
                    else -> throw RuntimeException("Java Edition not run on Windows, Linux or Mac OSX")
                }}.jar"

                nativeLibraries += libraryObject

                if (!File(libraryPathAppended).exists()) {
                    val downloadsObject = libraryObject["downloads"]!!.jsonObject

                    val nativeObjectNames = when {
                        // Compat with multiple format versions
                        SystemUtils.IS_OS_WINDOWS -> NATIVES_WINDOWS_VARIANTS
                        SystemUtils.IS_OS_LINUX -> NATIVES_LINUX_VARIANTS
                        SystemUtils.IS_OS_MAC_OSX -> NATIVES_OSX_VARIANTS
                        else -> throw RuntimeException("App running not on Windows, Linux or MacOS. This is not allowed for Java Edition")
                    }
                    val nativeObjectName = getNativeKeyName(nativeObjectNames, downloadsObject)

                    val nativePath = "$gamePath/libraries/$cut1/$cut2/$cut3-$nativeObjectName.jar"

                    // Some libraries, like Java-ObjC-Bridge do not support natives for some OSs, so check for that
                    if (!downloadsObject["classifiers"]!!.jsonObject.contains(nativeObjectName)) {
                        println("Natives library $cut3 is not supported on the current OS")
                        unsupportedNativeLibraries += libraryObject
                        continue
                    }

                    // Missing native libraries still have to be installed
                    downloadFile(
                        input =
                        // /classifiers
                        downloadsObject["classifiers"]!!
                            // /classifiers/natives_XXX
                            .jsonObject[nativeObjectName]!!
                            // /classifiers/natives_XXX/url
                            .jsonObject["url"]!!.jsonPrimitive.content,
                        output = nativePath
                    )

                    Files.copy(Path.of(nativePath), FileOutputStream("$nativesFolder/$cut3.jar"))
                }
            }

            // Check if the library doesn't exist
            if (!libraryFile.exists()) {
                // Group into natives or missing
                // If has classifiers -> native
                // If has artifact -> regular
                // Else isn't used since some libraries have both classifiers and artifact (they have both regular and native versions)

                val downloadsObject = libraryObject["downloads"]!!.jsonObject

                if (downloadsObject.contains("artifact")) {
                    // Just download the lib
                    downloadFile(
                        input =
                            // /artifact
                            downloadsObject["artifact"]!!
                            // /artifact/url
                            .jsonObject["url"]!!.jsonPrimitive.content,
                        output = libraryPath
                    )
                }
            }
        }
    }

    /**
     * Tries out multiple variant keys and returns the right one
     */
    private fun getNativeKeyName(
        /**
         * All possible variants
         */
        variants: List<String>,
        /**
         * A JSONObject for the downloads
         */
        downloadsObject: JsonObject
    ): String {

        val classifiersObject = downloadsObject["classifiers"]!!.jsonObject

        variants.forEach { variant ->
            if (classifiersObject.contains(variant)) return variant
        }

        return variants[variants.lastIndex]
    }
}