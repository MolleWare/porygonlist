package io.github.molleware.porygonlist.data.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.molleware.porygonlist.data.crypto.AndroidKeystoreIdentityStore
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PinnedTls
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.SyncExchange
import io.github.molleware.porygonlist.data.sync.SyncPayload
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sync call against a **real Android Keystore**, over loopback.
 *
 * The question the JVM suite cannot answer: a sync caller now presents its own certificate, so the
 * keystore has to sign the *client's* half of a TLS handshake too, with a key it will not let anyone
 * read. Pairing only ever proved the server side. If the client side fails the way the server side
 * once did — a hang-up with each end blaming the other — no list ever moves, and nothing short of
 * running it on a phone would show why.
 */
@RunWith(AndroidJUnit4::class)
class SyncTransportTest {

  // Test aliases, never the app's own: generating over it would change this phone's device id.
  private lateinit var serverStore: AndroidKeystoreIdentityStore
  private lateinit var clientStore: AndroidKeystoreIdentityStore
  private lateinit var server: LocalIdentity
  private lateinit var client: LocalIdentity

  private lateinit var scope: CoroutineScope
  private lateinit var endpoint: SyncEndpoint

  @Before
  fun setUp() {
    serverStore = AndroidKeystoreIdentityStore("porygonlist.test.syncserver")
    clientStore = AndroidKeystoreIdentityStore("porygonlist.test.syncclient")
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

  private fun payload(from: DeviceId) = SyncPayload(from = from, at = Hlc(1_000, 0, from), lists = emptyList())

  /** Places a sync call the way the coordinator does, presenting [as]'s key. */
  private fun call(port: Int, `as`: LocalIdentity): SyncExchange.Exchange? {
    val factory = PinnedTls.syncClientFactory(`as`, server.publicKey) ?: return null
    return try {
      factory.createSocket().use { plain ->
        val socket = plain as SSLSocket
        PinnedTls.harden(socket)
        socket.soTimeout = 8_000
        socket.connect(InetSocketAddress("127.0.0.1", port), 8_000)
        socket.startHandshake()
        SyncExchange.call(payload(`as`.deviceId), socket.outputStream, socket.inputStream)
      }
    } catch (e: IOException) {
      null
    }
  }

  @Test
  fun the_keystore_key_can_sign_as_a_sync_caller() {
    val presented = AtomicReference<ByteArray?>()
    endpoint.syncHandler = SyncAnswerer { key, socket ->
      presented.set(key)
      SyncExchange.answer(socket.inputStream, socket.outputStream) { payload(server.deviceId) }
    }
    val port = endpoint.start(ListenReason.DISCOVERY)
    assertNotNull("the keystore key would not stand up a TLS server socket", port)

    val exchange = call(port!!, client)

    assertNotNull("the sync call did not complete — the client-side signature is the suspect", exchange)
    // The listener saw exactly the caller's key, which is what it checks against its paired phones.
    assertTrue("the listener was handed a different key", client.publicKey.contentEquals(presented.get()))
    assertEquals(payload(client.deviceId).at, exchange!!.ackOfMine)
  }

  @Test
  fun a_sync_call_is_not_answered_off_an_approved_network() {
    val answered = AtomicReference(false)
    endpoint.syncHandler = SyncAnswerer { _, _ -> answered.set(true) }
    // Open only because a pairing code is on screen — the gate has not approved this network.
    val port = endpoint.start(ListenReason.PAIRING, io.github.molleware.porygonlist.data.crypto.PairingToken.mint())!!

    assertNull("a sync call was answered with only a pairing code up", call(port, client))
    assertTrue("the handler should never have been reached", !answered.get())
  }
}
