package io.github.molleware.porygonlist.fakephone

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.FileListRepository
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PinnedTls
import io.github.molleware.porygonlist.data.net.ReachablePeer
import io.github.molleware.porygonlist.data.sync.SyncExchange
import io.github.molleware.porygonlist.data.sync.SyncPayload
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.net.NetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.net.PeerDiscovery
import io.github.molleware.porygonlist.data.net.SERVICE_TYPE
import io.github.molleware.porygonlist.data.net.ServiceRecord
import io.github.molleware.porygonlist.data.net.SyncAnswerer
import io.github.molleware.porygonlist.data.net.SyncCoordinator
import io.github.molleware.porygonlist.data.net.SyncEndpoint
import io.github.molleware.porygonlist.data.net.TXT_DEVICE_ID
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.ui.PorygonViewModel
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.Signature
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain

/**
 * A laptop standing in for a second phone.
 *
 * Built from the app's own parts — the repository, the view model, the TLS endpoint, the sync
 * coordinator — so what crosses the wifi is exactly what a phone would send. Only the edges are
 * swapped: a key in a PKCS12 file for the Android Keystore, JmDNS for NsdManager, and a network
 * monitor that says "approved wifi" until told otherwise.
 *
 * It has no screen. It reads one command per line from a FIFO and answers on stdout; see
 * scripts/fakephone.sh, which is the way to start and drive it.
 *
 * What it cannot stand in for is another phone's own quirks — a Samsung's mDNS, a port that changes
 * each time it rejoins the wifi. Those still need the real phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
  val dir = File(args.getOrNull(0) ?: error("usage: FakePhone <state dir> [name]")).apply { mkdirs() }
  val name = args.getOrNull(1) ?: "Laptop"
  val fifo = File(dir, "in")

  val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
  Dispatchers.setMain(main)

  val identity = KeyFileIdentity(File(dir, "identity.p12"))
  val address = lanAddress()
  say("I am ${identity.deviceId.value}, at ${address.hostAddress}")

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val repo = FileListRepository(File(dir, "porygonlist.state"), scope, identity = { identity })
  val wifi = FakeWifi(address.hostAddress)
  val discovery = JmdnsDiscovery(address, { identity.deviceId })
  val endpoint = SyncEndpoint(scope) { identity }
  val coordinator =
    SyncCoordinator(
        repo = repo,
        identity = { identity },
        found = discovery.found,
        scope = scope,
        // Off the wifi a phone cannot reach anyone, remembered addresses included; the laptop still
        // can, so it is told.
        dial = { peer, payload -> if (wifi.isOnline) dial(identity, peer, payload) else null },
        callbackPort = { endpoint.syncPort },
      )
  // Said aloud, because "the phone called by itself" is often the thing under test.
  endpoint.syncHandler = SyncAnswerer { key, socket ->
    say("call from ${DeviceIdentity.deviceIdFor(key).value} at ${socket.inetAddress?.hostAddress}")
    coordinator.answer(key, socket)
  }

  val store = ViewModelStore()
  val vm =
    runBlocking(main) {
      ViewModelProvider(
        store,
        viewModelFactory {
          initializer { PorygonViewModel(repo, wifi, { identity }, {}, discovery, endpoint, coordinator) }
        },
      )[PorygonViewModel::class.java]
    }

  val console = Console(vm, wifi, discovery, name)
  runBlocking(main) { console.firstRun() }

  // Changes arriving from the other phone, said as they happen, so a test can wait on the log.
  scope.launch {
    var before = repo.state.value
    repo.state.drop(1).collect { after ->
      if (after != null && before != null && after.lists != before?.lists) say("changed: ${summary(after)}")
      before = after
    }
  }

  say("ready")
  while (true) {
    // Opening a FIFO blocks until a writer arrives, and reading ends when that writer closes, so each
    // `fakephone.sh send` is one open, one or more lines, one close.
    fifo.bufferedReader().useLines { lines ->
      lines.forEach { line ->
        val command = line.trim()
        if (command.isEmpty()) return@forEach
        say("> $command")
        val ok = runCatching { runBlocking(main) { console.run(command) } }
        ok.exceptionOrNull()?.let { say("error: ${it.message ?: it::class.simpleName}") }
        if (command == "quit") {
          discovery.stop()
          endpoint.stopAll()
          // Let the repository's delayed write land before going.
          Thread.sleep(600)
          kotlin.system.exitProcess(0)
        }
      }
    }
  }
}

/**
 * The coordinator's own call, step for step, with what happened said aloud. The coordinator's
 * version is private; this one exists so a test can see the fake phone call, and whether it got
 * through.
 */
