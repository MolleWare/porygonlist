package io.github.molleware.porygonlist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ApprovedNetwork
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.ListRepository
import io.github.molleware.porygonlist.data.initialOf
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.data.crypto.PairingInvite
import io.github.molleware.porygonlist.data.crypto.PairingToken
import io.github.molleware.porygonlist.data.crypto.PeerAddress
import io.github.molleware.porygonlist.data.LocalNode
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.ShareCodec
import io.github.molleware.porygonlist.data.Staple
import io.github.molleware.porygonlist.data.WordTrie
import io.github.molleware.porygonlist.data.alreadyOn
import io.github.molleware.porygonlist.data.suggestionVocabulary
import io.github.molleware.porygonlist.data.net.DiscoveryDecision
import io.github.molleware.porygonlist.data.net.HoldReason
import io.github.molleware.porygonlist.data.net.ListenReason
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.net.PairingHandshake
import io.github.molleware.porygonlist.data.net.NetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.net.PeerDiscovery
import io.github.molleware.porygonlist.data.net.ReachablePeer
import io.github.molleware.porygonlist.data.net.SyncCoordinator
import io.github.molleware.porygonlist.data.net.SyncEndpoint
import io.github.molleware.porygonlist.data.net.discoveryDecision
import io.github.molleware.porygonlist.data.net.reachablePeers
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.ItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How much has to be typed before anything is offered.
 *
 * One letter matches most of the vocabulary, which is a wall of words rather than a suggestion.
 */
private const val MIN_SUGGEST_PREFIX = 2

/** Which bottom sheet is up, if any. */
enum class Sheet {
  EDIT_ITEM,
  EXPORT,
  IMPORT,
}

/** The item being edited, held apart from the list so Cancel is free. */
data class EditDraft(val itemId: ItemId, val name: String, val qty: Int, val addedBy: String)

