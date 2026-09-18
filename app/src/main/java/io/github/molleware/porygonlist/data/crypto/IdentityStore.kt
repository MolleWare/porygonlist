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
}

interface IdentityStore {
  /** Loads the identity, creating it on first run. */
  fun identity(): LocalIdentity
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
    }
  }

  private fun generate(): java.security.PublicKey {
    val spec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
        .setDigests(KeyProperties.DIGEST_SHA256)
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

  private val keyPair =
    seedKeyPair
      ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

  private val cached =
    object : LocalIdentity {
      override val deviceId = DeviceIdentity.deviceIdFor(keyPair.public.encoded)
      override val publicKey: ByteArray = keyPair.public.encoded

      override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(AndroidKeystoreIdentityStore.SIGNATURE_ALGORITHM).run {
          initSign(keyPair.private)
          update(data)
          sign()
        }
    }

  override fun identity(): LocalIdentity = cached
}