private fun dial(identity: LocalIdentity, peer: ReachablePeer, payload: SyncPayload): SyncExchange.Exchange? {
  val who = "${peer.peer.name} at ${peer.host}:${peer.port}"
  val factory = PinnedTls.syncClientFactory(identity, peer.peer.publicKey) ?: return null
  return try {
    factory.createSocket().use { plain ->
      val socket = plain as SSLSocket
      PinnedTls.harden(socket)
      socket.soTimeout = 8_000
      socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
      socket.startHandshake()
      SyncExchange.call(payload, socket.outputStream, socket.inputStream)
    }
      .also { say(if (it != null) "called $who: exchanged" else "called $who: no exchange") }
  } catch (e: IOException) {
    say("called $who: failed, ${e.message ?: e::class.simpleName}")
    null
  }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun say(line: String) {
  println("${LocalTime.now().format(clock)} $line")
  System.out.flush()
}

private fun summary(state: AppState) =
  state.lists
    .filter { it.personFor(state.localDevice)?.present == true }
    .joinToString(" | ") { list -> "${list.name}: ${list.liveItems.size} items, ${list.liveItems.count { it.checked }} ticked" }

/** Every command the fake phone understands. Runs on the main dispatcher, as a screen would. */
private class Console(
  private val vm: PorygonViewModel,
  private val wifi: FakeWifi,
  private val discovery: JmdnsDiscovery,
  private val name: String,
) {
  private val state: AppState
    get() = vm.state.value ?: error("not loaded yet")

  /** A name and an approved wifi, so it is ready to pair and sync without being told. */
  suspend fun firstRun() {
    while (vm.state.value == null) kotlinx.coroutines.delay(20)
    if (state.displayName.isEmpty()) {
      vm.onNameDraftChange(name)
      vm.saveName()
    }
    // Read once so the gate sees the current snapshot, then approved.
    while (vm.network.value.fingerprint == null) kotlinx.coroutines.delay(20)
    vm.approveCurrentNetwork()
    vm.cancelNamingNetwork()
  }

  suspend fun run(line: String) {
    val verb = line.substringBefore(' ')
    val rest = line.substringAfter(' ', "").trim()
    when (verb) {
      "help" -> say(HELP)
      "state" -> dump()
      "code" -> code()
      // Another phone's link, as though scanned: trusted, and our own key handed back over the wifi.
      "pair" -> {
        vm.onPairCodeChange(rest)
        checkNotNull(vm.pendingInvite) { vm.pairNote.ifEmpty { "not a pairing code" } }
        vm.pairAsNew()
        val until = System.currentTimeMillis() + 6_000
        while ((vm.handingBack || vm.pairNote.isEmpty()) && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
        say(vm.pairNote)
      }
      "lists" -> state.visibleLists().forEachIndexed { i, l -> say("[$i] ${l.name}${if (l.id == state.activeListId) "  (open)" else ""}") }
      "open" -> vm.openList(list(rest).id)
      "newlist" -> {
        vm.onListDraftChange(rest)
        vm.createList()
      }
      "renamelist" -> {
        vm.startRename(state.activeList)
        vm.onRenameDraftChange(rest)
        vm.saveRename()
      }
      "leave", "deletelist" -> vm.deleteList(state.activeListId)
      "share" -> vm.addPersonToActiveList(peer(rest))
      "kick" -> vm.removePersonFromActiveList(peer(rest))
      "unpair" -> vm.unpair(peer(rest))
      "add" -> vm.addItem(rest)
      "bulk" -> {
        val count = rest.substringBefore(' ').toInt()
        val prefix = rest.substringAfter(' ', "Item").ifBlank { "Item" }
        repeat(count) { vm.addItem("$prefix ${"%03d".format(it + 1)}") }
      }
      "tick" -> vm.toggleChecked(item(rest).id)
      "rename" -> {
        val (old, new) = rest.split("=>").map { it.trim() }.also { require(it.size == 2) { "rename <item> => <new name>" } }
        edit(old) { vm.onSheetNameChange(new) }
      }
      "qty" -> {
        val wanted = rest.substringAfterLast(' ').toInt()
        edit(rest.substringBeforeLast(' ')) {
          repeat(30) { vm.qtyDown() }
          repeat(wanted - 1) { vm.qtyUp() }
        }
      }
      "remove" -> {
        vm.openEditSheet(item(rest), addedBy = "")
        vm.confirmRemove()
      }
      "keep" -> {
        // The first card on the open list, as the screen shows them: oldest first.
        val card = state.openConflictsOn(state.activeListId).firstOrNull() ?: error("no card on the open list")
        when (rest) {
          "mine", "yours" -> vm.keepVersion(card, yours = true)
          "theirs" -> vm.keepVersion(card, yours = false)
          "both" -> vm.keepBoth(card)
          else -> error("keep mine | theirs | both")
        }
      }
      "offline" -> wifi.online(false)
      "online" -> wifi.online(true)
      "found" -> discovery.found.value.forEach { say("found ${it.deviceId.value} at ${it.host}:${it.port}") }
      "quit" -> say("bye")
      else -> error("unknown command '$verb' — try help")
    }
    if (verb != "state" && verb != "help" && verb != "lists" && verb != "found" && verb != "code") say("ok")
  }

  /** Puts a pairing code up and prints it, address and token included, for the Pixel to paste. */
  private suspend fun code() {
    if (vm.listeningPort == null) {
      vm.startPairing()
      // The socket opens on the IO dispatcher and the port is set back on this one, so wait by
      // suspending: sleeping here would hold the very thread that has to set it.
      val until = System.currentTimeMillis() + 3_000
      while (vm.listeningPort == null && System.currentTimeMillis() < until) kotlinx.coroutines.delay(20)
    }
    say("code ${vm.invite(name)}")
  }

  private fun edit(itemName: String, change: () -> Unit) {
    vm.openEditSheet(item(itemName), addedBy = "")
    change()
    vm.saveSheet()
  }

  private fun dump() {
    val s = state
    say("me: ${s.displayName} ${s.localDevice.value}, wifi ${if (wifi.isOnline) "on" else "off"}, socket ${vm.listeningPort ?: "-"}")
    s.peers.forEach { say("peer: ${it.name} ${it.deviceId.value}") }
    s.visibleLists().forEachIndexed { i, list ->
      val people = list.people.filter { it.present }.joinToString { it.name.ifEmpty { it.device.value.take(6) } }
      say("[$i] ${list.name}  — $people${if (list.id == s.activeListId) "  (open)" else ""}")
      list.liveItems.forEach { say("      ${if (it.checked) "[x]" else "[ ]"} ${it.label()}") }
    }
    // What a screen would show: a clash answered on the other phone is stored but no longer asked.
    s.openConflicts.forEach { say("conflict (${it.kind}) on ${it.itemName}: mine ${it.yours.label()} / theirs ${it.theirs.label()}") }
  }

  private fun GroceryItem.label() =
    name.value + (if (qty.value > 1) " ×${qty.value}" else "") + (if (removed.value) " (removed)" else "")

  private fun AppState.visibleLists() = lists.filter { it.personFor(localDevice)?.present == true }

  /** By index from `lists`, or by name. */
  private fun list(key: String): GroceryList {
    val visible = state.visibleLists()
    return key.toIntOrNull()?.let { visible.getOrNull(it) }
      ?: visible.singleOrNull { it.name.equals(key, ignoreCase = true) }
      ?: error("no single list '$key' — use its index from `lists`")
  }

  private fun item(key: String): GroceryItem =
    state.activeList.liveItems.firstOrNull { it.name.value.equals(key, ignoreCase = true) }
      ?: error("no item '$key' on ${state.activeList.name}")

  private fun peer(key: String): DeviceId =
    state.peers.firstOrNull { it.name.equals(key, ignoreCase = true) || it.deviceId.value == key }?.deviceId
      ?: error("no paired phone '$key'")

  companion object {
    const val HELP =
      "state | lists | code | pair <link> | found | open <list|index> | newlist <name> | renamelist <name> | leave | " +
        "share <peer> | kick <peer> | unpair <peer> | add <item> | bulk <n> [prefix] | tick <item> | " +
        "rename <item> => <name> | qty <item> <n> | remove <item> | keep mine|theirs|both | offline | online | quit"
  }
}

/**
 * The phone's key, kept in a PKCS12 file beside its state.
 *
 * Made with keytool on first run because the JDK has no public API for a self-signed certificate,
 * and TLS wants the key inside one. Same curve as the phone's, P-256. The password is not a secret:
 * this is a throwaway test identity that never leaves the laptop.
 */
private class KeyFileIdentity(file: File) : LocalIdentity {
  private val entry: KeyStore.PrivateKeyEntry

  init {
    if (!file.exists()) {
      val keytool = File(System.getProperty("java.home"), "bin/keytool").path
      val made =
        ProcessBuilder(
            keytool, "-genkeypair", "-keystore", file.path, "-storetype", "PKCS12",
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-alias", ALIAS,
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=Fake", "-validity", "9999",
          )
          .inheritIO()
          .start()
          .waitFor()
      check(made == 0) { "keytool failed" }
    }
    val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
    entry = store.getEntry(ALIAS, KeyStore.PasswordProtection(PASSWORD.toCharArray())) as KeyStore.PrivateKeyEntry
  }

  override val publicKey: ByteArray = entry.certificate.publicKey.encoded
  override val deviceId = DeviceIdentity.deviceIdFor(publicKey)

  override fun sign(data: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").run {
      initSign(entry.privateKey)
      update(data)
      sign()
    }

  override fun keyEntry() = entry

  private companion object {
    const val ALIAS = "porygonlist"
    const val PASSWORD = "fakephone"
  }
}

/** An approved home wifi, until `offline` says the laptop walked out of range. */
private class FakeWifi(address: String) : NetworkMonitor {
  private val on =
    NetworkSnapshot(NetworkFingerprint("fa4efa4efa4e"), isWifi = true, isUsable = true, address = address)

  override val snapshots = MutableStateFlow(on)
  override val wifiName = MutableStateFlow<String?>("Fake wifi")

  override fun refreshWifiName() = Unit

  val isOnline
    get() = snapshots.value.isUsable

  fun online(yes: Boolean) {
    snapshots.value = if (yes) on else NetworkSnapshot.Offline
  }
}

/**
 * NsdManager's job, done with JmDNS: advertise this phone with its id in the TXT record, and report
 * every phone that advertises the same, resolved to an IPv4 address.
 */
private class JmdnsDiscovery(private val address: InetAddress, private val me: () -> DeviceId) : PeerDiscovery {
  private val type = "$SERVICE_TYPE.local."
  private val lock = Any()
  private var jmdns: JmDNS? = null
  private var advertised: Int? = null
  private val resolved = ConcurrentHashMap<String, ServiceRecord>()

  private val _found = MutableStateFlow<Set<ServiceRecord>>(emptySet())
  override val found: StateFlow<Set<ServiceRecord>> = _found.asStateFlow()

  override fun start(port: Int) =
    synchronized(lock) {
      if (jmdns != null && advertised == port) return
      stopLocked()
      // A host name per phone, so two fake phones on one laptop do not answer for each other.
      val dns = JmDNS.create(address, "fake-${me().value.lowercase()}")
      dns.registerService(ServiceInfo.create(type, me().value, port, 0, 0, mapOf(TXT_DEVICE_ID to me().value)))
      dns.addServiceListener(
        type,
        object : ServiceListener {
          override fun serviceAdded(event: ServiceEvent) = event.dns.requestServiceInfo(event.type, event.name, 3_000)

          override fun serviceRemoved(event: ServiceEvent) {
            resolved.remove(event.name)
            publish()
          }

          override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val id = info.getPropertyString(TXT_DEVICE_ID)?.trim().orEmpty()
            val host = info.inet4Addresses.firstOrNull()?.hostAddress
            if (id.isEmpty() || id == me().value || host == null || info.port <= 0) return
            resolved[event.name] = ServiceRecord(DeviceId(id), host, info.port)
            publish()
          }
        },
      )
      jmdns = dns
      advertised = port
      say("advertising on port $port")
    }

  override fun stop() = synchronized(lock) { stopLocked() }

  private fun stopLocked() {
    val dns = jmdns ?: return
    runCatching {
      dns.unregisterAllServices()
      dns.close()
    }
    jmdns = null
    advertised = null
    resolved.clear()
    publish()
    say("stopped advertising")
  }

  private fun publish() {
    _found.value = resolved.values.toSet()
  }
}

/**
 * The laptop's address on the home wifi. FAKEPHONE_IP picks one when the guess is wrong — a
 * container's own bridges are site-local too, which is why 192.168 is preferred.
 */
private fun lanAddress(): InetAddress {
  System.getenv("FAKEPHONE_IP")?.takeIf { it.isNotBlank() }?.let { return InetAddress.getByName(it) }
  val candidates =
    NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
      .flatMap { it.inetAddresses.toList() }
      .filterIsInstance<Inet4Address>()
      .filter { it.isSiteLocalAddress }
  return candidates.firstOrNull { it.hostAddress.startsWith("192.168.") }
    ?: candidates.firstOrNull()
    ?: error("no LAN address found; set FAKEPHONE_IP")
}
