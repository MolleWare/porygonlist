package io.github.molleware.porygonlist.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.molleware.porygonlist.data.sync.DeviceId
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's long-term identity: the key pair everything else authenticates against.
 *
 * Generated once, on first use, and never rotated — rotating it would change the [DeviceId] and so
 * orphan every item this phone has authored, and every pairing anyone has with it.
 */
interface LocalIdentity {
  val deviceId: DeviceId

  /** X.509 SubjectPublicKeyInfo. This is what goes in a pairing invite and what peers pin. */
  val publicKey: ByteArray

  fun sign(data: ByteArray): ByteArray

  /**
   * The same key pair as something TLS can present, or null where there is none to present.
   *
   * The certificate is not built here and does not need a library: generating a key in the Android
   * Keystore **also generates a self-signed X.509 certificate wrapping it**, which comes back from
   * `getCertificate(alias)`. Its subject and validity are whatever the platform chose and none of
   * it is believed by anything — a peer pins [publicKey] and ignores the rest of the certificate,
   * because the certificate is only the envelope TLS insists the key travel in.
   *
   * The private key it comes with is an opaque keystore handle rather than key material. It can be
   * signed with and not read, which is what keeps the identity on the phone even here.
   */
  fun keyEntry(): java.security.KeyStore.PrivateKeyEntry?
}

interface IdentityStore {
  /** Loads the identity, creating it on first run. */
  fun identity(): LocalIdentity

  /**
   * Destroys the key, so the next [identity] call mints a different phone.
   *
   * There is no rotation here and this is not it: the device id is derived from the key, so a new
   * key is a new device with no claim on anything the old one authored. Every peer still pins the
   * old public key and will not recognise what comes back, which is why this belongs behind a
   * deliberate choice and nothing automatic may call it.
   */
  fun forget()
}

/**
 * Keeps the private key in the Android Keystore.
 *
 * The key is generated inside the keystore and never leaves it — this process can ask for
 * signatures but cannot read the key, so a filesystem compromise or a careless backup does not hand
 * over the phone's identity. The cost is that the identity cannot be exported: a new phone is a new
 * device that has to pair again, which is the same bargain Syncthing makes.
 *
 * EC P-256 rather than Ed25519: keystore support for Ed25519 only arrives in recent Android, and
 * this app runs from API 26.
 */
class AndroidKeystoreIdentityStore(private val alias: String = DEFAULT_ALIAS) : IdentityStore {

  override fun identity(): LocalIdentity {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getCertificate(alias)?.publicKey
    val publicKey = existing ?: generate()

    val encoded = publicKey.encoded
    return object : LocalIdentity {
      override val deviceId = DeviceIdentity.deviceIdFor(encoded)
      override val publicKey = encoded

      override fun sign(data: ByteArray): ByteArray {
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
          initSign(entry.privateKey)
          update(data)
          sign()
        }
      }

      override fun keyEntry(): KeyStore.PrivateKeyEntry? =
        runCatching { keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
    }
  }

  override fun forget() {
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
  }

  private fun generate(): java.security.PublicKey {
    val spec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
        // SHA-256 is what [LocalIdentity.sign] asks for. DIGEST_NONE is what **TLS** asks for, and
        // leaving it out is why a pinned handshake on a real phone closed without an error on
        // either side: Conscrypt hashes the handshake transcript itself and hands the key a raw
        // 32-byte digest to sign, so a key authorised only for SHA-256 refuses, and the server
        // hangs up before it has sent a certificate. Measured on a Pixel 6 — with NONE added the
        // same code negotiates TLSv1.3 / TLS_AES_128_GCM_SHA256 first time.
        //
        // What it costs: the key will sign any 32 bytes put in front of it, rather than only
        // things it has hashed itself. That is the same bargain every keystore-backed client
        // certificate on Android makes, and this key signs nothing but handshakes and its own
        // pairing invites.
        //
        // **Adding this to an existing key is not possible.** Authorised digests are fixed when
        // the key is generated, so a phone whose identity predates this has to mint a new one —
        // and a new key is a new DeviceId. See docs/ARCHITECTURE.md.
        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        // Deliberately not requiring user authentication: sync has to work with the phone in a
        // pocket, and the key guards a grocery list.
        .build()

    return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
      .apply { initialize(spec) }
      .generateKeyPair()
      .public
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DEFAULT_ALIAS = "porygonlist.identity"
    private const val CURVE = "secp256r1"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
  }
}

/**
 * An identity held in memory, for tests and previews.
 *
 * Never use this on a real device: the key vanishes with the process, so the phone would come back
 * as a stranger every launch.
 */
class InMemoryIdentityStore(seedKeyPair: java.security.KeyPair? = null) : IdentityStore {

  private var keyPair =
    seedKeyPair
      ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

  private var cached: LocalIdentity? = null

  override fun identity(): LocalIdentity =
    cached
      ?: object : LocalIdentity {
          override val deviceId = DeviceIdentity.deviceIdFor(keyPair.public.encoded)
          override val publicKey: ByteArray = keyPair.public.encoded

          override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance(AndroidKeystoreIdentityStore.SIGNATURE_ALGORITHM).run {
              initSign(keyPair.private)
              update(data)
              sign()
            }

          /**
           * Null: there is no certificate here, so there is nothing TLS could present.
           *
           * Generating one would mean hand-writing an X.509 encoder for a fixture, and the thing
           * it would be standing in for — the platform minting a certificate alongside a keystore
           * key — is exactly the part that cannot be reproduced off a device. Callers already
           * treat null as "cannot serve TLS", so a preview or a JVM test simply does not.
           */
          override fun keyEntry(): java.security.KeyStore.PrivateKeyEntry? = null
        }
        .also { cached = it }

  override fun forget() {
    keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    cached = null
  }
}
