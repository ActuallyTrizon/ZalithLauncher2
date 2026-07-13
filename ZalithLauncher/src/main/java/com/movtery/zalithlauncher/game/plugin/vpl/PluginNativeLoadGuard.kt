/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.plugin.vpl

import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.plugin.ffmpeg.FFmpegPluginManager
import com.movtery.zalithlauncher.game.plugin.natives.NativePlugin
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPlugin
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import com.tungsten.verifiedpluginload.api.VerifiedPluginLoad
import com.tungsten.verifiedpluginload.api.VerifiedPluginLoadRegistry
import com.tungsten.verifiedpluginload.model.PluginLoadAuthorization
import com.tungsten.verifiedpluginload.model.PluginTrustStatus
import com.tungsten.verifiedpluginload.model.TrustSource
import java.io.File
import java.io.IOException

object PluginNativeLoadGuard {
    private const val TAG = "VerifiedPluginLoad"

    private val ALLOWED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /*
     * These values are consumed by native code as library paths, loader configuration, or
     * already-loaded handles.  Letting the user-controlled environment editor replace one
     * would bypass the APK/source-dir authorization immediately above the actual dlopen.
     */
    private val PROTECTED_NATIVE_ENVIRONMENT_VARIABLES = setOf(
        "DLOPEN",
        "DRIVER_PATH",
        "FFMPEG_PATH",
        "FCL_NATIVEDIR",
        "LIBGL_DRIVERS_PATH",
        "LIB_MESA_NAME",
        "MESA_LIBRARY",
        "MESA_LOADER_DRIVER_OVERRIDE",
        "MOD_ANDROID_RUNTIME",
        "POJAVEXEC_EGL",
        "POJAV_NATIVEDIR",
        "RENDERER_HANDLE",
        "TMPDIR",
        "VK_ADD_DRIVER_FILES",
        "VK_ADD_LAYER_PATH",
        "VK_DRIVER_FILES",
        "VK_ICD_FILENAMES",
        "VK_LAYER_PATH",
        "VULKAN_PTR"
    )

    private val NATIVE_PATH_ENVIRONMENT_VARIABLES = setOf(
        "DLOPEN",
        "DRIVER_PATH",
        "FFMPEG_PATH",
        "LIBGL_DRIVERS_PATH",
        "LIB_MESA_NAME",
        "MESA_LIBRARY",
        "POJAVEXEC_EGL"
    )

    /**
     * Native loading configuration belongs to the verified launch plan, not the global custom
     * environment setting.  This is public so Launcher can apply the same policy at the last
     * point custom environment variables are merged.
     */
    fun isProtectedNativeEnvironmentVariable(key: String?): Boolean {
        return key != null && (key.startsWith("LD_") || key in PROTECTED_NATIVE_ENVIRONMENT_VARIABLES)
    }

    /** A stored per-certificate trust is only active while the launcher setting permits it. */
    fun isExplicitKeyTrustAllowed(trustSource: TrustSource?, allowUntrustedPlugins: Boolean): Boolean {
        return trustSource != TrustSource.KEY || allowUntrustedPlugins
    }

    fun verify(context: Context, authorizations: List<PluginLoadAuthorization>) {
        val vpl = VerifiedPluginLoadRegistry.get(context)
        val allowUntrusted = AllSettings.allowUntrustedPlugins.getValue()

        val renderer = RendererPluginManager.selectedRendererPlugin
        if (renderer != null) {
            verifyPlugin(
                vpl = vpl,
                authorizations = authorizations,
                type = "Renderer",
                packageName = renderer.packageName,
                expectedNativeDirectory = renderer.path,
                allowUntrusted = allowUntrusted
            )
            verifyRendererLibraries(renderer)
        }

        val driver = DriverPluginManager.getDriver()
        if (!driver.isLauncher) {
            verifyPlugin(
                vpl = vpl,
                authorizations = authorizations,
                type = "Vulkan driver",
                packageName = driver.packageName,
                expectedNativeDirectory = driver.path,
                allowUntrusted = allowUntrusted
            )
        } else if (!samePath(driver.path, context.applicationInfo.nativeLibraryDir)) {
            throw IOException("Vulkan driver path is not owned by the launcher or an installed plugin APK")
        }

        for (plugin in NativePluginManager.getCheckedPlugins()) {
            verifyPlugin(
                vpl = vpl,
                authorizations = authorizations,
                type = "Native plugin",
                packageName = plugin.packageName,
                expectedNativeDirectory = plugin.path,
                allowUntrusted = allowUntrusted
            )
            verifyNativePluginEnvironment(plugin)
        }

        if (FFmpegPluginManager.isAvailable) {
            val libraryPath = FFmpegPluginManager.libraryPath
            if (libraryPath != null) {
                verifyPlugin(vpl, authorizations, "FFmpeg", "net.kdt.pojavlaunch.ffmpeg", libraryPath, allowUntrusted)
                requireLibraryInside(
                    nativeDirectory = libraryPath,
                    relativeLibrary = "libffmpeg.so",
                    label = "FFmpeg plugin"
                )
            }
        }
    }

