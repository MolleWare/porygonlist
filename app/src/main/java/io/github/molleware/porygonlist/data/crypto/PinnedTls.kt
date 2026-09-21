package io.github.molleware.porygonlist.data.crypto

import android.annotation.SuppressLint
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS where the only thing believed is a key somebody vouched for in person.
 *
 * **No certificate authority is involved, and none is wanted.** A CA answers "does a third party
 * say this name belongs to this key", which is a question about names on the public internet. The
 * question here is "is this the key I was shown when she held her phone up", and the answer is a
 * byte comparison. So the certificate is a formality TLS requires the key to travel inside, its
 * subject and dates are whatever the platform stamped on it, and nothing reads them.
 *
 * What is pinned is the SubjectPublicKeyInfo — the same encoding [DeviceIdentity.deviceIdFor]
 * hashes, so a pin and a device id are two views of one fact and cannot disagree.
 *
 * The private key never leaves the Android Keystore. TLS asks it for signatures through an opaque
 * handle; this process cannot read it and neither can a peer.
 */
object PinnedTls {

  /**
   * TLS 1.2 is the floor and 1.3 is used where the platform has it.
   *
   * 1.3 arrived in Android 10 and this app runs from API 26, so both are named and whichever the
   * platform supports is enabled — asking for one that is missing throws rather than degrading.
   * Nothing below 1.2 is ever enabled, on either end.
   */
  private val WANTED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

  /**
   * Accepts connections, presenting this phone's key.
   *
   * Client certificates are neither required nor asked for. The phone connecting is, by
   * definition, one this phone has never met — it is about to introduce itself — so there is
   * nothing to check it against. That is precisely why the introduction it sends has to be put in
   * front of a person before it means anything.
   *
   * Null when the phone has no key entry to present, which leaves the caller to stay silent rather
   * than accept connections it cannot authenticate itself on.
   */
  fun serverFactory(identity: LocalIdentity): SSLServerSocketFactory? {
    val entry = identity.keyEntry() ?: return null
    return context(entry, trust = AcceptAny)?.serverSocketFactory
  }

  /**
   * Connects, and refuses to speak to anything that is not [expecting].
   *
   * [expecting] is the public key out of the pairing invite — read off a screen, over a channel a
   * person can vouch for. Pinning it here is what makes the connection worth putting a key into:
   * an impostor on the same wifi cannot complete this handshake without the matching private key,
   * whatever address the invite pointed at.
   *
   * No client certificate is presented. This end has nothing the far end could check yet.
   */
  fun clientFactory(expecting: ByteArray): SSLSocketFactory? =
    context(entry = null, trust = Pinned(expecting))?.socketFactory

  /** Restricts a socket to the protocols above. Call before the handshake, on both ends. */
  fun harden(socket: SSLSocket) {
    val supported = socket.supportedProtocols.toSet()
    val enabled = WANTED_PROTOCOLS.filter { it in supported }
    if (enabled.isNotEmpty()) socket.enabledProtocols = enabled.toTypedArray()
  }

  private fun context(entry: KeyStore.PrivateKeyEntry?, trust: X509TrustManager): SSLContext? =
    runCatching {
        SSLContext.getInstance("TLS").apply {
          init(entry?.let { arrayOf<KeyManager>(SingleKeyManager(it)) }, arrayOf(trust), null)
        }
      }
      .getOrNull()

  /**
   * Hands TLS the one key this phone has.
   *
   * The stock `KeyManagerFactory` wants a `KeyStore` and a password, which an Android Keystore
   * entry does not have — its whole point is that there is no material to protect with one. This
   * returns the entry directly and skips the question.
   *
   * Every chooser returns the same alias unconditionally. There is nothing to choose between, and
   * in particular the issuer list a peer sends is ignored: it names certificate authorities, and
   * this certificate has none.
   *
   * **The `Engine` variants are not optional.** `X509ExtendedKeyManager` declares
   * `chooseEngineServerAlias` and `chooseEngineClientAlias` with a default implementation that
   * returns null, and Conscrypt's socket runs the handshake through an `SSLEngine` — so with only
   * the `Socket` overloads implemented, the server is asked for a certificate through the engine
   * path, answers "I have none", and hangs up mid-handshake. Both ends then report that the other
   * closed the connection, which says nothing about why. Overriding all four is what makes this
   * work on a real device.
   */
  private class SingleKeyManager(private val entry: KeyStore.PrivateKeyEntry) : X509ExtendedKeyManager() {

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS

    override fun chooseEngineClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      engine: SSLEngine?,
    ) = ALIAS

    override fun getCertificateChain(alias: String?): Array<X509Certificate> =
      entry.certificateChain.filterIsInstance<X509Certificate>().toTypedArray()

    override fun getPrivateKey(alias: String?): PrivateKey = entry.privateKey

    private companion object {
      const val ALIAS = "porygonlist"
    }
  }

  /**
   * Trusts exactly one key, by its bytes.
   *
   * Dates, subject, issuer and chain are all ignored on purpose. A self-signed certificate the
   * platform minted has no meaningful validity window and no issuer worth checking, and treating
   * an expiry as a reason to refuse would break pairing on a date nobody chose. The key is the
   * whole of the claim.
   */
  // Lint objects to any hand-written trust manager, and it is right to by default: almost every
  // one in the wild exists to make a certificate error go away, and ends up trusting everything.
  // This one trusts strictly less than the platform's — exactly one key, fixed in advance, with
  // no chain to build and no authority that could issue another. Deferring to the default here
  // would be the insecure option: it would accept any certificate a public CA had signed for any
  // name, which is not a statement about whose phone this is.
  @SuppressLint("CustomX509TrustManager")
  private class Pinned(private val expecting: ByteArray) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      val presented = chain?.firstOrNull()?.publicKey?.encoded
      // MessageDigest.isEqual is the constant-time comparison the platform ships. The value is
      // public, so this is not hiding a secret; it costs nothing and keeps the habit.
      if (presented == null || !java.security.MessageDigest.isEqual(presented, expecting)) {
        throw java.security.cert.CertificateException("not the key from the invite")
      }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      throw java.security.cert.CertificateException("this end never asks for a client certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  /**
   * Checks nothing, because there is nothing yet to check against.
   *
   * Only ever installed on the listening end of a pairing handshake, where the peer is unknown by
   * definition. It must never be used anywhere a peer is already paired: there the pinned key
   * exists and [Pinned] is the only correct answer.
   */
  // Lint's complaint is the correct one to raise about this shape, and the answer is not that it
  // is safe in general — it is that this end is not authenticating anybody here, and does not
  // pretend to. The check that stands in for it is the one-time token in PairingHandshake, and
  // after that a person saying yes. Nothing is read, written or merged on the strength of this.
  @SuppressLint("CustomX509TrustManager")
  private object AcceptAny : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
}
