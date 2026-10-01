package io.github.molleware.porygonlist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.ui.PorygonViewModel
import io.github.molleware.porygonlist.ui.Sheet
import io.github.molleware.porygonlist.ui.components.Confetti
import io.github.molleware.porygonlist.ui.components.EditItemSheet
import io.github.molleware.porygonlist.ui.components.ExportSheet
import io.github.molleware.porygonlist.ui.components.ImportSheet
import io.github.molleware.porygonlist.ui.components.Tab
import io.github.molleware.porygonlist.ui.components.TabBar
import io.github.molleware.porygonlist.ui.itemSubLabel
import io.github.molleware.porygonlist.ui.networkLabel
import io.github.molleware.porygonlist.ui.others
import io.github.molleware.porygonlist.ui.partnerName
import io.github.molleware.porygonlist.ui.screens.ListDetailScreen
import io.github.molleware.porygonlist.ui.screens.ListsScreen
import io.github.molleware.porygonlist.ui.screens.NameScreen
import io.github.molleware.porygonlist.ui.screens.PairScreen
import io.github.molleware.porygonlist.ui.screens.SettingsScreen
import io.github.molleware.porygonlist.ui.screens.ShareScreen
import io.github.molleware.porygonlist.ui.screens.ShopScreen
import io.github.molleware.porygonlist.ui.screens.StaplesScreen

