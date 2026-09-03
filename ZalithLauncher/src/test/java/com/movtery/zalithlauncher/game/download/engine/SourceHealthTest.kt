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

package com.movtery.zalithlauncher.game.download.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class SourceHealthTest {

    @Test
    fun `host stays viable below trip threshold and trips at it`() {
        val health = SourceHealth(tripThreshold = 3, cooldownNanos = 10_000_000_000L)
        val url = "https://official.example/file"

        repeat(2) { health.recordTimeout(url) }
        assertTrue(health.isViable(url))

        health.recordTimeout(url)
        assertFalse(health.isViable(url))
    }

    @Test
    fun `stale timeouts age out of the window`() {
        val health = SourceHealth(tripThreshold = 2, cooldownNanos = 10_000_000_000L, windowNanos = 120_000_000L)
        val url = "https://official.example/file"

        health.recordTimeout(url)
        //窗口过半后再记一次：前一次尚未出窗，凑满阈值即熔断
        health.recordTimeout(url)
        assertFalse(health.isViable(url))
    }

    @Test
    fun `scattered timeouts below window do not trip`() {
        val health = SourceHealth(tripThreshold = 2, cooldownNanos = 10_000_000_000L, windowNanos = 80_000_000L)
        val url = "https://official.example/file"

        health.recordTimeout(url)
        Thread.sleep(100) //前一次超时已滑出窗口
        health.recordTimeout(url)
        assertTrue(health.isViable(url))
    }

    @Test
    fun `viability returns after cooldown`() {
        val health = SourceHealth(tripThreshold = 2, cooldownNanos = 60_000_000L)
        val url = "https://official.example/file"

        repeat(2) { health.recordTimeout(url) }
        assertFalse(health.isViable(url))
        Thread.sleep(80)
        assertTrue(health.isViable(url))
    }

    @Test
    fun `tripped host is skipped across files sharing the health`() {
        val health = SourceHealth(tripThreshold = 3, cooldownNanos = 10_000_000_000L)

        //第一个文件的三次超时把 official 主机熔断
        val first = SourceSet(listOf("https://official.example/f1", "https://mirror.example/f1"), health)
        val official = first.acquire(false)!!
        repeat(3) { official.recordFailure(SocketTimeoutException("timeout")) }

        //新文件（独立 SourceSet，同一批共享 health）应当直接拿到镜像源
        val second = SourceSet(listOf("https://official.example/f2", "https://mirror.example/f2"), health)
        assertEquals("https://mirror.example/f2", second.acquire(false)!!.url)
    }

    @Test
    fun `all hosts tripped yields nothing and waits for cooldown`() {
        val health = SourceHealth(tripThreshold = 1, cooldownNanos = 10_000_000_000L)
        health.recordTimeout("https://official.example/f")
        health.recordTimeout("https://mirror.example/f")

        val sources = SourceSet(listOf("https://official.example/f", "https://mirror.example/f"), health)
        //熔断期内 acquire 不再派发注定空转的候选人，由调用方等待最早的冷却到期
        assertNull(sources.acquire(false))
        assertTrue(sources.cooldownRemainingMillis() > 0)
        //降级通道无视熔断，保证末路阶段仍会真实尝试
        assertNotNull(sources.acquireDegraded(false))
    }

    @Test
    fun `okio read timeout trips the breaker on http11`() {
        val health = SourceHealth(tripThreshold = 2, cooldownNanos = 600_000_000L)
        val sources = SourceSet(listOf("https://official.example/f", "https://mirror.example/f"), health)
        val official = sources.acquire(false)!!

        //HTTP/1.1 的读超时是 okio 抛出的 InterruptedIOException("timeout")，必须计入熔断
        repeat(2) { official.recordFailure(InterruptedIOException("timeout")) }
        assertFalse(health.isViable("https://official.example/f"))
    }

    @Test
    fun `non timeout failures do not count toward the breaker`() {
        val health = SourceHealth(tripThreshold = 3, cooldownNanos = 10_000_000_000L)
        val sources = SourceSet(listOf("https://official.example/f", "https://mirror.example/f"), health)
        val official = sources.acquire(false)!!

        repeat(5) { official.recordFailure(HttpResultException(500, "synthetic")) }
        assertTrue(health.isViable("https://official.example/f"))
    }

    @Test
    fun `timeout is detected through the cause chain`() {
        assertTrue(SocketTimeoutException("timeout").isTimeoutError())
        assertTrue(IOException("wrapped", SocketTimeoutException("read timed out")).isTimeoutError())
        assertTrue(InterruptedIOException("timeout").isTimeoutError())
        assertTrue(IOException("wrapped", InterruptedIOException("timeout")).isTimeoutError())
        assertFalse(IOException("plain").isTimeoutError())
        assertFalse(HttpResultException(500, "synthetic").isTimeoutError())
    }

    @Test
    fun `only timeouts are treated as failures not cancellations`() {
        assertFalse(SocketTimeoutException("timeout").isInterruptedByCancellation())
        assertFalse(InterruptedIOException("timeout").isInterruptedByCancellation())
        assertTrue(InterruptedIOException("plain interrupt").isInterruptedByCancellation())
    }
}
