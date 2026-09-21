package io.github.molleware.porygonlist.data.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.molleware.porygonlist.data.crypto.AndroidKeystoreIdentityStore
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.data.crypto.PairingToken
import io.github.molleware.porygonlist.data.crypto.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pairing handshake against a **real Android Keystore**, over loopback.
 *
 * This suite exists against the grain of the rest of the project, where logic lives in the JVM
 * tests and the phone is only ever looked at. The reason is narrow and does not generalise: the
 * thing under test is whether the platform's TLS stack will sign a handshake with a key it cannot
 * read, held in hardware that does not exist off a device. There is no way to ask that question on
 * a JVM, and no way to answer it by looking at a screenshot.
 *
 * **What was actually in doubt.** Identity keys are generated with
 * `setDigests(KeyProperties.DIGEST_SHA256)` and nothing else. If the TLS stack ever asks that key
 * for a SHA-384 or SHA-512 signature while negotiating, the handshake dies — and the keys on the
 * phones already in use cannot be given more digests after the fact, because a new key is a new
 * [io.github.molleware.porygonlist.data.sync.DeviceId]. So this is a load-bearing assumption with
 * no migration path, which is exactly the kind worth pinning down in a test rather than
 * discovering in a supermarket.
 *
 * Loopback rather than two phones: what is being checked is the keystore and the TLS stack, and
 * both of those are the same whether the bytes cross a wifi or a loopback interface. Two phones
 * test the network, which is a different question and a different day.
 */
@RunWith(AndroidJUnit4::class)
class PairingHandshakeTest {

  /**
   * Test identities, under their own aliases.
   *
   * Never the app's real alias. Generating over `porygonlist.identity` would change this phone's
   * device id and silently unpair it from every phone it knows.
   */
  private lateinit var serverStore: AndroidKeystoreIdentityStore
  private lateinit var clientStore: AndroidKeystoreIdentityStore
  private lateinit var server: LocalIdentity
  private lateinit var client: LocalIdentity

  private lateinit var scope: CoroutineScope
  private lateinit var endpoint: SyncEndpoint

  @Before
  fun setUp() {
    serverStore = AndroidKeystoreIdentityStore("porygonlist.test.server")
    clientStore = AndroidKeystoreIdentityStore("porygonlist.test.client")
    server = serverStore.identity()
    client = clientStore.identity()

    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    endpoint = SyncEndpoint(scope) { server }
  }

  @After
  fun tearDown() {
    endpoint.stopAll()
    scope.cancel()
    runCatching { serverStore.forget() }
    runCatching { clientStore.forget() }
  }

  /** The invite the client would show, pointed at wherever the server is actually listening. */
  private fun linkTo(port: Int, token: PairingToken?, name: String = "Ava") =
    PairingCodec.link(server, name, at = PeerAddress("127.0.0.1", port), token = token)

  @Test
  fun the_keystore_key_can_carry_a_tls_handshake() = runBlocking {
    // The whole question, in one line of setup: serverFactory is null when the keystore has no
    // usable entry to present, and nothing else in the app would tell you so.
    val token = PairingToken.mint()
    val port = endpoint.start(ListenReason.PAIRING, token)
    assertNotNull("the keystore key would not stand up a TLS server socket", port)

    val arrived = async { withTimeoutOrNull(10_000) { endpoint.incoming.first() } }

    val outcome =
      PairingHandshake.deliver(
        link = linkTo(port!!, token),
        me = client,
        myName = "Hugo",
        expecting = server.publicKey,
      )

    assertEquals(PairingHandshake.Outcome.Delivered, outcome)

    val invite = arrived.await()
    assertNotNull("the server never surfaced the invite", invite)
    assertEquals("Hugo", invite!!.displayName)
    // The id is derived from the key that crossed the wire, so this also says the key arrived
    // intact — a truncated or mangled one would hash to something else.
    assertEquals(client.deviceId, invite.deviceId)
    assertTrue(client.publicKey.contentEquals(invite.publicKey))
  }

  @Test
  fun a_different_key_at_that_address_is_refused() = runBlocking {
    // Someone else answering where the code said to call. The client pins the key it read off the
    // screen, so this must fail at the handshake — before a byte of our own key is offered.
    val token = PairingToken.mint()
    val port = endpoint.start(ListenReason.PAIRING, token)!!

    val outcome =
      PairingHandshake.deliver(
        link = linkTo(port, token),
        me = client,
        myName = "Hugo",
        // Any key that is not the server's. The client's own does nicely.
        expecting = client.publicKey,
      )

    assertTrue("an unpinned key was accepted", outcome is PairingHandshake.Outcome.Failed)
  }

  @Test
  fun a_caller_without_the_token_is_refused() = runBlocking {
    // A port-scanner, or anyone who has the public code but never saw the screen. It can complete
    // the TLS handshake — the server's key is public — so the token is the only thing standing
    // between it and the owner's attention.
    val token = PairingToken.mint()
    val port = endpoint.start(ListenReason.PAIRING, token)!!

    val arrived = async { withTimeoutOrNull(4_000) { endpoint.incoming.first() } }

    val outcome =
      PairingHandshake.deliver(
        link = linkTo(port, PairingToken.mint()),
        me = client,
        myName = "Hugo",
        expecting = server.publicKey,
      )

    assertTrue(outcome is PairingHandshake.Outcome.Failed)
    assertNull("a caller that never saw the code reached the owner", arrived.await())
  }

  @Test
  fun a_spent_token_does_not_work_twice() = runBlocking {
    // A photograph of the screen, used after the person it was meant for has already arrived.
    val token = PairingToken.mint()
    val port = endpoint.start(ListenReason.PAIRING, token)!!

    val first = async { withTimeoutOrNull(10_000) { endpoint.incoming.first() } }
    assertEquals(
      PairingHandshake.Outcome.Delivered,
      PairingHandshake.deliver(linkTo(port, token), client, "Hugo", server.publicKey),
    )
    assertNotNull(first.await())

    val second = async { withTimeoutOrNull(4_000) { endpoint.incoming.first() } }
    val outcome = PairingHandshake.deliver(linkTo(port, token), client, "Hugo", server.publicKey)

    assertTrue("the token was still good after being spent", outcome is PairingHandshake.Outcome.Failed)
    assertNull(second.await())
  }

  @Test
  fun leaving_the_pairing_screen_closes_the_door() = runBlocking {
    val token = PairingToken.mint()
    val port = endpoint.start(ListenReason.PAIRING, token)!!
    endpoint.stop(ListenReason.PAIRING)

    val outcome = PairingHandshake.deliver(linkTo(port, token), client, "Hugo", server.publicKey)

    assertTrue("the socket still answered after the screen closed", outcome is PairingHandshake.Outcome.Failed)
  }
}
