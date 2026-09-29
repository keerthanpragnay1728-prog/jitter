package dev.molasses.core.lease

import dev.molasses.core.lease.LeaseExpiryCheck.Fire
import dev.molasses.core.lease.LeaseExpiryCheck.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaseExpiryCheckTest {

    private val ig = "com.instagram.android"

    @Test
    fun `a live lease schedules one check at its remaining time`() {
        assertEquals(Plan.Schedule(ig, 600_000L), LeaseExpiryCheck.plan(ig, 600_000L))
    }

    @Test
    fun `a spent or absent lease schedules nothing`() {
        assertEquals(Plan.None, LeaseExpiryCheck.plan(ig, 0L))
        assertEquals(Plan.None, LeaseExpiryCheck.plan(ig, -5L))
    }

    @Test
    fun `on fire, a spent lease on the open package raises the gate`() {
        assertEquals(Fire.RaiseGate, LeaseExpiryCheck.onFire(ig, openPkg = ig, remainingMs = 0L))
    }

    @Test
    fun `on fire, time still on the lease reschedules for what is left`() {
        assertEquals(Fire.Reschedule(1_500L), LeaseExpiryCheck.onFire(ig, openPkg = ig, remainingMs = 1_500L))
    }

    @Test
    fun `on fire, a package no longer open is left alone`() {
        assertEquals(Fire.Skip("no longer open"), LeaseExpiryCheck.onFire(ig, openPkg = null, remainingMs = 0L))
        assertEquals(Fire.Skip("no longer open"), LeaseExpiryCheck.onFire(ig, openPkg = "com.twitter.android", remainingMs = 0L))
    }

    @Test
    fun `connect re-arms only an open, leased package`() {
        assertEquals(Plan.Schedule(ig, 30_000L), LeaseExpiryCheck.onConnect(ig, 30_000L))
        assertEquals(Plan.None, LeaseExpiryCheck.onConnect(null, 30_000L))
        assertEquals(Plan.None, LeaseExpiryCheck.onConnect(ig, 0L))
    }
}