class PorygonViewModel(
  private val repo: ListRepository,
  private val monitor: NetworkMonitor,
  /** Read through a function: deleting the identity replaces the one behind it. */
  private val identity: () -> LocalIdentity,
  /** Destroys the key. Paired with [ListRepository.reset], never called on its own. */
  private val deleteIdentity: () -> Unit,
  /**
   * Finds other phones, and lets them find this one.
   *
   * Null in tests and previews, where there is no network to look at: the gate below then simply
   * has nothing to open, and everything else on this class behaves as it always did.
   */
  private val peerDiscovery: PeerDiscovery? = null,
  /** The socket peers connect back on. Null alongside [peerDiscovery], for the same reason. */
  private val endpoint: SyncEndpoint? = null,
  /** Decides when to exchange lists with paired phones. Null alongside the two above. */
  private val syncCoordinator: SyncCoordinator? = null,
) : ViewModel() {

  val state: StateFlow<AppState?> = repo.state

  /**
   * The link this phone is on right now.
   *
   * Live observation, never persisted: which wifi you are standing on is not a fact about your
   * lists, and a stale copy read back at launch would be worse than none.
   */
  val network: StateFlow<NetworkSnapshot> =
    monitor.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkSnapshot.Offline)

  /**
   * Whether to look for peers here — the gate that keeps the app silent on work and public wifi.
   *
   * A [DiscoveryDecision.Discover] means only that it is worth listening. Everything about who is
   * actually on the other end is settled afterwards, by keys.
   */
  val discovery: StateFlow<DiscoveryDecision> =
    combine(network, state) { snapshot, appState ->
        discoveryDecision(snapshot, appState?.approvedFingerprints.orEmpty())
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryDecision.Hold(HoldReason.OFFLINE))

  /**
   * The paired phones that are on this network right now.
   *
   * Found, not trusted. An entry means a phone claiming that id answered a browse at that address;
   * it is matched against the paired set here so that the connection which follows has the pinned
   * key to check it against, and a stranger advertising on the same wifi never gets that far.
   *
   * Empty whenever discovery is held, because [PeerDiscovery.stop] empties its own flow — there is
   * no separate clearing step to forget, and a stale address cannot survive a network change.
   */
  val reachable: StateFlow<List<ReachablePeer>> =
    combine(peerDiscovery?.found ?: MutableStateFlow(emptySet()), state) { found, appState ->
        if (appState == null) emptyList()
        else reachablePeers(found, appState.peers, appState.localDevice)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** The "Add something…" field. */
  var draft by mutableStateOf("")
    private set

  /** The first-run name field, held here so a rotation does not lose what has been typed. */
  var nameDraft by mutableStateOf("")
    private set

  var sheet by mutableStateOf<Sheet?>(null)
    private set

  var editDraft by mutableStateOf<EditDraft?>(null)
    private set

  var importText by mutableStateOf("")
    private set

  var importNote by mutableStateOf("")
    private set

  var copied by mutableStateOf(false)
    private set

  /** The edit sheet is asking whether the item really should go. */
  var confirmingRemoval by mutableStateOf(false)
    private set

  init {
    // Deliberately here and not in Application.onCreate: the first frame does not wait on disk.
    viewModelScope.launch { repo.load() }
    watchDiscoveryGate()
    watchIncomingPairings()
  }

  /**
   * Turns the network side on and off as [discovery] decides.
   *
   * The gate is the only thing that ever starts it. Nothing binds a socket or registers a service
   * until the decision is [DiscoveryDecision.Discover], which cannot happen before the state file
   * has loaded and named this network as approved — so the cold-start path stays clear of all of
   * it, and an unapproved network costs nothing at all.
   *
   * Stopping on every other decision is not tidiness. Coming off an approved network onto a café's
   * wifi, or having a VPN come up, has to take the advertisement down with it; leaving it running
   * would announce this phone in exactly the places the gate exists to stay quiet in.
   *
   * The port is read back from [SyncEndpoint] rather than chosen, and re-read on every open: the
   * system may give a different one after a stop, and advertising the previous number would send
   * the other phone at a closed door.
   */
  private fun watchDiscoveryGate() {
    val discoveryService = peerDiscovery ?: return
    val socket = endpoint ?: return

    viewModelScope.launch {
      // Waits for the state file before subscribing to anything. Collecting [discovery] is what
      // makes the network monitor hot, and the monitor's first act is a binder call to register a
      // callback — not something to do while the first frame is still being drawn. The load
      // finishes after it, and no network decision could have been acted on before it anyway,
      // because the approved list is in the file being read.
      repo.state.filterNotNull().first()

      // After the load, for the same reason. It has nothing to do until discovery finds a paired
      // phone, and discovery has nothing to find until the gate below opens.
      syncCoordinator?.start()

      discovery.collect { decision ->
        if (decision is DiscoveryDecision.Discover) {
          val port = socket.start(ListenReason.DISCOVERY)
          // No port means the socket would not bind. Silence is the honest answer: advertising a
          // phone that cannot be connected to just costs the other one a timeout.
          if (port != null) discoveryService.start(port) else discoveryService.stop()
        } else {
          discoveryService.stop()
          socket.stop(ListenReason.DISCOVERY)
        }
      }
    }
  }

  // ── Who you are ───────────────────────────────────────────────────────────

  fun onNameDraftChange(value: String) {
    nameDraft = value
  }

  /**
   * Records the owner's name, which is what ends first run.
   *
   * A blank name is not an error to report, only a button that has nothing to do yet — the screen
   * dims it rather than explaining itself.
   */
  fun saveName() {
    val name = nameDraft.trim()
    if (name.isEmpty()) return
    repo.update { s, _ -> s.withDisplayName(name) }
    nameDraft = ""
  }

  /** The id other phones know this one by. Shown so two people can check they paired with each other. */
  val deviceId: DeviceId
    get() = identity().deviceId

  /** Settings is asking whether the identity really should go. */
  var confirmingIdentityDelete by mutableStateOf(false)
    private set

  fun askDeleteIdentity() {
    confirmingIdentityDelete = true
  }

  fun cancelDeleteIdentity() {
    confirmingIdentityDelete = false
  }

  /**
   * Destroys this phone's identity and everything authored under it.
   *
   * The order matters. The key goes first, so that if anything fails afterwards the phone is not
   * left holding lists it can no longer sign for; the repository then reseeds under whatever the
   * new key says this device is. The app lands back on first run because the fresh state has no
   * name in it, which is the same path a new install takes — there is no separate "reset" screen
   * to keep working.
   */
  fun confirmDeleteIdentity() {
    confirmingIdentityDelete = false
    viewModelScope.launch {
      deleteIdentity()
      repo.reset()
    }
  }

  // ── Sync ──────────────────────────────────────────────────────────────────

  /**
   * Stands in for the real thing. Coming back onto an approved network hands over whatever was
   * waiting, which is the moment the design is built around.
   */
  fun toggleOnline() = repo.update { s, _ ->
    val comingOnline = !s.online
    s.copy(
      online = comingOnline,
      lists = if (comingOnline) s.lists.map { l -> l.copy(items = l.items.map { it.copy(pending = false) }) } else s.lists,
    )
  }

  /** Opens a list, which also marks a newly shared one as seen. */
  fun openList(id: ListId) = repo.update { s, _ ->
    s.copy(activeListId = id, lists = s.lists.map { if (it.id == id && it.arrivedFrom != null) it.copy(arrivedFrom = null) else it })
  }

  /**
   * What this phone should send a peer: everything it knows, and the point in its own clock that
   * covers it.
   *
   * The stamp is what the peer echoes back as proof of receipt, so it must be read at the same
   * moment as the payload — a later reading would claim delivery of changes that were never sent.
   */
  fun outgoingTo(peer: DeviceId): Pair<AppState, Hlc>? {
    val current = state.value ?: return null
    return current to current.clockHead
  }

  /**
   * Records a peer's proof of receipt, then collects whatever that makes collectable.
   *
   * [upTo] is the stamp this phone sent alongside the payload the peer is confirming — never a
   * fresh reading of the clock, which would mark unsent changes as delivered.
   */
  fun recordDelivery(peer: DeviceId, upTo: Hlc) = repo.update { s, _ ->
    s.copy(deliveredTo = s.deliveredTo.record(peer, upTo)).pruneDeliveredTombstones()
  }

  // ── Pairing ───────────────────────────────────────────────────────────────

  /**
   * The invite to show another phone — as a QR code, or as text where there is no camera.
   *
   * Contains a public key and a name, no secret. It is safe on a screen or in a message; what makes
   * pairing trustworthy is the channel, not confidentiality.
   *
   * In link form, so that the other phone's camera app can act on the QR code and so that the same
   * string is tappable when it arrives in a message. [PairingCodec.decode] still reads the bare
   * form, so codes sent before this keep working.
   */
  fun invite(displayName: String = state.value?.displayName.orEmpty()): String =
    PairingCodec.link(identity(), displayName, at = listeningAt(), token = listeningAt()?.let { pairingToken })

  /**
   * The same invite with nothing live in it: no address, no token.
   *
   * This is what the screen prints and what the copy button puts on the clipboard, and the two
   * forms have to be different. A pairing code is safe to send precisely because it is a public
   * key and a name — put a one-time token in it and it stops being safe to send, because a text
   * message is forwarded, backed up and read over shoulders long after the screen it came from
   * has closed.
   *
   * So the live parts are in the QR alone, where they are looked at once from across a table, and
   * the durable form stays exactly the string it has always been.
   */
  fun textInvite(displayName: String = state.value?.displayName.orEmpty()): String =
    PairingCodec.link(identity(), displayName)

  /**
   * Where this phone can be called back, while the pairing screen has it listening.
   *
   * Both halves have to be true at once. The port comes from a socket that is actually open, and
   * the address from the link this phone is standing on right now — neither is remembered, because
   * a remembered one is wrong the moment the wifi or the lease changes, and wrong invisibly.
   *
   * Null drops the hint and the invite is the older kind: still correct, still scannable, just
   * back to both people swapping codes.
   */
  private fun listeningAt(): PeerAddress? {
    val port = listeningPort ?: return null
    val snapshot = network.value
    // Under a VPN the address on the default link is the tunnel's, not the wifi's — the same
    // reason discovery holds for a VPN, and it bites harder here: an address that looks perfectly
    // local goes out in a code, and the phone that scans it dials somewhere that is not this one.
    // Better to carry no address and let both people swap codes.
    if (snapshot.isVpn) return null
    val host = snapshot.address ?: return null
    return PeerAddress(host, port)
  }

  /**
   * Whether the code on screen can actually be scanned once instead of twice.
   *
   * An open socket is not enough, which is what this originally said. The QR only carries a way
   * back if there is an address to put in it as well, so a phone with a port open but no usable
   * address would have promised "one scan does both" and then quietly needed two. This asks the
   * same question the invite does, so the screen cannot claim more than the code carries.
   */
  val reachableForPairing: Boolean
    get() = listeningAt() != null

  /**
   * The port the pairing screen has open, as Compose state.
   *
   * Held here rather than read off the endpoint because opening the socket is slow enough to be
   * worth doing off the main thread — so the screen draws its code first and this arrives a moment
   * later. It has to be state, or the QR would keep the addressless version it was first drawn
   * with and the handshake would never be offered.
   */
  var listeningPort by mutableStateOf<Int?>(null)
    private set

  /** True while this phone is handing its key back to one that just scanned its code. */
  var handingBack by mutableStateOf(false)
    private set

  /**
   * The token in the code currently on screen.
   *
   * Minted per visit to the pairing screen and never persisted — a token that outlived the screen
   * would be a standing invitation to pair with this phone, which is the opposite of what it is
   * for. Kept out of Compose state deliberately: it is not drawn, only embedded.
   */
  private var pairingToken: PairingToken? = null

  /**
   * Accepts an invite read from a QR code or pasted in.
   *
   * The id is derived from the key inside, so a scanned code cannot claim to be a phone it is not.
   * Re-pairing an existing peer replaces its key — which is how a peer that reinstalled gets back
   * in, and also the one moment an attacker would want, so it belongs behind a deliberate scan
   * rather than anything automatic.
   */
  fun pair(code: String): PairingInvite? = PairingCodec.decode(code)?.let { trust(it) }

  /**
   * Records an invite as a phone this one trusts, whether it was typed, scanned, or handed over.
   *
   * Split out from [pair] because an invite that arrived through [PairingHandshake] never existed
   * as text on this phone — re-encoding it only to decode it again would be inventing a string to
   * throw away, and the two paths have to agree on what pairing means.
   */
  private fun trust(invite: PairingInvite): PairingInvite? {
    // Pairing with yourself would make this phone its own peer and wait for its own receipts.
    if (invite.deviceId == identity().deviceId) return null

    repo.update { s, _ ->
      val peer = TrustedPeer(invite.deviceId, invite.publicKey, invite.displayName, System.currentTimeMillis())
      s.copy(peers = s.peers.filterNot { it.deviceId == invite.deviceId } + peer)
    }
    return invite
  }

  // The pairing screen's own state: what has been pasted, and what it turned out to be.

  var pairCode by mutableStateOf("")
    private set

  var pairNote by mutableStateOf("")
    private set

  /** The invite currently pasted in, once it has parsed. Null while there is nothing usable. */
  var pendingInvite by mutableStateOf<PairingInvite?>(null)
    private set

  /**
   * Reads what has been pasted as it arrives.
   *
   * Decoding on every keystroke is cheap — it is base64 and a hash — and it means the screen can
   * say what the code is before anything is committed, rather than reporting a failure after.
   */
  fun onPairCodeChange(value: String) {
    pairCode = value
    val trimmed = value.trim()
    val invite = PairingCodec.decode(trimmed)
    pendingInvite = invite?.takeIf { it.deviceId != identity().deviceId }
    pairNote =
      when {
        trimmed.isEmpty() -> ""
        invite == null -> "That does not look like a pairing code."
        invite.deviceId == identity().deviceId -> "That is this phone's own code."
        else -> ""
      }
  }

  /**
   * People who might be holding the phone in the pasted code.
   *
   * Anyone already on a list, other than this phone and the code's own device. Offering them is
   * what makes a replaced handset expressible at all: without it, a new phone can only ever be a
   * new person, and the old id goes on being waited on for receipts it will never send.
   */
  fun replaceCandidates(state: AppState): List<Person> =
    state.lists
      .flatMap { it.people }
      .distinctBy { it.device }
      // The phone in question is excluded whether it is still a question or has already paired
      // itself: a just-added person is on the list now, and offering them as somebody they might
      // be replacing would let them replace themselves.
      .filterNot {
        it.device == state.localDevice || it.device == pendingInvite?.deviceId || it.device == justPaired?.deviceId
      }

  /**
   * The list this pairing is being done in order to share, if it was started that way.
   *
   * Pairing and sharing stay separate acts in the model, and should: trusting a phone is about the
   * phone. But "share this list with someone new" is one intention, and making a person perform it
   * as two unrelated steps — pair here, then find the list and add them — is how a feature ends up
   * looking absent. Set on the way in, honoured once, cleared.
   */
  var pairingForList by mutableStateOf<ListId?>(null)
    private set

  /**
   * Opens the pairing screen, and puts this phone on the air for as long as it is up.
   *
   * The socket and the token are what make one scan enough: the code carries where to call and a
   * value only this screen knows, so the phone that scans it can hand its own key straight back.
   * Both die in [stopPairing]. Nothing here depends on the network being an approved one — see
   * [ListenReason.PAIRING] for why that is deliberate rather than a gap.
   */
  fun startPairing(forListId: ListId? = null) {
    pairingForList = forListId
    pairCode = ""
    pairNote = ""
    pendingInvite = null
    justPaired = null

    val socket = endpoint ?: return
    val token = PairingToken.mint()
    pairingToken = token
    viewModelScope.launch {
      // Binding a socket and building an SSL context are both slow enough to keep off the frame
      // that is drawing the code. The QR appears without an address and gains one a moment later.
      val port = withContext(Dispatchers.IO) { socket.start(ListenReason.PAIRING, token) }
      listeningPort = port
    }
  }

  /**
   * Leaves the pairing screen: the code stops working and the socket closes.
   *
   * Called on the way out however that happens, because the alternative is a phone that quietly
   * goes on accepting introductions from a code nobody is looking at any more.
   */
  fun stopPairing() {
    pairingToken = null
    listeningPort = null
    justPaired = null
    endpoint?.stop(ListenReason.PAIRING)
  }

  /** Trusts the pasted code as a phone this one has not seen before. */
  fun pairAsNew() {
    val invite = pendingInvite ?: return
    // Captured before the draft is cleared: it is the link, not the invite, that says where to
    // call back and what to say when we get there.
    val link = pairCode.trim()

    trust(invite)
    val listId = pairingForList
    if (listId != null) {
      addPersonToList(listId, invite.deviceId)
      pairNote = "${invite.displayName} is on ${listName(listId)}."
    } else {
      pairNote = "Paired with ${invite.displayName}."
    }
    clearPairDraft()

    handBackOurKey(link, invite)
  }

  /**
   * Gives this phone's own key to the one whose code was just scanned.
   *
   * This is the leg that used to be a second scan. It runs after the local pairing rather than
   * before, so that a network that will not carry it costs nothing: the pairing this person asked
   * for has already happened, and what fails is only the shortcut.
   *
   * Every outcome ends in a sentence on the screen, including the good one. "Paired" on its own
   * would leave the person wondering whether the other phone knows — which is exactly the doubt
   * that made two scans feel necessary.
   */
  private fun handBackOurKey(link: String, invite: PairingInvite) {
    val me = identity()
    val myName = state.value?.displayName.orEmpty()
    val paired = pairNote

    viewModelScope.launch {
      handingBack = true
      val outcome = withContext(Dispatchers.IO) { PairingHandshake.deliver(link, me, myName, invite.publicKey) }
      handingBack = false

      pairNote =
        when (outcome) {
          // Said plainly, because the whole point is that there is nothing left to do.
          PairingHandshake.Outcome.Delivered -> "$paired They have your code too — nothing else to do."
          // A code that came as a text carries no address. Not a failure, just the older way.
          PairingHandshake.Outcome.NoAddress -> "$paired Now show them your code."
          is PairingHandshake.Outcome.Failed -> "$paired Show them your code — ${outcome.reason}."
        }
    }
  }

  /**
   * Invites arriving from a phone that has just scanned this one's code.
   *
   * They land in exactly the place a pasted code lands, in front of exactly the same question.
   * That is the point: having read the screen earns a phone the owner's attention, and nothing
   * more. What it saves is the scan, not the decision.
   */
  private fun watchIncomingPairings() {
    val socket = endpoint ?: return
    viewModelScope.launch {
      socket.incoming.collect { invite ->
        if (invite.deviceId == identity().deviceId) return@collect

        trust(invite)
        val listId = pairingForList
        if (listId != null) addPersonToList(listId, invite.deviceId)

        justPaired = invite
        pairCode = ""
        pendingInvite = null
        pairNote = ""
      }
    }
  }

  /**
   * The phone that just paired itself by scanning this one's code.
   *
   * Shown as something that has happened, not something to approve. The consent was putting the
   * code on screen and holding it up; being asked to confirm it a second time, for a phone that
   * proved it read that very screen, is the ceremony this whole change exists to remove.
   *
   * It is still on screen and still undoable, which is the part that matters — a wrong name
   * appearing is visible immediately, and [undoJustPaired] is one tap away.
   */
  var justPaired by mutableStateOf<PairingInvite?>(null)
    private set

  /**
   * Takes back an automatic pairing.
   *
   * The way out when the wrong phone got there first. It unpairs rather than merely hiding the
   * card, because the card is the only notice this happened at all.
   */
  fun undoJustPaired() {
    val invite = justPaired ?: return
    justPaired = null
    unpair(invite.deviceId)
    pairNote = "${invite.displayName} was removed. Show your code again to try once more."
  }

  /**
   * Records that the phone which just paired is somebody's replacement handset, not a new person.
   *
   * Offered after the fact rather than before it, for the same reason the pairing itself is: the
   * common case is a new person, and making everyone answer a question that matters to almost
   * nobody is how a fast thing becomes a slow one. Taking it back is still cheap — the automatic
   * pairing has already happened, and this corrects it rather than racing it.
   */
  fun justPairedIsReplacementFor(oldDevice: DeviceId) {
    val invite = justPaired ?: return
    justPaired = null
    replaceDevice(oldDevice, invite)
    pairNote = "${invite.displayName}'s new phone took over."
  }

  fun dismissJustPaired() {
    justPaired = null
  }

  override fun onCleared() {
    // The socket outlives this object otherwise: it belongs to the graph, and nothing else would
    // think to close the pairing half of it.
    stopPairing()
    super.onCleared()
  }

  /** Records that [oldDevice]'s owner is now holding the phone in the pasted code. */
  fun pairAsReplacementFor(oldDevice: DeviceId) {
    val invite = pendingInvite ?: return
    replaceDevice(oldDevice, invite)
    pairNote = "${invite.displayName}'s new phone took over."
    clearPairDraft()
  }

  private fun listName(id: ListId): String = state.value?.lists?.firstOrNull { it.id == id }?.name.orEmpty()

  private fun clearPairDraft() {
    pairCode = ""
    pendingInvite = null
    pairingForList = null
  }

  /**
   * Stops sharing with a phone.
   *
   * Removes it from every list as well as from the paired set, which matters more than it looks: a
   * device left in a list's people is still waited on for delivery receipts, and one that will
   * never answer again would hold every tombstone on that list open for ever.
   */
  fun unpair(deviceId: DeviceId) = repo.update { s, node -> s.retireDevice(deviceId, node.clock.tick()) }

  /** Which person the People section is asking about before removing. */
  var confirmingRemovalOf by mutableStateOf<DeviceId?>(null)
    private set

  fun askRemovePerson(device: DeviceId) {
    confirmingRemovalOf = device
  }

  fun cancelRemovePerson() {
    confirmingRemovalOf = null
  }

  /**
   * Stops sharing the active list with someone.
   *
   * Stamped, so the decision travels to everyone on the list and to them — see
   * [AppState.removePersonFrom]. Their phone keeps what it had as a private list.
   */
  fun removePersonFromActiveList(device: DeviceId) {
    repo.update { s, node -> s.removePersonFrom(s.activeListId, device, node.clock.tick()) }
    confirmingRemovalOf = null
  }

  /**
   * Accepts a new phone as someone's replacement for an old one.
   *
   * Identity is tied to an installation, so a replaced handset is a different device. Its history
   * stays attributed to the same person, while the dead id stops being waited on.
   */
  fun replaceDevice(oldDevice: DeviceId, invite: PairingInvite) = repo.update { s, _ ->
    val peer = TrustedPeer(invite.deviceId, invite.publicKey, invite.displayName, System.currentTimeMillis())
    s.copy(peers = s.peers.filterNot { it.deviceId == oldDevice || it.deviceId == invite.deviceId } + peer)
      .replaceDevice(oldDevice, invite.deviceId, invite.displayName)
  }

  /**
   * Paired phones that are not on the list being looked at.
   *
   * Pairing and sharing are separate on purpose: trusting a phone is about the phone, and it does
   * not follow that everything on this one is theirs to see. A list is shared by a second, explicit
   * act — which is what makes a private list possible at all.
   */
  fun peersNotOnActiveList(state: AppState): List<TrustedPeer> {
    // Only people on it now: someone who left is exactly who might be added back.
    val already = state.activeList.people.filter { it.present }.map { it.device }.toSet()
    return state.peers.filterNot { it.deviceId in already }
  }

  /** Starts sharing the open list with a phone already paired with this one. */
  fun addPersonToActiveList(device: DeviceId) {
    val listId = state.value?.activeListId ?: return
    addPersonToList(listId, device)
  }

  /** Puts a paired phone's owner on one list, by id rather than by whichever is open. */
  fun addPersonToList(listId: ListId, device: DeviceId) = repo.update { s, node ->
    val peer = s.peerFor(device) ?: return@update s
    s.copy(
      lists =
        s.lists.map { list ->
          if (list.id != listId || list.personFor(device)?.present == true) list
          else list.withPersonAdded(device, peer.name, node.clock.tick())
        }
    )
  }

  // ── Lists ─────────────────────────────────────────────────────────────────

  var listDraft by mutableStateOf("")
    private set

  fun onListDraftChange(value: String) {
    listDraft = value
  }

  /**
   * Makes a new list, holding nobody but you.
   *
   * Sharing it is a later, separate choice — see [addPersonToActiveList]. The new list opens
   * straight away, because nobody makes a list in order to go on looking at the old one.
   */
  fun createList() {
    val name = listDraft.trim()
    if (name.isEmpty()) return
    repo.update { s, node ->
      // Minted, never derived from what is already here. A local `max + 1` meant both phones called
      // their first list `1`, so two unrelated lists could not be told apart once they met.
      val id = node.ids.nextList()
      val accent = ListAccent.entries[s.lists.size % ListAccent.entries.size]
      val you = Person(s.localDevice, s.displayName, initialOf(s.displayName))
      s.copy(lists = s.lists + GroceryList(id, name, accent, items = emptyList(), people = listOf(you)), activeListId = id)
    }
    listDraft = ""
  }

  var renamingList by mutableStateOf<ListId?>(null)
    private set

  var renameDraft by mutableStateOf("")
    private set

  fun startRename(list: GroceryList) {
    renamingList = list.id
    renameDraft = list.name
  }

  fun onRenameDraftChange(value: String) {
    renameDraft = value
  }

  fun cancelRename() {
    renamingList = null
    renameDraft = ""
  }

  /**
   * Renames a list here.
   *
   * Note the asymmetry, which is real and known: a list's name is a plain value rather than a
   * stamped [Field], so two people renaming the same list at once resolve by whoever syncs last
   * rather than by the clock. Items do better than this; the name has not needed it yet.
   */
  fun saveRename() {
    val id = renamingList ?: return
    val name = renameDraft.trim()
    if (name.isNotEmpty()) {
      repo.update { s, node ->
        // Stamped, so the rename can win against the other phone's copy rather than lose to it.
        s.copy(lists = s.lists.map { if (it.id == id) it.copy(name = name, nameAt = node.clock.tick()) else it })
      }
    }
    cancelRename()
  }

  var confirmingListDelete by mutableStateOf<ListId?>(null)
    private set

  fun askDeleteList(id: ListId) {
    confirmingListDelete = id
  }

  fun cancelDeleteList() {
    confirmingListDelete = null
  }

  /**
   * Deletes a list from this phone.
   *
   * Local only, and the wording on the screen says so: the people you shared it with keep their
   * copies. Propagating a whole-list deletion would need a tombstone for the list itself, and
   * handing one phone the power to wipe a shared list off everyone else's is not obviously right.
   *
   * The last list can go too. A new install starts with none, so no-lists is a state the app already
   * has to show; refusing to delete the only one would make the bin stop working for no reason the
   * owner can see.
   */
  fun deleteList(id: ListId) {
    repo.update { s, node ->
      val going = s.lists.firstOrNull { it.id == id }
      val shared = going != null && going.peersOf(s.localDevice).isNotEmpty()

      val lists =
        if (shared) {
          // Someone else is on it, so this is leaving rather than deleting. The list stays, hidden,
          // until every one of them has heard — see AppState.pruneDeliveredTombstones. Dropping it
          // outright would leave them waiting for ever on a phone that had simply stopped caring.
          s.lists.map { if (it.id == id) it.withPersonLeft(s.localDevice, node.clock.tick()) else it }
        } else {
          s.lists.filterNot { it.id == id }
        }

      // An empty id is never minted, so this leaves nothing active, which is what no lists means.
      val visible = lists.filter { it.personFor(s.localDevice)?.present == true }
      s.copy(
        lists = lists,
        activeListId = if (s.activeListId == id) visible.firstOrNull()?.id ?: ListId("") else s.activeListId,
      )
    }
    confirmingListDelete = null
  }

  /** Arranges this phone's Lists screen. Not stamped and never sent: the order is the owner's own. */
  fun moveList(id: ListId, toIndex: Int) = repo.update { s, _ -> s.withListMoved(id, toIndex) }

  fun setPinned(id: ListId, pinned: Boolean) = repo.update { s, _ -> s.withPinned(id, pinned) }

  // ── Items ─────────────────────────────────────────────────────────────────

  fun onDraftChange(value: String) {
    draft = value
  }

  fun submitDraft() {
    addItem(draft)
    draft = ""
  }

  // The index is rebuilt only when the words available change, not on every keystroke: the whole
  // reason for a trie here is that typing costs nothing, and rebuilding per character would hand
  // that back.
  private var vocabulary: List<String> = emptyList()
  private var trie: WordTrie = WordTrie.of(emptyList())

  /**
   * What to offer under the add field.
   *
   * Consistent spelling is the point rather than saving keystrokes. Two phones that both call it
   * "Tomatoes" can one day be told they added the same thing; "tomatos" and "Tomatoes" can only
   * ever be two items. Nothing here corrects what someone typed — a suggestion is taken only by
   * being tapped.
   */
  fun suggestions(state: AppState, limit: Int = 4): List<String> {
    val typed = draft.trim()
    if (typed.length < MIN_SUGGEST_PREFIX) return emptyList()

    val words = state.suggestionVocabulary()
    if (words !== vocabulary) {
      vocabulary = words
      trie = WordTrie.of(words)
    }

    val here = alreadyOn(state.activeList)
    val folded = WordTrie.fold(typed)
    return trie
      .matching(typed, limit = limit + here.size + 1)
      .filterNot { WordTrie.fold(it) in here }
      // Already typed in full: the add button does that, and repeating it back is noise.
      .filterNot { WordTrie.fold(it) == folded }
      .take(limit)
  }

  /** Puts a suggestion on the list, as one tap — the same bargain the staples grid makes. */
  fun takeSuggestion(name: String) {
    addItem(name)
    draft = ""
  }

  fun addItem(name: String, origin: Origin = Origin.LOCAL) {
    if (name.isBlank()) return
    repo.update { s, node -> s.withItemAdded(node, name, origin = origin) }
  }

  /**
   * Ticking something off is a change like any other: it is stamped and queued, so the other phone
   * learns about it. Note it does not reattribute the item — who put it on the list is part of its
   * identity, and only the last writer moves.
   */
  /**
   * True for as long as the burst on screen has to run. Set by the tick that finishes a list.
   *
   * Consumed by the UI rather than reset on a timer, so nothing depends on the animation's duration
   * being known in two places, and a rotation mid-flight does not replay it.
   */
  var celebrating by mutableStateOf(false)
    private set

  fun celebrationShown() {
    celebrating = false
  }

  /**
   * Puts an item at [toIndex] of the open list's [GroceryList.orderedItems].
   *
   * The order is shared, so this is stamped and travels like any other edit.
   */
  fun moveItem(itemId: ItemId, toIndex: Int) = repo.update { s, node ->
    s.withActiveList { it.withItemMoved(itemId, toIndex, node.clock.tick()) }
  }

  fun toggleChecked(itemId: ItemId) {
    var finishedTheList = false
    repo.update { s, node ->
      val stamp = node.clock.tick()
      val next =
        s.withActiveList { list ->
          list.copy(
            items =
              list.items.map {
                if (it.id != itemId) it else it.copy(checked = !it.checked, checkedAt = stamp, pending = !s.online)
              }
          )
        }
      // The *transition* into a finished list, not the state of being one: computed from before and
      // after so that unticking, re-ticking on an already-finished list, or simply opening one that
      // was finished yesterday all stay quiet. An empty list has not been finished, it is empty.
      val before = s.activeList
      val after = next.activeList
      finishedTheList =
        after.liveItems.isNotEmpty() &&
          after.liveItems.all { it.checked } &&
          before.liveItems.any { !it.checked }
      next
    }
    // Only for a tick made here. A peer finishing the list is their moment, and confetti on a phone
    // sitting in a pocket is a notification nobody asked for.
    if (finishedTheList) celebrating = true
  }

  /** The list detail is asking whether the ticked-off items really should go. */
  var confirmingClear by mutableStateOf(false)
    private set

  fun askClearChecked() {
    confirmingClear = true
  }

  fun cancelClearChecked() {
    confirmingClear = false
  }

  /**
   * Takes everything already in the trolley off the list.
   *
   * Ticking something off says you have it; it stays visible so you can see what you have got, and
   * this is what ends the shop. Tombstones rather than deletions, for the same reason a single
   * removal is: a phone that never heard about it would offer the whole trolley back.
   */
  fun clearChecked() {
    repo.update { s, node ->
      val stamp = node.clock.tick()
      s.withActiveList { list ->
        list.copy(
          items =
            list.items.map { item ->
              if (item.checked && !item.removed.value) item.copy(removed = item.removed.set(true, stamp), pending = !s.online)
              else item
            }
        )
      }
    }
    confirmingClear = false
  }

  // ── Edit sheet ────────────────────────────────────────────────────────────

  fun openEditSheet(item: GroceryItem, addedBy: String) {
    editDraft = EditDraft(item.id, item.name.value, item.qty.value, addedBy)
    sheet = Sheet.EDIT_ITEM
  }

  fun onSheetNameChange(value: String) {
    editDraft = editDraft?.copy(name = value)
  }

  fun qtyUp() {
    editDraft = editDraft?.let { it.copy(qty = it.qty + 1) }
  }

  fun qtyDown() {
    editDraft = editDraft?.let { it.copy(qty = maxOf(1, it.qty - 1)) }
  }

  fun saveSheet() {
    val d = editDraft ?: return
    repo.update { s, node ->
      val stamp = node.clock.tick()
      s.withActiveList { list ->
        list.copy(
          items =
            list.items.map { item ->
              if (item.id != d.itemId) item
              else {
                // Each field records what it was written against, so a peer can later tell an edit
                // made in knowledge of theirs from one made blind.
                val newName = d.name.trim().ifEmpty { item.name.value }
                item.copy(
                  name = if (newName == item.name.value) item.name else item.name.set(newName, stamp),
                  qty = if (d.qty == item.qty.value) item.qty else item.qty.set(d.qty, stamp),
                  editing = false,
                  pending = !s.online,
                )
              }
            }
        )
      }
    }
    closeSheet()
  }

  /** Asks first. One deliberate tap, rather than an undo trail afterwards. */
  fun askRemove() {
    confirmingRemoval = true
  }

  fun cancelRemove() {
    confirmingRemoval = false
  }

  /**
   * Takes the item off the list.
   *
   * It goes at once, here and on the other phones — no notice, nothing to dismiss. What is written
   * is a tombstone rather than a plain deletion, because an absent row and a row the other phone
   * has not heard about yet are indistinguishable, and it would come straight back.
   */
  fun confirmRemove() {
    val d = editDraft ?: return
    repo.update { s, node ->
      val stamp = node.clock.tick()
      s.withActiveList { list ->
        list.copy(
          items =
            list.items.map { item ->
              if (item.id != d.itemId) item
              else item.copy(removed = item.removed.set(true, stamp), pending = !s.online)
            }
        )
      }
    }
    closeSheet()
  }

  fun closeSheet() {
    sheet = null
    editDraft = null
    confirmingRemoval = false
  }

  // ── Staples ───────────────────────────────────────────────────────────────

  /** The "Add a staple" field on the staples screen. */
  var stapleDraft by mutableStateOf("")
    private set

  fun onStapleDraftChange(value: String) {
    stapleDraft = value
  }

  /**
   * Drops a staple onto the list being worked on, and counts it.
   *
   * The count is the tile's whole sub-label, so it has to move in the same update as the item —
   * otherwise a tap adds the thing and the tile goes on claiming it has never been used.
   */
  fun useStaple(name: String) = repo.update { s, node ->
    s.withItemAdded(node, name)
      .copy(staples = s.staples.map { if (it.name == name) it.copy(uses = it.uses + 1) else it })
  }

  /** Adds to the grid. An existing staple is left as it is rather than duplicated. */
  fun addStaple() {
    val name = stapleDraft.trim()
    if (name.isEmpty()) return
    repo.update { s, _ ->
      if (s.staples.any { it.name.equals(name, ignoreCase = true) }) s
      else s.copy(staples = s.staples + Staple(name))
    }
    stapleDraft = ""
  }

  /** Which tile is asking to be removed. Null when none is. */
  var confirmingStaple by mutableStateOf<String?>(null)
    private set

  fun askRemoveStaple(name: String) {
    confirmingStaple = name
  }

  fun cancelRemoveStaple() {
    confirmingStaple = null
  }

  /** Takes a tile off the grid. Nothing already added to a list is touched. */
  fun removeStaple(name: String) {
    repo.update { s, _ -> s.copy(staples = s.staples.filterNot { it.name == name }) }
    confirmingStaple = null
  }

  // ── Conflict ──────────────────────────────────────────────────────────────

  /**
   * One entry, the two quantities added together, authored here as a fresh merged item.
   *
   * The two originals are tombstoned rather than dropped. After a real handover they are on both
   * phones, so simply forgetting them here would leave the other phone holding them and hand them
   * straight back — and the person would have merged nothing.
   */
  fun mergeConflict() = repo.update { s, node ->
    val c = s.conflict ?: return@update s
    val stamp = node.clock.tick()
    val merged = node.newItem(s, c.itemName, qty = c.yours.qty.value + c.theirs.qty.value, origin = Origin.MERGED)
    val replaced = setOf(c.yours.id, c.theirs.id)

    s.copy(conflict = null).withActiveList { list ->
      list.copy(
        items = list.items.map { if (it.id in replaced) it.copy(removed = it.removed.set(true, stamp)) else it } + merged
      )
    }
  }

  /**
   * Both entries kept, exactly as each device made them.
   *
   * Nothing is minted: each side already carries its own creator and clock, which is the whole point
   * of items knowing who made them. Either may already be on the list — after a handover both are —
   * so only what is missing is added, and answering twice cannot produce a third copy.
   */
  fun keepBoth() = repo.update { s, node ->
    val c = s.conflict ?: return@update s
    // Both sides are already stamped by the phones that made them; folding them into this clock
    // keeps it ahead of anything it has now seen.
    node.clock.observe(c.theirs.touchedAt)
    s.copy(conflict = null).withActiveList { list ->
      val here = list.items.map { it.id }.toSet()
      list.copy(items = list.items + listOf(c.yours, c.theirs).filterNot { it.id in here })
    }
  }

  // ── Networks ──────────────────────────────────────────────────────────────

  fun toggleNetwork(fingerprint: NetworkFingerprint) = repo.update { s, _ ->
    s.copy(
      networks = s.networks.map { if (it.fingerprint == fingerprint) it.copy(approved = !it.approved) else it }
    )
  }

  /**
   * Opts into looking for peers on the network this phone is standing on.
   *
   * Only ever acts on a network that could be fingerprinted — approving one that cannot be told
   * apart from any other would opt into every network sharing its shape.
   */
  fun approveCurrentNetwork() {
    // Never while a VPN is up. The fingerprint would be the tunnel's, which is the same one this
    // phone sees in every café in the world — approving it would quietly opt into all of them.
    if (network.value.isVpn) return
    val fingerprint = network.value.fingerprint ?: return
    var wasKnown = true
    repo.update { s, _ ->
      wasKnown = s.networks.any { it.fingerprint == fingerprint }
      s.copy(
        networks =
          if (wasKnown) s.networks.map { if (it.fingerprint == fingerprint) it.copy(approved = true) else it }
          else listOf(approvedNow(fingerprint)) + s.networks
      )
    }
    // Asked here rather than left for later: this is the one moment the owner certainly knows which
    // network they just approved. Not asked at all when the phone has already told us the name —
    // demanding someone type "Kingfisher" under the word Kingfisher is ceremony, not a question.
    if (!wasKnown && wifiName.value == null) {
      namingNetwork = fingerprint
      networkNameDraft = ""
    }
  }

  private fun approvedNow(fingerprint: NetworkFingerprint) =
    ApprovedNetwork(
      fingerprint = fingerprint,
      // The network's own name if the phone will tell us it, so the common case needs no typing at
      // all. Blank without the location permission, which is what the naming prompt is for.
      name = wifiName.value.orEmpty(),
      detail = "Approved just now",
      approved = true,
    )

  /**
   * The name the wifi gives itself, or null when the phone will not say.
   *
   * Straight from the monitor, which can only learn it asynchronously — see [NetworkMonitor.wifiName].
   */
  val wifiName: StateFlow<String?> = monitor.wifiName

  /** Asks the monitor to look again. Call after the location permission is granted. */
  fun refreshWifiName() = monitor.refreshWifiName()

  init {
    // The name is wanted on every screen that names the network, not only the one that can ask for
    // the permission — asking on the Share screen alone left the Lists banner saying
    // "Network 0833af" for the rest of the session.
    //
    // Hung off `network` rather than run in the constructor body so it stays off the startup path:
    // that flow is WhileSubscribed, so nothing here happens until the UI subscribes, which is after
    // the first frame.
    viewModelScope.launch { network.collect { monitor.refreshWifiName() } }

    // Adopting the name is separate from reading it, because it arrives later than the network
    // does. Only ever fills a blank: a name somebody typed is theirs, and an SSID turning up
    // afterwards must not overwrite it — they renamed it for a reason.
    viewModelScope.launch {
      wifiName.collect { name ->
        if (name == null) return@collect
        val fingerprint = network.value.fingerprint ?: return@collect
        val known = state.value?.networks?.firstOrNull { it.fingerprint == fingerprint }
        // Checked before writing rather than inside the update: a no-op that still goes through
        // repo.update is a save this app has no reason to make.
        if (known == null || known.name.isNotBlank()) return@collect

        repo.update { s, _ ->
          s.copy(networks = s.networks.map { if (it.fingerprint == fingerprint) it.copy(name = name) else it })
        }
      }
    }
  }

  /**
   * Which network is being named, if any.
   *
   * Approving one leaves it called "Network a3f91c", because the SSID is deliberately not read —
   * that would cost a location permission for a value any other network can claim. So the only
   * name available is the one the owner gives it, and they are asked right after approving, while
   * they still know which network they meant.
   */
  var namingNetwork by mutableStateOf<NetworkFingerprint?>(null)
    private set

  var networkNameDraft by mutableStateOf("")
    private set

  fun startNamingNetwork(network: ApprovedNetwork) {
    namingNetwork = network.fingerprint
    networkNameDraft = network.name
  }

  fun onNetworkNameDraftChange(value: String) {
    networkNameDraft = value
  }

  fun cancelNamingNetwork() {
    namingNetwork = null
    networkNameDraft = ""
  }

  fun saveNetworkName() {
    val fingerprint = namingNetwork ?: return
    val name = networkNameDraft.trim()
    if (name.isNotEmpty()) {
      repo.update { s, _ ->
        s.copy(networks = s.networks.map { if (it.fingerprint == fingerprint) it.copy(name = name) else it })
      }
    }
    cancelNamingNetwork()
  }

  /** Which network row is asking to be forgotten. Null when none is. */
  var confirmingNetworkRemoval by mutableStateOf<NetworkFingerprint?>(null)
    private set

  fun askForgetNetwork(fingerprint: NetworkFingerprint) {
    confirmingNetworkRemoval = fingerprint
  }

  fun cancelForgetNetwork() {
    confirmingNetworkRemoval = null
  }

  /**
   * Drops a network from the list entirely.
   *
   * Distinct from switching it off, which is what [toggleNetwork] does: an entry switched off is
   * one the owner may want back, and it keeps the name they gave it. Forgetting is for the café
   * approved once in March — the list is a record of places this phone will talk on, and a record
   * nobody prunes stops being one worth reading.
   *
   * Nobody is unpaired by this. Which phones are trusted is settled by keys, and a network only
   * ever decides *where* they are allowed to look for each other — see the two-part trust model in
   * `docs/ARCHITECTURE.md`. Standing on a forgotten network simply offers it for approval again.
   */
  fun forgetNetwork(fingerprint: NetworkFingerprint) {
    repo.update { s, _ -> s.copy(networks = s.networks.filterNot { it.fingerprint == fingerprint }) }
    confirmingNetworkRemoval = null
    // A row being renamed as it is forgotten would otherwise leave the form open over nothing.
    if (namingNetwork == fingerprint) cancelNamingNetwork()
  }

  // ── Export / import ───────────────────────────────────────────────────────

  fun openExport() {
    copied = false
    sheet = Sheet.EXPORT
  }

  fun openImport() {
    importText = ""
    importNote = ""
    sheet = Sheet.IMPORT
  }

  fun onImportTextChange(value: String) {
    importText = value
    importNote = ""
  }

  fun markCopied() {
    copied = true
  }

  fun exportText(list: GroceryList): String = ShareCodec.encode(list)

  /** Items already on the list are left alone, so pasting the same message twice is harmless. */
  fun runImport() {
    val raw = importText.trim()
    if (raw.isEmpty()) {
      importNote = "Paste the message first."
      return
    }
    val parsed = ShareCodec.decode(raw)
    if (parsed.isEmpty()) {
      importNote = "Could not find any items in that."
      return
    }
    repo.update { s, node ->
      val have = s.activeList.liveItems.map { it.name.value.lowercase() }.toSet()
      val fresh = parsed.filterNot { it.name.lowercase() in have }
      // Each pasted item is created here, so it gets this phone's identity and a fresh id: the
      // message carries no identity of its own, by design.
      val items = fresh.map { node.newItem(s, it.name, qty = it.qty, origin = Origin.IMPORTED) }
      s.withActiveList { list -> items.lastOrNull()?.let { list.withItemsAppended(items, it.name.at) } ?: list }
    }
    importText = ""
    closeSheet()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Puts [name] on the open list, or asks for more of what is already there.
   *
   * Wanting two of something is a quantity, not two lines. Every way of adding goes through here —
   * the field, a suggestion, a staple tile — because they are the same act to the person doing it,
   * and a staple tapped twice used to leave two identical rows to reconcile by hand.
   *
   * Matching is on the folded name, the same comparison `alreadyOn` and the suggestion filter use,
   * so "eggs" finds "Eggs". A **ticked** item is not matched: it is already in the trolley, and
   * quietly raising its count would hide the new need behind a line that reads as done.
   *
   * The bump is stamped like any other field write, so it merges against a peer's edit rather than
   * overwriting it blind.
   */
  private fun AppState.withItemAdded(
    node: LocalNode,
    name: String,
    qty: Int = 1,
    origin: Origin = Origin.LOCAL,
  ): AppState {
    val trimmed = name.trim()
    val folded = WordTrie.fold(trimmed)
    val existing = activeList.liveItems.firstOrNull { !it.checked && WordTrie.fold(it.name.value) == folded }
    val state = this

    if (existing == null) {
      val item = node.newItem(state, trimmed, qty = qty, origin = origin)
      return withActiveList { it.withItemsAppended(listOf(item), item.name.at) }
    }

    val stamp = node.clock.tick()
    return withActiveList { list ->
      list.copy(
        items =
          list.items.map { item ->
            if (item.id != existing.id) item
            else item.copy(qty = item.qty.set(item.qty.value + qty, stamp), pending = !state.online)
          }
      )
    }
  }

  /**
   * Mints an item authored by this phone: a fresh id from this device's factory, and one stamp
   * shared by its fields. A newly created value has no `basedOn` — there was nothing before it.
   */
  private fun LocalNode.newItem(s: AppState, name: String, qty: Int = 1, origin: Origin = Origin.LOCAL): GroceryItem {
    val stamp = clock.tick()
    return GroceryItem(
      id = ids.nextItem(),
      name = Field(name, stamp),
      qty = Field(qty, stamp),
      checkedAt = stamp,
      removed = Field(false, stamp),
      origin = origin,
      pending = !s.online,
    )
  }

  private inline fun AppState.withActiveList(transform: (GroceryList) -> GroceryList): AppState =
    copy(lists = lists.map { if (it.id == activeListId) transform(it) else it })
}
