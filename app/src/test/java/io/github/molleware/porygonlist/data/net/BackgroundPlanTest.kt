package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ApprovedNetwork
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the quarter-hour sync windows run: only after joining a wifi the owner approved, and only
 * with somebody to sync with. Anything less certain leaves the schedule alone rather than stopping
 * it, because the event that would start it again has already happened.
 */
class BackgroundPlanTest {

  private val home = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))!!
  private val cafe = NetworkFingerprint.of(listOf("10.20.0.1"), listOf("10.20.0.0/16"), listOf("10.20.0.53"))!!

  private val me = DeviceId("HUGOOOOOOOOOOOOO")

  private fun state(paired: Boolean = true) =
    AppState(
      localDevice = me,
      idCounter = 0,
      clockHead = Hlc(0, 0, me),
      lists = emptyList(),
      activeListId = ListId(""),
      online = true,
      networks = listOf(ApprovedNetwork(home, "Home", "", approved = true)),
      peers = if (paired) listOf(TrustedPeer(DeviceId("AVAAAAAAAAAAAAAA"), byteArrayOf(1), "Ava", 0)) else emptyList(),
      conflict = null,
    )

  private fun on(fingerprint: NetworkFingerprint?, usable: Boolean = true, vpn: Boolean = false) =
    NetworkSnapshot(fingerprint = fingerprint, isWifi = true, isUsable = usable, isVpn = vpn)

  @Test
  fun `joining the approved wifi runs the windows`() {
    assertEquals(BackgroundPlan.RUN, backgroundPlan(on(home), state()))
  }

  @Test
  fun `joining any other wifi stops them`() {
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(cafe), state()))
  }

  @Test
  fun `with nobody paired there is nothing to wake for`() {
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(home), state(paired = false)))
  }

  @Test
  fun `a link still settling or hidden behind a vpn leaves the schedule alone`() {
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(home, usable = false), state()))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(null), state()))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(home, vpn = true), state()))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(NetworkSnapshot.Offline, state()))
  }
}
