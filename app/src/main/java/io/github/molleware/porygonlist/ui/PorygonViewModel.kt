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
import io.github.molleware.porygonlist.data.ListRepository
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.data.crypto.PairingInvite
import io.github.molleware.porygonlist.data.LocalNode
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.ShareCodec
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
  networkMonitor: NetworkMonitor,
  private val identity: LocalIdentity,
) : ViewModel() {

  val state: StateFlow<AppState?> = repo.state

  /**
   * The link this phone is on right now.
   *
   * Live observation, never persisted: which wifi you are standing on is not a fact about your
   * lists, and a stale copy read back at launch would be worse than none.
   */
  val network: StateFlow<NetworkSnapshot> =
    networkMonitor.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkSnapshot.Offline)

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
   */
  fun invite(displayName: String = state.value?.displayName.orEmpty()): String =
    PairingCodec.encode(identity, displayName)

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
    if (invite.deviceId == identity.deviceId) return null

    repo.update { s, _ ->
      val peer = TrustedPeer(invite.deviceId, invite.publicKey, invite.displayName, System.currentTimeMillis())
      s.copy(peers = s.peers.filterNot { it.deviceId == invite.deviceId } + peer)
    }
    return invite
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

  // ── Items ─────────────────────────────────────────────────────────────────

  fun onDraftChange(value: String) {
    draft = value
  }

  fun submitDraft() {
    addItem(draft)
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

  // ── Conflict ──────────────────────────────────────────────────────────────

  /** One entry, the two quantities added together, authored here as a fresh merged item. */
  fun mergeConflict() = repo.update { s, node ->
    val c = s.conflict ?: return@update s
    val merged = node.newItem(s, c.itemName, qty = c.yours.qty.value + c.theirs.qty.value, origin = Origin.MERGED)
    s.copy(conflict = null).withActiveList { it.copy(items = it.items + merged) }
  }

  /**
   * Both entries kept, exactly as each device made them.
   *
   * Nothing is minted here: each side already carries its own creator and clock, so they can go
   * onto the list untouched — which is the whole point of items knowing who made them.
   */
  fun keepBoth() = repo.update { s, node ->
    val c = s.conflict ?: return@update s
    // Both sides are already stamped by the phones that made them; folding them into this clock
    // keeps it ahead of anything it has now seen.
    node.clock.observe(c.theirs.touchedAt)
    s.copy(conflict = null).withActiveList { it.copy(items = it.items + c.yours + c.theirs) }
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
    val fingerprint = network.value.fingerprint ?: return
    repo.update { s, _ ->
      val known = s.networks.any { it.fingerprint == fingerprint }
      s.copy(
        networks =
          if (known) s.networks.map { if (it.fingerprint == fingerprint) it.copy(approved = true) else it }
          else listOf(approvedNow(fingerprint)) + s.networks
      )
    }
  }

  private fun approvedNow(fingerprint: NetworkFingerprint) =
    ApprovedNetwork(fingerprint = fingerprint, name = "", detail = "Approved just now", approved = true)

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