    private fun verifyPlugin(
        vpl: VerifiedPluginLoad,
        authorizations: List<PluginLoadAuthorization>,
        type: String,
        packageName: String,
        expectedNativeDirectory: String,
        allowUntrusted: Boolean
    ) {
        val result = vpl.inspectInstalledPackage(packageName)
        if (result.status != PluginTrustStatus.TRUSTED) {
            throw IOException("$type $packageName is not trusted: ${result.status}")
        }
        if (!isExplicitKeyTrustAllowed(result.trustSource, allowUntrusted)) {
            throw IOException("$type $packageName is trusted only by an individual signature hash while untrusted plugin loading is disabled")
        }

        val apkPath = result.packageInfo.apkPath
        val nativeDirectory = result.packageInfo.nativeLibraryDirectory
        if (!samePath(nativeDirectory, expectedNativeDirectory)) {
            throw IOException("$type native library directory no longer matches its APK package")
        }

        verifySupportedAbi(nativeDirectory)

        val authorized = authorizations.any { auth ->
            auth.packageName == packageName &&
                    samePath(auth.apkPath, apkPath) &&
                    auth.versionCode == result.packageInfo.versionCode &&
                    auth.currentSignatures == result.currentSignatures.toSet()
        }
        if (!authorized) {
            throw IOException("$type has no matching pre-launch verification authorization")
        }

        val hash = result.matchedSignature?.sha256 ?: "unknown"
        Logger.info(
            TAG,
            "$type trusted: package=$packageName, " +
                    "version=${result.packageInfo.versionName}, " +
                    "sha256=$hash, " +
                    "trustListVersion=${result.trustListVersion}"
        )
    }

    private fun verifyRendererLibraries(renderer: RendererPlugin) {
        requireLibraryInside(renderer.path, renderer.glName, "Renderer OpenGL library")
        if (renderer.eglName.isNotEmpty()) {
            requireLibraryInside(
                nativeDirectory = renderer.path,
                relativeLibrary = stripLeadingSlash(renderer.eglName),
                label = "Renderer EGL library"
            )
        }
        for (dlopenLib in renderer.dlopen) {
            requireLibraryInside(
                nativeDirectory = renderer.path,
                relativeLibrary = dlopenLib,
                label = "Renderer DLOPEN library"
            )
        }
        for ((key, value) in renderer.env) {
            if (key == "LIB_MESA_NAME" || key == "MESA_LIBRARY") {
                requireLibraryInside(
                    nativeDirectory = renderer.path,
                    relativeLibrary = value,
                    label = "Renderer Mesa library"
                )
            }
        }
    }

    private fun verifyNativePluginEnvironment(plugin: NativePlugin) {
        for (entry in plugin.envList) {
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) continue
            val key = entry.substring(0, separator)
            val value = entry.substring(separator + 1)
            if (isNativePathEnvironmentKey(key) && !controlledNativePath(plugin.path, value)) {
                throw IOException("Native plugin environment points outside its installed library directory: $key")
            }
        }
    }

    private fun isNativePathEnvironmentKey(key: String?): Boolean {
        return key != null && (key.startsWith("LD_") || key in NATIVE_PATH_ENVIRONMENT_VARIABLES || key == "PATH")
    }

    private fun verifySupportedAbi(nativeDirectory: String?) {
        if (nativeDirectory == null) return
        val deviceSupportsAllowedAbi = Build.SUPPORTED_ABIS.any { it in ALLOWED_ABIS }
        if (!deviceSupportsAllowedAbi) {
            throw IOException("External native plugins are not supported on this device ABI")
        }
        val normalized = nativeDirectory.replace('\\', '/')
        if (normalized.endsWith("/x86") || normalized.contains("/x86/")) {
            throw IOException("x86 external native plugins are not permitted")
        }
    }

    private fun requireLibraryInside(nativeDirectory: String?, relativeLibrary: String, label: String?) {
        if (relativeLibrary.isBlank() || !relativeLibrary.endsWith(".so")) {
            throw IOException("$label is not a shared-library file")
        }
        val target = File(nativeDirectory, stripLeadingSlash(relativeLibrary)).getCanonicalFile()
        if (!pathInside(nativeDirectory, target.path)) {
            throw IOException("$label escapes the installed native library directory")
        }
        if (!target.isFile()) {
            throw IOException("$label is missing from the installed native library directory")
        }
    }

    private fun pathInside(base: String?, path: String?): Boolean {
        if (base == null || path == null) return false
        val baseFile = File(base).getCanonicalFile()
        val targetFile = File(path).getCanonicalFile()
        return targetFile.toPath().startsWith(baseFile.toPath())
    }

    private fun controlledNativePath(baseDir: String?, value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        for (entry in value.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (entry.isBlank() || !pathInside(baseDir, entry)) return false
        }
        return true
    }

    private fun stripLeadingSlash(name: String): String {
        return if (name.startsWith("/")) name.substring(1) else name
    }

    private fun samePath(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return try {
            File(first).canonicalPath == File(second).canonicalPath
        } catch (_: IOException) {
            false
        }
    }
}