@Composable
fun MainNavigation(pairLink: String? = null, onPairLinkHandled: () -> Unit = {}) {
  val context = LocalContext.current
  val viewModel: PorygonViewModel =
    viewModel {
      PorygonViewModel(
        repo = Graph.listRepository(context),
        monitor = Graph.networkMonitor(context),
        identity = Graph::identity,
        deleteIdentity = Graph::deleteIdentity,
        peerDiscovery = Graph.peerDiscovery(context),
        endpoint = Graph.syncEndpoint(),
        syncCoordinator = Graph.syncCoordinator(context),
      )
    }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val networkSnapshot by viewModel.network.collectAsStateWithLifecycle()
  val discovery by viewModel.discovery.collectAsStateWithLifecycle()
  val wifiName by viewModel.wifiName.collectAsStateWithLifecycle()

  // What to call the network in passing: the owner's own name for it, then the wifi's own name if
  // the optional permission allows reading it, then the fingerprint's short form.
  val networkLabel =
    networkLabel(
      known = state?.networks?.firstOrNull { it.fingerprint == networkSnapshot.fingerprint },
      snapshot = networkSnapshot,
      wifiName = wifiName,
    )

  val backStack = rememberNavBackStack(Lists)
  val current = backStack.lastOrNull() ?: Lists

  // Null until the stored state has been read. Painting the ground colour straight away means the
  // first frame is the app's own background rather than a white flash.
  Box(Modifier.fillMaxSize().background(Bg)) {
    val appState = state ?: return@Box

    // First run. Deliberately not a destination: there is no back stack entry, no tab bar and
    // nothing to skip past, because every screen behind this one attributes items to a person and
    // has no name to do it with yet.
    if (!appState.named) {
      NameScreen(
        name = viewModel.nameDraft,
        onNameChange = viewModel::onNameDraftChange,
        onContinue = viewModel::saveName,
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      )
      return@Box
    }

    // A pairing link opened from outside — a camera app that read someone's QR code, or a tap on a
    // link in a message. It fills the field and opens the screen; it does not pair. The person still
    // answers "is this someone new, or a replacement?" exactly as they would for a pasted code,
    // which is what keeps an intent from any app on the phone from being an act of trust.
    //
    // Sits below the first-run gate deliberately: until there is a name, there is nobody to pair as,
    // so the link waits rather than being dropped.
    if (pairLink != null) {
      LaunchedEffect(pairLink) {
        viewModel.startPairing()
        viewModel.onPairCodeChange(pairLink)
        backStack.goTo(PairPhone)
        onPairLinkHandled()
      }
    }

    // Back closes an open sheet before it touches navigation.
    BackHandler(enabled = viewModel.sheet != null) { viewModel.closeSheet() }

    // Everything except the keyboard. The shell is inset for the status and navigation bars and for
    // a cutout, but deliberately *not* for the IME: padding the whole app by the keyboard's height
    // lifts the tab bar into the middle of the screen and leaves a band of ground under it. The bar
    // belongs at the bottom of the window, with the keyboard simply covering it.
    val shellInsets =
      WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)
        .exclude(WindowInsets.ime)

    // The keyboard covers the foot of the window, so the tab bar steps out of the layout while it
    // is up rather than being pushed above it. Leaving it in and padding around it is what put a
    // band of empty ground between the last row and the keys: the bar's height and the navigation
    // bar were both being counted twice, once as layout and again as the keyboard's inset.
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(Modifier.fillMaxSize().windowInsetsPadding(shellInsets).consumeWindowInsets(shellInsets)) {
      // The scrolling area is what yields to the keyboard: a field at the foot of a screen ends up
      // directly above the keys, with nothing between them.
      Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
        NavDisplay(
          backStack = backStack,
          // Never pop the last entry: an empty back stack has nothing to display.
          onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
          entryProvider =
            entryProvider {
              entry<Lists> {
                ListsScreen(
                  state = appState,
                  networkLabel = networkLabel,
                  onOpenList = { id ->
                    viewModel.openList(id)
                    backStack.add(ListDetail)
                  },
                  onToggleOnline = viewModel::toggleOnline,
                  onGoShare = { backStack.goTo(Share) },
                  onOpenSettings = { backStack.add(Settings) },
                  draft = viewModel.listDraft,
                  onDraftChange = viewModel::onListDraftChange,
                  onCreateList = viewModel::createList,
                  onShareList = { id ->
                    viewModel.openList(id)
                    backStack.goTo(Share)
                  },
                  renamingList = viewModel.renamingList,
                  renameDraft = viewModel.renameDraft,
                  onStartRename = viewModel::startRename,
                  onRenameDraftChange = viewModel::onRenameDraftChange,
                  onSaveRename = viewModel::saveRename,
                  onCancelRename = viewModel::cancelRename,
                  confirmingDelete = viewModel.confirmingListDelete,
                  onAskDelete = viewModel::askDeleteList,
                  onCancelDelete = viewModel::cancelDeleteList,
                  onDelete = viewModel::deleteList,
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<ListDetail> {
                ListDetailScreen(
                  state = appState,
                  draft = viewModel.draft,
                  onDraftChange = viewModel::onDraftChange,
                  onSubmitDraft = viewModel::submitDraft,
                  suggestions = viewModel.suggestions(appState),
                  onAddItem = viewModel::takeSuggestion,
                  onToggleChecked = viewModel::toggleChecked,
                  onEditItem = { item ->
                    viewModel.openEditSheet(item, itemSubLabel(item, appState.activeList, appState.localDevice))
                  },
                  onMerge = viewModel::mergeConflict,
                  onKeepBoth = viewModel::keepBoth,
                  onShare = { backStack.goTo(Share) },
                  confirmingClear = viewModel.confirmingClear,
                  onAskClear = viewModel::askClearChecked,
                  onCancelClear = viewModel::cancelClearChecked,
                  onClearChecked = viewModel::clearChecked,
                  onBack = { backStack.goTo(Lists) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Shop> {
                ShopScreen(
                  state = appState,
                  onToggleChecked = viewModel::toggleChecked,
                  // The same four as the list screen, deliberately: clearing the trolley is one
                  // act with one piece of state, reachable from either place it makes sense.
                  confirmingClear = viewModel.confirmingClear,
                  onAskClear = viewModel::askClearChecked,
                  onCancelClear = viewModel::cancelClearChecked,
                  onClearChecked = viewModel::clearChecked,
                  onLeave = { backStack.goTo(ListDetail) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Staples> {
                StaplesScreen(
                  staples = appState.staples,
                  activeListName = appState.activeList.name,
                  draft = viewModel.stapleDraft,
                  onDraftChange = viewModel::onStapleDraftChange,
                  onAddStaple = viewModel::addStaple,
                  onUse = viewModel::useStaple,
                  confirmingRemoval = viewModel.confirmingStaple,
                  onAskRemove = viewModel::askRemoveStaple,
                  onCancelRemove = viewModel::cancelRemoveStaple,
                  onRemove = viewModel::removeStaple,
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Settings> {
                SettingsScreen(
                  state = appState,
                  deviceId = viewModel.deviceId,
                  nameDraft = viewModel.nameDraft,
                  onNameDraftChange = viewModel::onNameDraftChange,
                  onSaveName = viewModel::saveName,
                  onPair = {
                    viewModel.startPairing()
                    backStack.add(PairPhone)
                  },
                  onUnpair = viewModel::unpair,
                  confirmingDelete = viewModel.confirmingIdentityDelete,
                  onAskDelete = viewModel::askDeleteIdentity,
                  onCancelDelete = viewModel::cancelDeleteIdentity,
                  onConfirmDelete = viewModel::confirmDeleteIdentity,
                  onBack = { backStack.goTo(Lists) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<PairPhone> {
                PairScreen(
                  invite = viewModel.invite(),
                  textInvite = viewModel.textInvite(),
                  code = viewModel.pairCode,
                  onCodeChange = viewModel::onPairCodeChange,
                  note = viewModel.pairNote,
                  sharingList = viewModel.pairingForList?.let { id -> appState.lists.firstOrNull { it.id == id }?.name },
                  pendingName = viewModel.pendingInvite?.displayName,
                  justPairedName = viewModel.justPaired?.displayName,
                  onUndoJustPaired = viewModel::undoJustPaired,
                  onJustPairedIsReplacementFor = viewModel::justPairedIsReplacementFor,
                  listening = viewModel.reachableForPairing,
                  handingBack = viewModel.handingBack,
                  replaceCandidates = viewModel.replaceCandidates(appState),
                  onPairAsNew = viewModel::pairAsNew,
                  onReplace = viewModel::pairAsReplacementFor,
                  onBack = {
                    // Closes the socket and burns the token. Leaving by the back gesture goes
                    // through here too, so there is no way off this screen that leaves it open.
                    viewModel.stopPairing()
                    if (backStack.size > 1) backStack.removeLastOrNull() else backStack.goTo(Settings)
                  },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Share> {
                // Read once per visit rather than held: the permission can be revoked from settings
                // while the app is alive, and a cached "granted" would leave the offer hidden with
                // no way back to it.
                var nameGranted by remember { mutableStateOf(hasFineLocation(context)) }
                val askForName =
                  rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    nameGranted = granted
                    // Re-read straight away. Granting a permission does not make a network callback
                    // fire, so without this the name would not appear until the wifi changed.
                    if (granted) viewModel.refreshWifiName()
                  }

                ShareScreen(
                  state = appState,
                  network = networkSnapshot,
                  discovery = discovery,
                  onApproveCurrent = viewModel::approveCurrentNetwork,
                  onToggleNetwork = viewModel::toggleNetwork,
                  onExport = viewModel::openExport,
                  onImport = viewModel::openImport,
                  wifiName = wifiName,
                  // Nothing to offer when it is already granted, or when there is no wifi for a
                  // name to belong to.
                  canAskForWifiName = !nameGranted && networkSnapshot.isWifi,
                  onAskForWifiName = { askForName.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                  namingNetwork = viewModel.namingNetwork,
                  networkNameDraft = viewModel.networkNameDraft,
                  onNetworkNameDraftChange = viewModel::onNetworkNameDraftChange,
                  onStartNamingNetwork = viewModel::startNamingNetwork,
                  confirmingNetworkRemoval = viewModel.confirmingNetworkRemoval,
                  onAskForgetNetwork = viewModel::askForgetNetwork,
                  onCancelForgetNetwork = viewModel::cancelForgetNetwork,
                  onForgetNetwork = viewModel::forgetNetwork,
                  onSaveNetworkName = viewModel::saveNetworkName,
                  onCancelNamingNetwork = viewModel::cancelNamingNetwork,
                  pairablePeers = viewModel.peersNotOnActiveList(appState),
                  onAddPerson = viewModel::addPersonToActiveList,
                  onGoPair = {
                    viewModel.startPairing(forListId = appState.activeListId)
                    backStack.add(PairPhone)
                  },
                  onOpenPairedPhones = { backStack.add(Settings) },
                  confirmingRemovalOf = viewModel.confirmingRemovalOf,
                  onAskRemovePerson = viewModel::askRemovePerson,
                  onCancelRemovePerson = viewModel::cancelRemovePerson,
                  onRemovePerson = viewModel::removePersonFromActiveList,
                  onBack = { backStack.goTo(Lists) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
            },
        )
      }

      if (!keyboardUp) {
        TabBar(current = current.toTab(), onSelect = { tab -> backStack.goTo(tab.toKey()) })
      }
    }

    // The sheets sit above everything, bottom bar included, exactly as the overlay does in the design.
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
      when (viewModel.sheet) {
        Sheet.EDIT_ITEM ->
          viewModel.editDraft?.let { draft ->
            EditItemSheet(
              draft = draft,
              syncNote =
                // Nobody on the list means nobody to see it, whatever the network is doing.
                if (appState.activeList.others(appState.localDevice).isEmpty()) "Saved on this phone."
                else if (appState.online) "${appState.activeList.partnerName(appState.localDevice)} sees this the moment you save."
                else "Saved here now, handed over next time you share a network.",
              onNameChange = viewModel::onSheetNameChange,
              onQtyUp = viewModel::qtyUp,
              onQtyDown = viewModel::qtyDown,
              confirmingRemoval = viewModel.confirmingRemoval,
              onSave = viewModel::saveSheet,
              onAskRemove = viewModel::askRemove,
              onCancelRemove = viewModel::cancelRemove,
              onConfirmRemove = viewModel::confirmRemove,
              onDismiss = viewModel::closeSheet,
            )
          }
        Sheet.EXPORT ->
          ExportSheet(
            text = viewModel.exportText(appState.activeList),
            remaining = appState.activeList.items.count { !it.checked },
            copied = viewModel.copied,
            onCopied = viewModel::markCopied,
            onDismiss = viewModel::closeSheet,
          )
        Sheet.IMPORT ->
          ImportSheet(
            text = viewModel.importText,
            note = viewModel.importNote,
            listName = appState.activeList.name,
            onTextChange = viewModel::onImportTextChange,
            onAdd = viewModel::runImport,
            onDismiss = viewModel::closeSheet,
          )
        null -> Unit
      }
    }

    // Above everything, including an open sheet, and not inside any one screen: the list can be
    // finished from the list itself or from shopping mode, and the burst should not be clipped to
    // whichever one happened to be on top.
    if (viewModel.celebrating) {
      Confetti(onFinished = viewModel::celebrationShown, modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * Whether the app may read the wifi's name.
 *
 * The only permission this app asks for at runtime, and it buys a label and nothing else — see the
 * manifest and `WifiName`. Declining it costs nothing but the name.
 */
private fun hasFineLocation(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED

/** Switching tabs replaces the stack rather than piling destinations up behind the bar. */
private fun androidx.navigation3.runtime.NavBackStack<NavKey>.goTo(key: NavKey) {
  clear()
  add(key)
}

private fun NavKey.toTab(): Tab =
  when (this) {
    is Shop -> Tab.SHOP
    is Staples -> Tab.STAPLES
    is Share -> Tab.SHARE
    // A list you have opened still belongs to the Lists tab.
    else -> Tab.LISTS
  }

private fun Tab.toKey(): NavKey =
  when (this) {
    Tab.LISTS -> Lists
    Tab.SHOP -> Shop
    Tab.STAPLES -> Staples
    Tab.SHARE -> Share
  }
