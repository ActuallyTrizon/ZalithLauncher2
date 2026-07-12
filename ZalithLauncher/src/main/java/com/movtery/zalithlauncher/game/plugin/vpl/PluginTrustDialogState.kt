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

import com.movtery.zalithlauncher.ui.AndroidStringText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PluginTrustDialogState {

    enum class Severity { INFO, WARNING, ERROR }

    enum class DialogAction { TRUST, CANCEL }

    sealed class DialogRequest {
        abstract val title: AndroidStringText
        abstract val summary: AndroidStringText?
        abstract val message: AndroidStringText?
        abstract val generalDetails: AndroidStringText?
        abstract val technicalDetails: AndroidStringText?
        abstract val deferred: CompletableDeferred<DialogAction>

        data class AuthorTrust(
            override val title: AndroidStringText,
            override val summary: AndroidStringText?,
            override val message: AndroidStringText?,
            override val generalDetails: AndroidStringText?,
            override val technicalDetails: AndroidStringText?,
            val severity: Severity,
            override val deferred: CompletableDeferred<DialogAction> = CompletableDeferred()
        ) : DialogRequest()

        data class KeyTrust(
            override val title: AndroidStringText,
            override val summary: AndroidStringText?,
            override val message: AndroidStringText?,
            override val generalDetails: AndroidStringText?,
            override val technicalDetails: AndroidStringText?,
            val cooldownSeconds: Int,
            override val deferred: CompletableDeferred<DialogAction> = CompletableDeferred()
        ) : DialogRequest()

        data class Error(
            override val title: AndroidStringText,
            override val summary: AndroidStringText?,
            override val message: AndroidStringText?,
            override val generalDetails: AndroidStringText?,
            override val technicalDetails: AndroidStringText?,
            override val deferred: CompletableDeferred<DialogAction> = CompletableDeferred()
        ) : DialogRequest()
    }

    private val _currentRequest = MutableStateFlow<DialogRequest?>(null)
    val currentRequest: StateFlow<DialogRequest?> = _currentRequest.asStateFlow()

    private fun setCurrentRequest(request: DialogRequest?) {
        _currentRequest.value = request
    }

    suspend fun showAuthorTrust(
        title: AndroidStringText,
        summary: AndroidStringText?,
        message: AndroidStringText?,
        generalDetails: AndroidStringText?,
        technicalDetails: AndroidStringText?,
        severity: Severity
    ): DialogAction {
        val request = DialogRequest.AuthorTrust(
            title = title,
            summary = summary,
            message = message,
            generalDetails = generalDetails,
            technicalDetails = technicalDetails,
            severity = severity
        )
        setCurrentRequest(request)
        return try {
            request.deferred.await()
        } finally {
            setCurrentRequest(null)
        }
    }

    suspend fun showKeyTrust(
        title: AndroidStringText,
        summary: AndroidStringText?,
        message: AndroidStringText?,
        generalDetails: AndroidStringText?,
        technicalDetails: AndroidStringText?,
        cooldownSeconds: Int
    ): DialogAction {
        val request = DialogRequest.KeyTrust(
            title = title,
            summary = summary,
            message = message,
            generalDetails = generalDetails,
            technicalDetails = technicalDetails,
            cooldownSeconds = cooldownSeconds
        )
        setCurrentRequest(request)
        return try {
            request.deferred.await()
        } finally {
            setCurrentRequest(null)
        }
    }

    suspend fun showError(
        title: AndroidStringText,
        summary: AndroidStringText?,
        message: AndroidStringText?,
        generalDetails: AndroidStringText?,
        technicalDetails: AndroidStringText?
    ): DialogAction {
        val request = DialogRequest.Error(
            title = title,
            summary = summary,
            message = message,
            generalDetails = generalDetails,
            technicalDetails = technicalDetails
        )
        setCurrentRequest(request)
        return try {
            request.deferred.await()
        } finally {
            setCurrentRequest(null)
        }
    }

    fun dismissCurrent() {
        _currentRequest.value?.let { req ->
            if (!req.deferred.isCompleted) {
                req.deferred.complete(DialogAction.CANCEL)
            }
        }
        setCurrentRequest(null)
    }
}
