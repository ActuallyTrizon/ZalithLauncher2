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
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.plugin.ffmpeg.FFmpegPluginManager
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.utils.logging.Logger
import com.tungsten.verifiedpluginload.api.VerifiedPluginLoad
import com.tungsten.verifiedpluginload.api.VerifiedPluginLoadRegistry
import com.tungsten.verifiedpluginload.model.AuthorType
import com.tungsten.verifiedpluginload.model.PluginLoadAuthorization
import com.tungsten.verifiedpluginload.model.PluginTrustStatus
import com.tungsten.verifiedpluginload.model.PluginVerificationResult
import com.tungsten.verifiedpluginload.model.TrustActionStatus
import com.tungsten.verifiedpluginload.model.TrustSource
import com.tungsten.verifiedpluginload.model.TrustedAuthorInfo
import kotlinx.coroutines.CancellationException

object PluginTrustGate {
    private const val TAG = "VerifiedPluginLoad"
    private const val UNKNOWN_PLUGIN_COOLDOWN_SECONDS = 6

    data class PluginCandidate(
        val packageName: String,
        val type: PluginType
    )

    suspend fun verifyForLaunch(
        context: Context,
        onDialogShow: (AndroidStringText) -> Unit = {}
    ): List<PluginLoadAuthorization> {
        PluginTrustListSync.awaitStartupRefresh()

        val vpl = VerifiedPluginLoadRegistry.get(context)
        val candidates = collectCandidates()

        if (candidates.isEmpty()) return emptyList()

        return verifyNext(vpl, candidates, 0, mutableListOf(), onDialogShow)
    }

    fun resetUnknownPluginCooldown() {
        PluginTrustDialogState.dismissCurrent()
    }

    private fun collectCandidates(): List<PluginCandidate> {
        val candidates = mutableListOf<PluginCandidate>()

        RendererPluginManager.selectedRendererPlugin?.let { plugin ->
            candidates.add(PluginCandidate(plugin.packageName, PluginType.Renderer))
        }

        val driver = DriverPluginManager.getDriver()
        if (driver.packageName.isNotEmpty() && !driver.isLauncher) {
            candidates.add(PluginCandidate(driver.packageName, PluginType.VulkanDriver))
        }

        for (plugin in NativePluginManager.getCheckedPlugins()) {
            candidates.add(PluginCandidate(plugin.packageName, PluginType.NativeLib))
        }

        if (FFmpegPluginManager.isAvailable) {
            candidates.add(
                PluginCandidate("net.kdt.pojavlaunch.ffmpeg", PluginType.FFmpeg)
            )
        }

        return candidates
    }

