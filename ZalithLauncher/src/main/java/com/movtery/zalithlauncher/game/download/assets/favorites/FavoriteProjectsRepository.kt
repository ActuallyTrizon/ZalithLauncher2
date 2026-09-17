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

package com.movtery.zalithlauncher.game.download.assets.favorites

import androidx.compose.runtime.mutableStateMapOf
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformProject
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformSearchData
import com.movtery.zalithlauncher.game.download.assets.platform.getProjectByVersion
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 收藏键，平台与项目Id唯一确定一个收藏项
 */
data class FavoriteKey(
    val platform: Platform,
    val projectId: String
)

/**
 * 收藏项目条目，本地缓存数据与已加载的远端项目数据
 */
data class FavoriteEntry(
    val platform: Platform,
    val project: FavoriteProject,
    val remote: PlatformProject? = null
)

/**
 * 收藏项目仓库
 */
object FavoriteProjectsRepository {
    private const val TAG = "FavoriteProjectsRepository"
    private const val REFRESH_CONCURRENCY = 8

    /** 所有收藏项目，键为 [FavoriteKey] */
    val projects = mutableStateMapOf<FavoriteKey, FavoriteEntry>()

    private var initialized = false

    fun isFavorite(platform: Platform, projectId: String): Boolean {
        return projects.containsKey(FavoriteKey(platform, projectId))
    }

    /**
     * 收藏一个搜索结果项目
     */
    fun favorite(data: PlatformSearchData, classes: PlatformClasses) {
        saveFavorite(data.platform(), data.toFavoriteProject(classes))
    }

    /**
     * 收藏一个远端项目
     */
    fun favorite(project: PlatformProject, defaultClasses: PlatformClasses) {
        saveFavorite(project.platform(), project.toFavoriteProject(defaultClasses))
    }

    private fun saveFavorite(platform: Platform, project: FavoriteProject) {
        favoritesMMKV(platform).encode(project.projectId, project)
        projects[FavoriteKey(platform, project.projectId)] = FavoriteEntry(platform, project)
    }

    fun unfavorite(platform: Platform, projectId: String) {
        favoritesMMKV(platform).remove(projectId)
        projects.remove(FavoriteKey(platform, projectId))
    }

    /**
     * 切换搜索结果项目的收藏状态
     */
    fun toggle(data: PlatformSearchData, classes: PlatformClasses) {
        val platform = data.platform()
        val projectId = data.platformId()
        if (isFavorite(platform, projectId)) {
            unfavorite(platform, projectId)
        } else {
            favorite(data, classes)
        }
    }

    /**
     * 切换远端项目的收藏状态
     */
    fun toggle(project: PlatformProject, defaultClasses: PlatformClasses) {
        val platform = project.platform()
        val projectId = project.platformId()
        if (isFavorite(platform, projectId)) {
            unfavorite(platform, projectId)
        } else {
            favorite(project, defaultClasses)
        }
    }

    /**
     * 从 MMKV 重新加载收藏数据
     */
    fun reload() {
        val latest = readAll()
        if (!initialized) {
            initialized = true
            projects.clear()
            projects.putAll(latest)
        } else {
            latest.forEach { (key, entry) ->
                if (!projects.containsKey(key)) projects[key] = entry
            }
            (projects.keys - latest.keys).forEach(projects::remove)
        }
    }

    /**
     * 并发刷新所有收藏项目的远端数据，逐条更新内存与本地缓存
     */
    fun refreshRemote(scope: CoroutineScope) {
        val pending = projects.values.toList()
        if (pending.isEmpty()) return
        scope.launch {
            val semaphore = Semaphore(REFRESH_CONCURRENCY)
            pending.map { entry ->
                async {
                    semaphore.withPermit { refreshEntry(entry) }
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshEntry(entry: FavoriteEntry) {
        val key = FavoriteKey(entry.platform, entry.project.projectId)
        try {
            val remote = getProjectByVersion(
                projectId = entry.project.projectId,
                platform = entry.platform,
                printLog = false
            )
            //条目可能在刷新过程中被移除，仅更新仍然存在的条目
            projects[key]?.let { current ->
                val merged = mergeCache(current.project, remote)
                projects[key] = current.copy(project = merged, remote = remote)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.warning(TAG, "Failed to refresh favorite project: ${key.platform}/${key.projectId}", e)
        }
    }

    /**
     * 以远端数据校正本地缓存，数据有变化时回写 MMKV，收藏时间保持不变
     */
    private fun mergeCache(cached: FavoriteProject, remote: PlatformProject): FavoriteProject {
        val classes = remote.platformClasses(cached.classes)
        val iconUrl = remote.platformIconUrl()
        val title = remote.platformTitle()
        val description = remote.platformSummary() ?: ""
        val authors = remote.platformAuthors()

        val unchanged = classes == cached.classes &&
                iconUrl == cached.iconUrl &&
                title == cached.title &&
                description == cached.description &&
                authors == cached.authors
        if (unchanged) return cached

        val merged = FavoriteProject(
            projectId = cached.projectId,
            iconUrl = iconUrl,
            title = title,
            description = description,
            authors = authors,
            classes = classes,
            followTime = cached.followTime
        )
        favoritesMMKV(remote.platform()).encode(merged.projectId, merged)
        return merged
    }

    private fun readAll(): Map<FavoriteKey, FavoriteEntry> {
        val result = mutableMapOf<FavoriteKey, FavoriteEntry>()
        Platform.entries.forEach { platform ->
            val mmkv = favoritesMMKV(platform)
            mmkv.allKeys()?.forEach { key ->
                runCatching {
                    mmkv.decodeParcelable(key, FavoriteProject::class.java)
                }.getOrNull()?.let { project ->
                    result[FavoriteKey(platform, project.projectId)] = FavoriteEntry(platform, project)
                }
            }
        }
        return result
    }
}

/**
 * 从搜索结果数据生成收藏缓存
 */
fun PlatformSearchData.toFavoriteProject(classes: PlatformClasses): FavoriteProject = FavoriteProject(
    projectId = platformId(),
    iconUrl = platformIconUrl(),
    title = platformTitle(),
    description = platformDescription(),
    authors = platformAuthors(),
    classes = classes,
    followTime = System.currentTimeMillis()
)

/**
 * 从远端项目数据生成收藏缓存
 */
fun PlatformProject.toFavoriteProject(defaultClasses: PlatformClasses): FavoriteProject = FavoriteProject(
    projectId = platformId(),
    iconUrl = platformIconUrl(),
    title = platformTitle(),
    description = platformSummary() ?: "",
    authors = platformAuthors(),
    classes = platformClasses(defaultClasses),
    followTime = System.currentTimeMillis()
)
