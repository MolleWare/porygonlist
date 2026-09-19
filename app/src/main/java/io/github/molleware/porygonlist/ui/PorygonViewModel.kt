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
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.net.NetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.net.discoveryDecision
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

  fun openList(id: Long) = repo.update { s, _ -> s.copy(activeListId = id) }

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
    PairingCodec.link(identity(), displayName)

  /**
   * Accepts an invite read from a QR code or pasted in.
   *
   * The id is derived from the key inside, so a scanned code cannot claim to be a phone it is not.
   * Re-pairing an existing peer replaces its key — which is how a peer that reinstalled gets back
   * in, and also the one moment an attacker would want, so it belongs behind a deliberate scan
   * rather than anything automatic.
   */
  fun pair(code: String): PairingInvite? {
    val invite = PairingCodec.decode(code) ?: return null
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
      .filterNot { it.device == state.localDevice || it.device == pendingInvite?.deviceId }

  /**
   * The list this pairing is being done in order to share, if it was started that way.
   *
   * Pairing and sharing stay separate acts in the model, and should: trusting a phone is about the
   * phone. But "share this list with someone new" is one intention, and making a person perform it
   * as two unrelated steps — pair here, then find the list and add them — is how a feature ends up
   * looking absent. Set on the way in, honoured once, cleared.
   */
  var pairingForList by mutableStateOf<Long?>(null)
    private set

  fun startPairing(forListId: Long? = null) {
    pairingForList = forListId
    pairCode = ""
    pairNote = ""
    pendingInvite = null
  }

  /** Trusts the pasted code as a phone this one has not seen before. */
  fun pairAsNew() {
    val invite = pendingInvite ?: return
    pair(pairCode.trim())
    val listId = pairingForList
    if (listId != null) {
      addPersonToList(listId, invite.deviceId)
      pairNote = "${invite.displayName} is on ${listName(listId)}."
    } else {
      pairNote = "Paired with ${invite.displayName}."
    }
    clearPairDraft()
  }

  /** Records that [oldDevice]'s owner is now holding the phone in the pasted code. */
  fun pairAsReplacementFor(oldDevice: DeviceId) {
    val invite = pendingInvite ?: return
    replaceDevice(oldDevice, invite)
    pairNote = "${invite.displayName}'s new phone took over."
    clearPairDraft()
  }

  private fun listName(id: Long): String = state.value?.lists?.firstOrNull { it.id == id }?.name.orEmpty()

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
  fun unpair(deviceId: DeviceId) = repo.update { s, _ -> s.retireDevice(deviceId) }

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
   * Clears whatever was owed to them on that list as a consequence, which is the honest way round:
   * the tombstones go because there is nobody left to tell, not because the guarantee was waived.
   */
  fun removePersonFromActiveList(device: DeviceId) {
    repo.update { s, _ -> s.removePersonFrom(s.activeListId, device) }
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
    val already = state.activeList.people.map { it.device }.toSet()
    return state.peers.filterNot { it.deviceId in already }
  }

  /** Starts sharing the open list with a phone already paired with this one. */
  fun addPersonToActiveList(device: DeviceId) {
    val listId = state.value?.activeListId ?: return
    addPersonToList(listId, device)
  }

  /** Puts a paired phone's owner on one list, by id rather than by whichever is open. */
  fun addPersonToList(listId: Long, device: DeviceId) = repo.update { s, _ ->
    val peer = s.peerFor(device) ?: return@update s
    s.copy(
      lists =
        s.lists.map { list ->
          if (list.id != listId || list.people.any { it.device == device }) list
          else list.copy(people = list.people + Person(device, peer.name, initialOf(peer.name)))
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
    repo.update { s, _ ->
      val id = (s.lists.maxOfOrNull { it.id } ?: 0L) + 1
      val accent = ListAccent.entries[s.lists.size % ListAccent.entries.size]
      val you = Person(s.localDevice, s.displayName, initialOf(s.displayName))
      s.copy(lists = s.lists + GroceryList(id, name, accent, items = emptyList(), people = listOf(you)), activeListId = id)
    }
    listDraft = ""
  }

  var renamingList by mutableStateOf<Long?>(null)
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
      repo.update { s, _ -> s.copy(lists = s.lists.map { if (it.id == id) it.copy(name = name) else it }) }
    }
    cancelRename()
  }

  var confirmingListDelete by mutableStateOf<Long?>(null)
    private set

  fun askDeleteList(id: Long) {
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
   * The last list is never deleted — the app has no state that shows no list at all.
   */
  fun deleteList(id: Long) {
    repo.update { s, _ ->
      if (s.lists.size <= 1) return@update s
      val remaining = s.lists.filterNot { it.id == id }
      s.copy(lists = remaining, activeListId = if (s.activeListId == id) remaining.first().id else s.activeListId)
    }
    confirmingListDelete = null
  }

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
    repo.update { s, node ->
      s.withActiveList { it.copy(items = it.items + node.newItem(s, name.trim(), origin = origin)) }
    }
  }

  /**
   * Ticking something off is a change like any other: it is stamped and queued, so the other phone
   * learns about it. Note it does not reattribute the item — who put it on the list is part of its
   * identity, and only the last writer moves.
   */
  fun toggleChecked(itemId: ItemId) = repo.update { s, node ->
    val stamp = node.clock.tick()
    s.withActiveList { list ->
      list.copy(
        items =
          list.items.map {
            if (it.id != itemId) it else it.copy(checked = !it.checked, checkedAt = stamp, pending = !s.online)
          }
      )
    }
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
    s.withActiveList { it.copy(items = it.items + node.newItem(s, name.trim())) }
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
    if (!wasKnown && wifiName == null) {
      namingNetwork = fingerprint
      networkNameDraft = ""
    }
  }

  private fun approvedNow(fingerprint: NetworkFingerprint) =
    ApprovedNetwork(
      fingerprint = fingerprint,
      // The network's own name if the phone will tell us it, so the common case needs no typing at
      // all. Blank without the location permission, which is what the naming prompt is for.
      name = wifiName.orEmpty(),
      detail = "Approved just now",
      approved = true,
    )

  /**
   * The name the wifi gives itself, or null when the phone will not say.
   *
   * Held rather than read inline because reading it touches the framework, and composition is not
   * the place for that. Refreshed by [refreshWifiName] when the screen that shows it opens and
   * after the permission is asked for.
   */
  var wifiName by mutableStateOf<String?>(null)
    private set

  /**
   * Re-reads the name, and adopts it for the current network if that network has none.
   *
   * The adoption only ever fills a blank. A name somebody typed is theirs, and an SSID turning up
   * later must not overwrite it — they renamed it for a reason.
   */
  fun refreshWifiName() {
    wifiName = monitor.currentWifiName()
    val name = wifiName ?: return
    val fingerprint = network.value.fingerprint ?: return

    repo.update { s, _ ->
      val known = s.networks.firstOrNull { it.fingerprint == fingerprint }
      if (known == null || known.name.isNotBlank()) s
      else s.copy(networks = s.networks.map { if (it.fingerprint == fingerprint) it.copy(name = name) else it })
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
      s.withActiveList { it.copy(items = it.items + items) }
    }
    importText = ""
    closeSheet()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

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