    private suspend fun verifyNext(
        vpl: VerifiedPluginLoad,
        candidates: List<PluginCandidate>,
        index: Int,
        authorizations: MutableList<PluginLoadAuthorization>,
        onDialogShow: (AndroidStringText) -> Unit
    ): List<PluginLoadAuthorization> {
        if (index >= candidates.size) {
            return authorizations.toList()
        }

        val candidate = candidates[index]
        val result = vpl.inspectInstalledPackage(candidate.packageName)
        logDecision(candidate, result)

        val allowUntrusted = AllSettings.allowUntrustedPlugins.getValue()

        return when (result.status) {
            PluginTrustStatus.TRUSTED -> {
                if (!isExplicitKeyTrustAllowed(result.trustSource, allowUntrusted)) {
                    closeWithFailure(
                        title = androidText(R.string.plugin_trust_title_key_trust_disabled),
                        summary = androidText(R.string.plugin_trust_summary_key_trust_disabled),
                        message = androidText(R.string.plugin_trust_key_trust_disabled_body),
                        generalDetails = generalDetails(candidate, result),
                        technicalDetails = technicalDetails(candidate, result),
                        onDialogShow = onDialogShow
                    )
                    throw CancellationException("Key trust disabled")
                }
                authorizations.add(requireAuthorization(result))
                verifyNext(vpl, candidates, index + 1, authorizations, onDialogShow)
            }

            PluginTrustStatus.PENDING_TRUST -> {
                requestAuthorTrust(
                    vpl = vpl,
                    candidate = candidate,
                    result = result,
                    candidates = candidates,
                    index = index,
                    authorizations = authorizations,
                    onDialogShow = onDialogShow
                )
            }

            PluginTrustStatus.UNTRUSTED -> {
                if (allowUntrusted) {
                    requestKeyTrust(
                        vpl = vpl,
                        candidate = candidate,
                        result = result,
                        candidates = candidates,
                        index = index,
                        authorizations = authorizations,
                        onDialogShow = onDialogShow
                    )
                } else {
                    closeWithFailure(
                        title = androidText(R.string.plugin_trust_title_untrusted),
                        summary = androidText(R.string.plugin_trust_summary_untrusted),
                        message = androidText(R.string.plugin_trust_untrusted_body),
                        generalDetails = generalDetails(candidate, result),
                        technicalDetails = technicalDetails(candidate, result),
                        onDialogShow = onDialogShow
                    )
                    throw CancellationException("Untrusted plugin")
                }
            }

            PluginTrustStatus.BANNED -> {
                val reason = result.keyDescription ?: androidText(R.string.plugin_trust_banned_reason_default)
                closeWithFailure(
                    title = androidText(R.string.plugin_trust_title_banned),
                    summary = androidText(R.string.plugin_trust_summary_banned),
                    message = androidText(
                        R.string.plugin_trust_banned_body,
                        bannedWarningText(result.author)
                    ),
                    generalDetails = generalDetails(candidate, result),
                    technicalDetails = androidText(
                        technicalDetails(candidate, result),
                        androidText(R.string.plugin_trust_banned_technical_details, reason)
                    ),
                    onDialogShow = onDialogShow
                )
                throw CancellationException("Banned plugin")
            }

            PluginTrustStatus.VERIFICATION_FAILED -> {
                closeWithFailure(
                    title = androidText(R.string.plugin_trust_title_failed),
                    summary = androidText(R.string.plugin_trust_summary_failed),
                    message = androidText(R.string.plugin_trust_failed_body),
                    generalDetails = generalDetails(candidate, result),
                    technicalDetails = androidText(
                        technicalDetails(candidate, result),
                        androidText(R.string.plugin_trust_failed_technical_details, result.diagnostic.name)
                    ),
                    onDialogShow = onDialogShow
                )
                throw CancellationException("Verification failed")
            }
        }
    }

    private suspend fun requestAuthorTrust(
        vpl: VerifiedPluginLoad,
        candidate: PluginCandidate,
        result: PluginVerificationResult,
        candidates: List<PluginCandidate>,
        index: Int,
        authorizations: MutableList<PluginLoadAuthorization>,
        onDialogShow: (AndroidStringText) -> Unit
    ): List<PluginLoadAuthorization> {
        val author = result.author
        if (author == null || author.confidence == 0) {
            closeWithFailure(
                title = androidText(R.string.plugin_trust_title_registered),
                summary = androidText(R.string.plugin_trust_summary_registered),
                message = confidenceText(author),
                generalDetails = generalDetails(candidate, result),
                technicalDetails = technicalDetails(candidate, result),
                onDialogShow = onDialogShow
            )
            throw CancellationException("Author not trustable")
        }

        val severity = if (author.confidence == 1) {
            PluginTrustDialogState.Severity.WARNING
        } else {
            PluginTrustDialogState.Severity.INFO
        }

        val title = androidText(R.string.plugin_trust_title_registered)
        onDialogShow(title)
        val action = PluginTrustDialogState.showAuthorTrust(
            title = title,
            summary = androidText(R.string.plugin_trust_summary_registered),
            message = confidenceText(author),
            generalDetails = generalDetails(candidate, result),
            technicalDetails = technicalDetails(candidate, result),
            severity = severity
        )

        if (action == PluginTrustDialogState.DialogAction.CANCEL) {
            throw CancellationException("User cancelled author trust")
        }

        return trustAuthorThenContinue(
            vpl = vpl,
            candidate = candidate,
            result = result,
            candidates = candidates,
            index = index,
            authorizations = authorizations,
            onDialogShow = onDialogShow
        )
    }

