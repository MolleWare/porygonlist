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
 * When the quarter-hour sync windows run: only with the switch on, after joining a wifi the owner
 * approved, and with somebody to sync with. Anything less certain about the network leaves the
 * schedule alone rather than stopping it, because the event that would start it again has already
 * happened.
 */
class BackgroundPlanTest {

  private val home = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))!!
  private val cafe = NetworkFingerprint.of(listOf("10.20.0.1"), listOf("10.20.0.0/16"), listOf("10.20.0.53"))!!

  private val me = DeviceId("HUGOOOOOOOOOOOOO")

  private fun state(paired: Boolean = true, backgroundSync: Boolean? = true) =
    AppState(
      localDevice = me,
      idCounter = 0,
      clockHead = Hlc(0, 0, me),
      lists = emptyList(),
      activeListId = ListId(""),
      networks = listOf(ApprovedNetwork(home, "Home", "", approved = true)),
      peers = if (paired) listOf(TrustedPeer(DeviceId("AVAAAAAAAAAAAAAA"), byteArrayOf(1), "Ava", 0)) else emptyList(),
      backgroundSync = backgroundSync,
    )

  private fun on(fingerprint: NetworkFingerprint?, usable: Boolean = true, vpn: Boolean = false) =
    NetworkSnapshot(fingerprint = fingerprint, isWifi = true, isUsable = usable, isVpn = vpn)

  @Test
  fun `joining the approved wifi runs the windows`() {
    assertEquals(BackgroundPlan.RUN, backgroundPlan(on(home), state(), alarmsAllowed = true))
  }

  @Test
  fun `joining any other wifi stops them`() {
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(cafe), state(), alarmsAllowed = true))
  }

  @Test
  fun `with nobody paired there is nothing to wake for`() {
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(home), state(paired = false), alarmsAllowed = true))
  }

  @Test
  fun `a link still settling or hidden behind a vpn leaves the schedule alone`() {
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(home, usable = false), state(), alarmsAllowed = true))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(null), state(), alarmsAllowed = true))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(on(home, vpn = true), state(), alarmsAllowed = true))
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(NetworkSnapshot.Offline, state(), alarmsAllowed = true))
  }

  @Test
  fun `with the switch off nothing wakes, even at home`() {
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(home), state(backgroundSync = false), alarmsAllowed = true))
  }

  @Test
  fun `with alarms revoked nothing wakes, rather than falling back to a slower schedule`() {
    // Android cancelled the alarm and killed the app, and said nothing (Pixel, 2026-10-21).
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(home), state(backgroundSync = true), alarmsAllowed = false))
    // And whatever the network, since there is no schedule left to leave alone.
    assertEquals(BackgroundPlan.STOP, backgroundPlan(NetworkSnapshot.Offline, state(backgroundSync = true), alarmsAllowed = false))
  }

  @Test
  fun `never touched, it runs where alarms are allowed and not where they are not`() {
    assertEquals(BackgroundPlan.RUN, backgroundPlan(on(home), state(backgroundSync = null), alarmsAllowed = true))
    assertEquals(BackgroundPlan.STOP, backgroundPlan(on(home), state(backgroundSync = null), alarmsAllowed = false))
  }
}
