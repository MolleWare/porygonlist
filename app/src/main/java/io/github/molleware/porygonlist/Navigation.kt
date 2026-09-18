package io.github.molleware.porygonlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Neutral900
import io.github.molleware.porygonlist.ui.PorygonViewModel
import io.github.molleware.porygonlist.ui.Sheet
import io.github.molleware.porygonlist.ui.components.EditItemSheet
import io.github.molleware.porygonlist.ui.components.ExportSheet
import io.github.molleware.porygonlist.ui.components.ImportSheet
import io.github.molleware.porygonlist.ui.components.Tab
import io.github.molleware.porygonlist.ui.components.TabBar
import io.github.molleware.porygonlist.ui.itemSubLabel
import io.github.molleware.porygonlist.ui.partnerName
import io.github.molleware.porygonlist.ui.screens.ListDetailScreen
import io.github.molleware.porygonlist.ui.screens.ListsScreen
import io.github.molleware.porygonlist.ui.screens.NameScreen
import io.github.molleware.porygonlist.ui.screens.ShareScreen
import io.github.molleware.porygonlist.ui.screens.ShopScreen
import io.github.molleware.porygonlist.ui.screens.StaplesScreen

@Composable
fun MainNavigation() {
  val context = LocalContext.current
  val viewModel: PorygonViewModel = viewModel { PorygonViewModel(Graph.listRepository(context), Graph.networkMonitor(context), Graph.identity()) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val networkSnapshot by viewModel.network.collectAsStateWithLifecycle()
  val discovery by viewModel.discovery.collectAsStateWithLifecycle()

  // What to call the network in passing. The owner's own name for it where there is one, otherwise
  // the fingerprint's short form — never an SSID, which is not read.
  val networkLabel =
    state?.networks?.firstOrNull { it.fingerprint == networkSnapshot.fingerprint }?.label
      ?: networkSnapshot.fingerprint?.let { "Network ${it.short}" }
      ?: "this network"

  val backStack = rememberNavBackStack(Lists)
  val current = backStack.lastOrNull() ?: Lists
  val shopping = current is Shop

  // Null until the stored state has been read. Painting the ground colour straight away means the
  // first frame is the app's own background rather than a white flash.
  Box(Modifier.fillMaxSize().background(if (shopping) Neutral900 else Bg)) {
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

    // Back closes an open sheet before it touches navigation.
    BackHandler(enabled = viewModel.sheet != null) { viewModel.closeSheet() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
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
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<ListDetail> {
                ListDetailScreen(
                  state = appState,
                  draft = viewModel.draft,
                  onDraftChange = viewModel::onDraftChange,
                  onSubmitDraft = viewModel::submitDraft,
                  onAddItem = { viewModel.addItem(it) },
                  onToggleChecked = viewModel::toggleChecked,
                  onEditItem = { item ->
                    viewModel.openEditSheet(item, itemSubLabel(item, appState.activeList, appState.localDevice))
                  },
                  onMerge = viewModel::mergeConflict,
                  onKeepBoth = viewModel::keepBoth,
                  onBack = { backStack.goTo(Lists) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Shop> {
                ShopScreen(
                  state = appState,
                  onToggleChecked = viewModel::toggleChecked,
                  onLeave = { backStack.goTo(ListDetail) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Staples> {
                StaplesScreen(
                  activeListName = appState.activeList.name,
                  onAdd = { viewModel.addItem(it) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
              entry<Share> {
                ShareScreen(
                  state = appState,
                  network = networkSnapshot,
                  discovery = discovery,
                  onApproveCurrent = viewModel::approveCurrentNetwork,
                  onToggleNetwork = viewModel::toggleNetwork,
                  onExport = viewModel::openExport,
                  onImport = viewModel::openImport,
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

      TabBar(
        current = current.toTab(),
        onSelect = { tab -> backStack.goTo(tab.toKey()) },
        dark = shopping,
      )
    }

    // The sheets sit above everything, bottom bar included, exactly as the overlay does in the design.
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
      when (viewModel.sheet) {
        Sheet.EDIT_ITEM ->
          viewModel.editDraft?.let { draft ->
            EditItemSheet(
              draft = draft,
              syncNote =
                if (appState.online) "${appState.activeList.partnerName(appState.localDevice)} sees this the moment you save."
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
  }
}

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