    private suspend fun trustAuthorThenContinue(
        vpl: VerifiedPluginLoad,
        candidate: PluginCandidate,
        result: PluginVerificationResult,
        candidates: List<PluginCandidate>,
        index: Int,
        authorizations: MutableList<PluginLoadAuthorization>,
        onDialogShow: (AndroidStringText) -> Unit
    ): List<PluginLoadAuthorization> {
        val author = result.author ?: throw SecurityException("No author info")
        val action = vpl.trustAuthor(author.uuid)
        if (action.status != TrustActionStatus.SUCCESS) {
            throw SecurityException("Could not store author trust: ${action.status}")
        }
        val refreshed = vpl.inspectInstalledPackage(candidate.packageName)
        if (refreshed.status != PluginTrustStatus.TRUSTED) {
            throw SecurityException("Plugin is not trusted after author confirmation: ${refreshed.status}")
        }
        authorizations.add(requireAuthorization(refreshed))
        return verifyNext(vpl, candidates, index + 1, authorizations, onDialogShow)
    }

    private suspend fun requestKeyTrust(
        vpl: VerifiedPluginLoad,
        candidate: PluginCandidate,
        result: PluginVerificationResult,
        candidates: List<PluginCandidate>,
        index: Int,
        authorizations: MutableList<PluginLoadAuthorization>,
        onDialogShow: (AndroidStringText) -> Unit
    ): List<PluginLoadAuthorization> {
        if (result.currentSignatures.isEmpty()) {
            closeWithFailure(
                title = androidText(R.string.plugin_trust_title_failed),
                summary = androidText(R.string.plugin_trust_summary_failed),
                message = androidText(R.string.plugin_trust_failed_body),
                generalDetails = generalDetails(candidate, result),
                technicalDetails = androidText(
                    technicalDetails(candidate, result),
                    androidText(R.string.plugin_trust_failed_technical_details, "APK_UNSIGNED")
                ),
                onDialogShow = onDialogShow
            )
            throw CancellationException("Unsigned APK")
        }

        val title = androidText(R.string.plugin_trust_title_untrusted)
        onDialogShow(title)
        val action = PluginTrustDialogState.showKeyTrust(
            title = title,
            summary = androidText(R.string.plugin_trust_summary_unknown),
            message = androidText(R.string.plugin_trust_unknown_body),
            generalDetails = generalDetails(candidate, result),
            technicalDetails = technicalDetails(candidate, result),
            cooldownSeconds = UNKNOWN_PLUGIN_COOLDOWN_SECONDS
        )

        if (action == PluginTrustDialogState.DialogAction.CANCEL) {
            throw CancellationException("User cancelled key trust")
        }

        return trustKeyThenContinue(
            vpl = vpl,
            candidate = candidate,
            result = result,
            candidates = candidates,
            index = index,
            authorizations = authorizations,
            onDialogShow = onDialogShow
        )
    }

    private suspend fun trustKeyThenContinue(
        vpl: VerifiedPluginLoad,
        candidate: PluginCandidate,
        result: PluginVerificationResult,
        candidates: List<PluginCandidate>,
        index: Int,
        authorizations: MutableList<PluginLoadAuthorization>,
        onDialogShow: (AndroidStringText) -> Unit
    ): List<PluginLoadAuthorization> {
        val keyHash = result.currentSignatures.first().value
        val action = vpl.trustKeyHash(keyHash)
        if (action.status != TrustActionStatus.SUCCESS) {
            throw SecurityException("Could not store key trust: ${action.status}")
        }
        val refreshed = vpl.inspectInstalledPackage(candidate.packageName)
        if (refreshed.status != PluginTrustStatus.TRUSTED) {
            throw SecurityException("Plugin is not trusted after key confirmation: ${refreshed.status}")
        }
        authorizations.add(requireAuthorization(refreshed))
        return verifyNext(vpl, candidates, index + 1, authorizations, onDialogShow)
    }

