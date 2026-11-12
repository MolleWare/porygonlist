package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ApprovedNetwork
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @Test
  fun `the watch keeps the schedule coming while the switch is on and somebody is paired, whatever the network`() {
    // It reads no network: a reboot can leave the wifi listener missing, and the watch is the way
    // back from that, so it cannot wait on the listener's news of a wifi.
    assertTrue(keepsWatch(state(backgroundSync = true), alarmsAllowed = true))
    assertTrue("never touched, with alarms", keepsWatch(state(backgroundSync = null), alarmsAllowed = true))
  }

  @Test
  fun `the watch stands down with the switch off, alarms revoked, or nobody to sync with`() {
    assertFalse("turned off", keepsWatch(state(backgroundSync = false), alarmsAllowed = true))
    assertFalse("alarms revoked since it was turned on", keepsWatch(state(backgroundSync = true), alarmsAllowed = false))
    assertFalse("never touched, without alarms", keepsWatch(state(backgroundSync = null), alarmsAllowed = false))
    assertFalse("nobody paired", keepsWatch(state(paired = false), alarmsAllowed = true))
  }

  @Test
  fun `the watch does nothing while the quarter hour is armed, and re-arms and registers when it is not`() {
    // At home, or away on mobile data: armed, and the alarm's windows read the network themselves.
    // Registering there would fire at once for the home wifi, one more window an hour.
    assertEquals(WatchStep.NOTHING, watchStep(keeps = true, alarmArmed = true))
    // A strange wifi stopped the schedule, or a reboot cleared it: the one way back to the windows.
    assertEquals(WatchStep.REARM, watchStep(keeps = true, alarmArmed = false))
    assertEquals(WatchStep.STAND_DOWN, watchStep(keeps = false, alarmArmed = true))
    assertEquals(WatchStep.STAND_DOWN, watchStep(keeps = false, alarmArmed = false))
  }

  @Test
  fun `allowing alarms with the app closed arms the quarter hour with the switch on, though the network reads offline`() {
    // What the grant used to ask, from a process still firewalled, whose active network reads as
    // none: offline, which leaves the schedule alone, so nothing was armed.
    assertEquals(BackgroundPlan.LEAVE, backgroundPlan(NetworkSnapshot.Offline, state(backgroundSync = null), alarmsAllowed = true))
    // What it asks now, reading no network: the window the alarm opens checks the wifi itself.
    assertTrue("never touched, so on now that alarms are allowed", keepsWatch(state(backgroundSync = null), alarmsAllowed = true))
    assertTrue("turned on, then sent to allow them", keepsWatch(state(backgroundSync = true), alarmsAllowed = true))
    assertFalse("turned off: allowing alarms arms nothing", keepsWatch(state(backgroundSync = false), alarmsAllowed = true))
  }
}
