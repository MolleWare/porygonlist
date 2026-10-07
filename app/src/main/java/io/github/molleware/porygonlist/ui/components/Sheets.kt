package io.github.molleware.porygonlist.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Divider
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.SheetScrim
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.EditDraft
import kotlinx.coroutines.launch

/**
 * The shared bottom-sheet frame: a scrim that dismisses on tap, and a panel pinned to the bottom
 * with rounded top corners.
 */
@Composable
fun PorygonBottomSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(modifier.fillMaxSize().background(SheetScrim)) {
    ScrimDismissArea(onDismiss)
    Column(
      Modifier.align(Alignment.BottomCenter)
        .fillMaxWidth()
        .shadow(Elevation.Lg, Shapes.Sheet, ambientColor = ShadowInk, spotColor = ShadowInk)
        .clip(Shapes.Sheet)
        .background(Bg)
        // Swallow taps so they do not reach the scrim underneath.
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 26.dp)
    ) {
      SheetGrabber(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
      content()
    }
  }
}

/** Rename an item and set how many. Reached by long-press, or by the pencil. */
@Composable
fun EditItemSheet(
  draft: EditDraft,
  syncNote: String,
  /** Under the remove confirmation: whose phones it goes from, named for the people on the list. */
  removeNote: String,
  confirmingRemoval: Boolean,
  onNameChange: (String) -> Unit,
  onQtyUp: () -> Unit,
  onQtyDown: () -> Unit,
  onSave: () -> Unit,
  onAskRemove: () -> Unit,
  onCancelRemove: () -> Unit,
  onConfirmRemove: () -> Unit,
  onDismiss: () -> Unit,
) {
  PorygonBottomSheet(onDismiss) {
    Text("Edit item", style = PorygonType.SheetTitle, color = TextInk, modifier = Modifier.padding(bottom = 3.dp))
    Text(
      "Added by ${draft.addedBy}",
      style = PorygonType.Meta.copy(fontSize = PorygonType.Fine.fontSize),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 16.dp),
    )

    PorygonTextField(
      value = draft.name,
      onValueChange = onNameChange,
      placeholder = "Item name",
      modifier = Modifier.fillMaxWidth(),
      onSubmit = onSave,
    )

    Row(
      Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("How many", style = PorygonType.RowName.copy(fontSize = PorygonType.BodyLarge.fontSize), color = TextInk)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconActionButton(
          IconPaths.MINUS,
          contentDescription = "Fewer",
          onClick = onQtyDown,
          size = 40.dp,
          iconSize = 16.dp,
          background = androidx.compose.ui.graphics.Color.Transparent,
          tint = TextInk,
          border = Divider,
        )
        Text(
          draft.qty.toString(),
          style = PorygonType.QtyValue,
          color = TextInk,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(min = 26.dp),
        )
        IconActionButton(
          IconPaths.PLUS,
          contentDescription = "More",
          onClick = onQtyUp,
          size = 40.dp,
          iconSize = 16.dp,
          background = androidx.compose.ui.graphics.Color.Transparent,
          tint = TextInk,
          border = Divider,
        )
      }
    }

    if (confirmingRemoval) {
      // Asked once, at the moment it matters. A grocery list does not need an undo trail — it
      // needs the tap to have been deliberate.
      Text(
        "Take ${draft.name.ifBlank { "this" }} off the list?",
        style = PorygonType.RowName.copy(fontSize = PorygonType.BodyLarge.fontSize),
        color = TextInk,
        modifier = Modifier.padding(bottom = 10.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Remove", onConfirmRemove, modifier = Modifier.weight(1f).heightIn(min = 46.dp))
        SecondaryButton("Keep it", onCancelRemove, modifier = Modifier.heightIn(min = 46.dp))
      }
      Text(
        removeNote,
        style = PorygonType.Fine,
        color = Neutral700,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Save", onSave, modifier = Modifier.weight(1f).heightIn(min = 46.dp))
        SecondaryButton(
          "Remove",
          onAskRemove,
          modifier = Modifier.heightIn(min = 46.dp),
          contentColor = Accent2800,
          style = PorygonType.Body,
        )
      }
      Text(
        syncNote,
        style = PorygonType.Fine,
        color = Neutral700,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
      )
    }
  }
}

/** Hand the list over as one message, for when there is no shared network in reach. */
@Composable
fun ExportSheet(text: String, remaining: Int, copied: Boolean, onCopied: () -> Unit, onDismiss: () -> Unit) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  PorygonBottomSheet(onDismiss) {
    Text("Send as a text", style = PorygonType.SheetTitle, color = TextInk, modifier = Modifier.padding(bottom = 3.dp))
    Text(
      "$remaining items still to get · ${text.length} characters",
      style = PorygonType.Meta.copy(fontSize = PorygonType.Fine.fontSize),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 14.dp),
    )
    Box(
      Modifier.fillMaxWidth()
        .heightIn(max = 190.dp)
        .clip(Shapes.Panel)
        .background(Surface)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
      Text(text, style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.6), color = TextInk)
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      PrimaryButton(
        if (copied) "Copied" else "Copy the message",
        onClick = {
          scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Porygonlist", text))) }
          onCopied()
        },
        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
      )
      SecondaryButton("Done", onDismiss, modifier = Modifier.heightIn(min = 46.dp))
    }
  }
}

/** Take a message someone sent and fold it into the list. */
@Composable
fun ImportSheet(
  text: String,
  note: String,
  listName: String,
  onTextChange: (String) -> Unit,
  onAdd: () -> Unit,
  onDismiss: () -> Unit,
) {
  PorygonBottomSheet(onDismiss) {
    Text("Paste a list", style = PorygonType.SheetTitle, color = TextInk, modifier = Modifier.padding(bottom = 3.dp))
    Text(
      "Paste a message someone sent you.",
      style = PorygonType.Meta.copy(fontSize = PorygonType.Fine.fontSize),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 14.dp),
    )
    PorygonTextField(
      value = text,
      onValueChange = onTextChange,
      placeholder = "PL1 · Weekly shop · 2 milk, bread…",
      modifier = Modifier.fillMaxWidth(),
      textStyle = PorygonType.BodyLarge,
      minHeight = 110.dp,
      shape = Shapes.Panel,
      singleLine = false,
      imeAction = ImeAction.Default,
    )
    Text(
      note,
      style = PorygonType.Fine,
      color = Accent700,
      modifier = Modifier.fillMaxWidth().heightIn(min = 17.dp).padding(top = 8.dp, bottom = 14.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      PrimaryButton(
        if (listName.isBlank()) "Add" else "Add to $listName",
        onAdd,
        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
      )
      SecondaryButton("Cancel", onDismiss, modifier = Modifier.heightIn(min = 46.dp))
    }
  }
}