    private suspend fun closeWithFailure(
        title: AndroidStringText,
        summary: AndroidStringText?,
        message: AndroidStringText?,
        generalDetails: AndroidStringText?,
        technicalDetails: AndroidStringText?,
        onDialogShow: (AndroidStringText) -> Unit
    ) {
        onDialogShow(title)
        PluginTrustDialogState.showError(title, summary, message, generalDetails, technicalDetails)
    }

    fun isExplicitKeyTrustAllowed(trustSource: TrustSource?, allowUntrusted: Boolean): Boolean {
        return trustSource != TrustSource.KEY || allowUntrusted
    }

    private fun requireAuthorization(result: PluginVerificationResult): PluginLoadAuthorization {
        return result.toLoadAuthorization()
            ?: throw SecurityException("Trusted result does not contain a load authorization")
    }

    private fun logDecision(candidate: PluginCandidate, result: PluginVerificationResult) {
        val sha256 = result.matchedSignature?.sha256 ?: "unknown"
        Logger.info(
            TAG,
            "Plugin verification: type=${candidate.type.name}, " +
                    "package=${candidate.packageName}, " +
                    "version=${result.packageInfo.versionName}, " +
                    "sha256=$sha256, " +
                    "status=${result.status}, " +
                    "trustListVersion=${result.trustListVersion}"
        )
    }

    private fun generalDetails(candidate: PluginCandidate, result: PluginVerificationResult): AndroidStringText {
        val label = result.packageInfo.applicationLabel?.takeIf { it.isNotBlank() } ?: candidate.packageName
        val version = result.packageInfo.versionName ?: "-"
        val details = androidText(
            R.string.plugin_trust_general_details,
            label,
            version,
            candidate.type.localeText
        )
        val author = result.author
        return if (author != null) {
            androidText(details, authorDetails(author))
        } else {
            details
        }
    }

    private fun technicalDetails(candidate: PluginCandidate, result: PluginVerificationResult): AndroidStringText {
        val packageName = result.packageInfo.packageName ?: candidate.packageName
        val sha256 = if (result.currentSignatures.isNotEmpty()) result.currentSignatures.first().sha256 else "-"
        return androidText(R.string.plugin_trust_technical_details, packageName, sha256)
    }

    private fun authorDetails(author: TrustedAuthorInfo): AndroidStringText {
        val type = if (author.type == AuthorType.ORG) {
            androidText(R.string.plugin_trust_author_org)
        } else {
            androidText(R.string.plugin_trust_author_person)
        }
        return androidText(
            R.string.plugin_trust_author_details,
            author.name,
            type,
            author.description ?: "-",
            author.web ?: "-"
        )
    }

    private fun bannedWarningText(author: TrustedAuthorInfo?): AndroidStringText {
        return if (author != null && author.confidence == 0) {
            androidText(R.string.plugin_trust_banned_confidence_0)
        } else {
            androidText(R.string.plugin_trust_banned_confidence_known)
        }
    }

    private fun confidenceText(author: TrustedAuthorInfo?): AndroidStringText {
        if (author == null) return androidText(R.string.plugin_trust_confidence_0)
        return when (author.confidence) {
            2 -> androidText(R.string.plugin_trust_confidence_2)
            1 -> androidText(R.string.plugin_trust_confidence_1)
            else -> androidText(R.string.plugin_trust_confidence_0)
        }
    }
}
