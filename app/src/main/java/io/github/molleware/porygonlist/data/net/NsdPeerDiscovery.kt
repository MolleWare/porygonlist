package io.github.molleware.porygonlist.data.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.molleware.porygonlist.data.sync.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Peer discovery over the framework's own mDNS responder.
 *
 * `NsdManager` is in the platform, so this costs no dependency and no permission beyond the ones
 * already held — which is the reason it was chosen over bundling a responder.
 *
 * **Nothing here is trust.** What is advertised is "a PorygonList device claiming id X is at this
 * address", and what is browsed for is the same claim from others. Both are unauthenticated and
 * both are trivially forgeable. Discovery's only job is to produce an address worth opening a
 * connection to; who is on the other end is settled afterwards, against the pinned key.
 *
 * What contains the presence leak is [discoveryDecision], not this class. Running means announcing
 * on the LAN every few seconds that this phone exists, so this must only ever be started on a
 * network the owner has approved — see the gate in `PorygonViewModel.discovery`.
 *
 * Not unit-tested: it is an adapter onto framework callbacks, and the rule worth testing — which
 * of the phones found are ones we paired with — is [reachablePeers], which is pure.
 */
class NsdPeerDiscovery(
  context: Context,
  /**
   * Read through a function, and never at construction.
   *
   * Two reasons, both load-bearing. Resolving the id means reading the Android Keystore, and this
   * object is built while the first screen is composing — the key is wanted when discovery starts,
   * which is long after. And deleting the identity replaces the one behind this: reading it late
   * is what stops a discarded instance advertising an id the phone no longer holds.
   */
  private val localDevice: () -> DeviceId,
  private val scope: CoroutineScope,
) : PeerDiscovery {

  private val appContext = context.applicationContext

  private val nsd by lazy { appContext.getSystemService(Context.NSD_SERVICE) as NsdManager }

  private val _found = MutableStateFlow<Set<ServiceRecord>>(emptySet())
  override val found: StateFlow<Set<ServiceRecord>> = _found.asStateFlow()

  /** Guards every field below. NSD delivers its callbacks on a binder thread, not the caller's. */
  private val lock = Any()

  private var advertisedPort: Int? = null
  private var registration: NsdManager.RegistrationListener? = null
  private var browse: NsdManager.DiscoveryListener? = null

  /**
   * The name the system actually registered us under.
   *
   * Kept because Android renames on collision, so this is not always the name that was asked for,
   * and skipping our own record on the browse side needs the name it really went out as.
   */
  private var registeredName: String? = null

  /** Resolved records by service name, because a loss is reported by name and nothing else. */
  private val resolved = mutableMapOf<String, ServiceRecord>()

  /**
   * Resolves are done one at a time.
   *
   * Not a style choice. Below API 34 the platform runs a single resolve at a time and answers every
   * overlapping one with `FAILURE_ALREADY_ACTIVE`, so firing a resolve per `onServiceFound` loses
   * most of them on exactly the networks with the most to find. The queue is what makes discovery
   * reliable rather than lucky.
   */
  private val pending = ArrayDeque<NsdServiceInfo>()
  private var resolving = false

  override fun start(port: Int) {
    synchronized(lock) {
      if (advertisedPort == port && registration != null) return
      stopLocked()
      advertisedPort = port
      advertiseLocked(port)
      browseLocked()
    }
  }

  override fun stop() {
    synchronized(lock) { stopLocked() }
  }

  // ── Advertising ───────────────────────────────────────────────────────────

  private fun advertiseLocked(port: Int) {
    val me = localDevice().value
    val info =
      NsdServiceInfo().apply {
        // The id is in the name as well as the TXT record so that a packet capture or another
        // mDNS browser is readable by a person debugging this. The TXT copy is the one believed.
        serviceName = me
        serviceType = SERVICE_TYPE
        setPort(port)
        setAttribute(TXT_DEVICE_ID, me)
      }

    val listener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
          synchronized(lock) { registeredName = info.serviceName }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
          synchronized(lock) { if (registration === this) registration = null }
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
      }

    // A responder that will not start leaves this phone browsing but invisible — degraded, and the
    // other phone can still reach us once it is found. Better than refusing to discover at all.
    if (runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
      registration = listener
    }
  }

  // ── Browsing ──────────────────────────────────────────────────────────────

  private fun browseLocked() {
    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
          synchronized(lock) {
            // Our own advertisement comes back to us. Dropping it by name here saves a resolve;
            // reachablePeers drops it by id as well, because the name is not always ours to know.
            if (info.serviceName == registeredName) return
            if (browse !== this) return
            pending.addLast(info)
            pumpLocked()
          }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
          synchronized(lock) {
            resolved.remove(info.serviceName)
            publishLocked()
          }
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          synchronized(lock) { if (browse === this) browse = null }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
      }

    if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
      browse = listener
    }
  }

  // ── Resolving, one at a time ──────────────────────────────────────────────

  private fun pumpLocked() {
    if (resolving) return
    val next = pending.removeFirstOrNull() ?: return
    resolving = true
    resolve(next, attempt = 1)
  }

  /**
   * Asks the platform for a found service's address and TXT record.
   *
   * `resolveService` is deprecated from API 34 in favour of `registerServiceInfoCallback`, which
   * also lifts the one-at-a-time limit. It is still the only call that exists all the way down to
   * this app's API 26 floor, so it stays until the floor moves; the queue above is what makes it
   * behave on both.
   */
  @Suppress("DEPRECATION")
  private fun resolve(info: NsdServiceInfo, attempt: Int) {
    val listener =
      object : NsdManager.ResolveListener {
        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
          synchronized(lock) {
            recordLocked(resolvedInfo)
            resolving = false
            pumpLocked()
          }
        }

        override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
          // ALREADY_ACTIVE means the platform was still busy with a resolve this class did not
          // start — its own bookkeeping, a previous run's, or another app's. Backing off and
          // trying again is the only remedy; everything else is a service that went away between
          // being found and being asked about, which is ordinary.
          val retry = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < RESOLVE_ATTEMPTS
          synchronized(lock) {
            resolving = false
            if (!retry) {
              pumpLocked()
              return
            }
          }
          scope.launch {
            delay(RESOLVE_BACKOFF_MS * attempt)
            synchronized(lock) {
              if (browse == null) return@launch
              if (resolving) {
                pending.addLast(failedInfo)
                return@launch
              }
              resolving = true
              resolve(failedInfo, attempt + 1)
            }
          }
        }
      }

    if (!runCatching { nsd.resolveService(info, listener) }.isSuccess) {
      synchronized(lock) {
        resolving = false
        pumpLocked()
      }
    }
  }

  /**
   * Turns a resolved service into a record, or drops it.
   *
   * The id is taken from the TXT record rather than the service name: Android appends a suffix to
   * a colliding name, and an id read out of `AVAPHONE (2)` is not an id. A service without the
   * attribute is not one of ours — the type is unregistered, so something else could be using it —
   * and is dropped rather than guessed at.
   */
  @Suppress("DEPRECATION")
  private fun recordLocked(info: NsdServiceInfo) {
    val claimed = info.attributes[TXT_DEVICE_ID]?.toString(Charsets.UTF_8)?.trim().orEmpty()
    if (claimed.isEmpty()) return
    if (claimed == localDevice().value) return

    // `host` is deprecated from API 34 in favour of `hostAddresses`, and is the only one available
    // at this app's floor. Same trade as resolveService above.
    val address = info.host?.hostAddress ?: return
    if (info.port <= 0) return

    resolved[info.serviceName] = ServiceRecord(DeviceId(claimed), address, info.port)
    publishLocked()
  }

  private fun publishLocked() {
    _found.value = resolved.values.toSet()
  }

  // ── Teardown ──────────────────────────────────────────────────────────────

  private fun stopLocked() {
    registration?.let { runCatching { nsd.unregisterService(it) } }
    browse?.let { runCatching { nsd.stopServiceDiscovery(it) } }
    registration = null
    browse = null
    registeredName = null
    advertisedPort = null
    pending.clear()
    // Deliberately not clearing `resolving`: a resolve already in flight will still call back, and
    // its callback checks nothing else. Letting it land and find an empty browse is harmless; the
    // records it writes are cleared here and its retry is refused by the `browse == null` guard.
    resolved.clear()
    publishLocked()
  }

  private companion object {
    const val RESOLVE_ATTEMPTS = 4
    const val RESOLVE_BACKOFF_MS = 250L
  }
}
