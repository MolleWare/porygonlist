package io.github.molleware.porygonlist.data.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A background window reads the network before the system has let it out of the firewall, and the
 * network then reads as none. It waits a moment for the real answer rather than giving up.
 */
class SettledSnapshotTest {

  private val home =
    NetworkSnapshot(NetworkFingerprint("a3f91c000000"), isWifi = true, isUsable = true, address = "192.168.1.93")

  @Test
  fun `a network that shows up a moment later is the one used`() = runTest {
    var reads = 0
    val snapshot = SyncWindow.settledSnapshot { if (++reads < 4) NetworkSnapshot.Offline else home }

    assertEquals(home, snapshot)
    assertEquals(4, reads)
  }

  @Test
  fun `a usable network is used at once`() = runTest {
    var reads = 0
    SyncWindow.settledSnapshot { reads++; home }

    assertEquals(1, reads)
  }

  @Test
  fun `really offline is offline, after a short wait`() = runTest {
    var reads = 0
    val snapshot = SyncWindow.settledSnapshot { reads++; NetworkSnapshot.Offline }

    assertEquals(NetworkSnapshot.Offline, snapshot)
    assertTrue("it gave the firewall a few seconds, then stopped asking", reads in 2..30)
  }
}
