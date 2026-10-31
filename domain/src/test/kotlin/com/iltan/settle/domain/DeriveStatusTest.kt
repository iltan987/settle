package com.iltan.settle.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeriveStatusTest {

    private val now = 1_000_000L

    private fun status(
        local: LocalState,
        cloud: CloudState,
        inTrash: Boolean = false,
        eligibleAt: Long? = now,
    ) = deriveStatus(local, cloud, inTrash, uploadEligibleAtMillis = eligibleAt, nowMillis = now)

    @Test
    fun `new item waits until the grace period ends, then queues`() {
        status(LocalState.PRESENT, CloudState.NONE, eligibleAt = now + 1) shouldBe
            ItemStatus.WAITING
        status(LocalState.PRESENT, CloudState.NONE, eligibleAt = now) shouldBe ItemStatus.QUEUED
    }

    @Test
    fun `item not chosen for backup says so, unless a copy is already under way or done`() {
        status(LocalState.PRESENT, CloudState.NONE, eligibleAt = null) shouldBe
            ItemStatus.NOT_BACKED_UP
        status(LocalState.PRESENT, CloudState.UPLOADING, eligibleAt = null) shouldBe
            ItemStatus.UPLOADING
        status(LocalState.PRESENT, CloudState.VERIFIED, eligibleAt = null) shouldBe
            ItemStatus.BACKED_UP
    }

    @Test
    fun `upload in progress shows uploading until verified`() {
        status(LocalState.PRESENT, CloudState.UPLOADING) shouldBe ItemStatus.UPLOADING
        status(LocalState.PRESENT, CloudState.UPLOADED) shouldBe ItemStatus.UPLOADING
        status(LocalState.PRESENT, CloudState.VERIFIED) shouldBe ItemStatus.BACKED_UP
    }

    @Test
    fun `evicted item is cloud-only only when the cloud copy is verified`() {
        status(LocalState.EVICTED, CloudState.VERIFIED) shouldBe ItemStatus.CLOUD_ONLY
        CloudState.entries
            .filter { it != CloudState.VERIFIED }
            .forEach { status(LocalState.EVICTED, it) shouldBe ItemStatus.NEEDS_ATTENTION }
    }

    @Test
    fun `suspect cloud copy always needs attention`() {
        LocalState.entries.forEach {
            status(it, CloudState.SUSPECT) shouldBe ItemStatus.NEEDS_ATTENTION
        }
    }

    @Test
    fun `trash wins over every other state`() {
        LocalState.entries.forEach { local ->
            CloudState.entries.forEach { cloud ->
                status(local, cloud, inTrash = true) shouldBe ItemStatus.IN_TRASH
            }
        }
    }

    @Test
    fun `original still on phone but cloud copy gone is queued for backup again`() {
        status(LocalState.PRESENT, CloudState.TRASHED) shouldBe ItemStatus.QUEUED
        status(LocalState.PRESENT, CloudState.DELETED) shouldBe ItemStatus.QUEUED
    }

    @Test
    fun `missing original needs attention`() {
        status(LocalState.MISSING, CloudState.VERIFIED) shouldBe ItemStatus.NEEDS_ATTENTION
    }
}
