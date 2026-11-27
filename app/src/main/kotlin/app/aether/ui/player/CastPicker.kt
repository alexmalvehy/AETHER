// After BitChord's CastDialog (app/.../ui/components/CastDialog.kt, kushagrasinghx/BitChord; GPL-3.0):
// its live list and its rules (scanning only while it's open, closing itself once the TV chosen has
// come up), in AETHER's face.
package app.aether.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.DragState
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.player.cast.CastController

/**
 * Where the music plays: this phone, or a TV on its Wi-Fi. The list is live (TVs are found, and lost,
 * while it's open), and the TVs are looked for only while it is, which is the only time that's worth its
 * cost. Choosing one closes it once that TV has come up; the one playing says so, and "This phone"
 * brings the music back here, carrying on where the TV had got to.
 */
@Composable
internal fun CastPicker(sheet: DragState) {
    val cast by CastController.state.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    // Set by choosing a TV, so the sheet closes itself when that TV connects; not when it's opened while
    // already casting.
    var picked by remember { mutableStateOf(false) }
    val open = sheet.visible
    DisposableEffect(open, cast.supported) {
        val stop = if (open) CastController.discover(active = true) else ({})
        onDispose(stop)
    }
    LaunchedEffect(cast.connectedName, picked) {
        if (picked && cast.connectedName != null) {
            picked = false
            sheet.hide()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge).padding(bottom = 28.dp)) {
        Txt("Play on", Type.Display, modifier = Modifier.padding(start = 4.dp, top = 26.dp))
        Txt(
            when {
                cast.casting -> "Playing on ${cast.connectedName}. Its volume is the phone's volume keys."
                !cast.onLocalNetwork -> "A TV is found on the Wi-Fi it's on: put this phone on the same one."
                else -> "A TV or speaker that takes Google Cast, on this Wi-Fi."
            },
            Type.Meta,
            color = Ink.Grey,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 18.dp),
        )
        Rule()
        Place(
            glyph = Glyph.Listen,
            name = "This phone",
            state = if (!cast.casting && !cast.connecting) "Playing here" else null,
            lit = !cast.casting,
        ) {
            if (cast.casting) {
                haptics.select()
                CastController.disconnect(resumeHere = true)
                sheet.hide()
            }
        }
        cast.devices.forEach { device ->
            Place(
                glyph = Glyph.Cast,
                name = device.name,
                state = when {
                    // The route answers at once; the TV then takes a while (15 s on a slow one) to open
                    // its player: it's connecting for all of that.
                    device.connecting || (cast.connecting && device.connected) -> "Connecting"
                    cast.casting && device.connected -> "Playing here"
                    else -> device.description
                },
                lit = cast.casting && device.connected,
            ) {
                if (!device.connected) {
                    haptics.select()
                    picked = true
                    CastController.connect(device.id)
                }
            }
        }
        if (cast.devices.isEmpty() && cast.onLocalNetwork) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                GridDots()
                Spacer(Modifier.width(16.dp))
                Txt("Looking for TVs on this Wi-Fi", Type.Meta, color = Ink.Grey)
            }
        }
    }
}

/** One place the music can play: its mark, its name in the capitals, and what it's doing. */
@Composable
private fun Place(glyph: Glyph, name: String, state: String?, lit: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onClick).padding(horizontal = 4.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(glyph, size = 17.dp, color = if (lit) Ink.White else Ink.Grey)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Type.TitleSmall, color = if (lit) Ink.White else Ink.White.copy(alpha = 0.86f), maxLines = 1)
            if (state != null) Txt(state, Type.Meta, color = Ink.Grey, maxLines = 1)
        }
        if (lit) Icon(Glyph.Check, size = 15.dp, color = Ink.White)
    }
    Rule()
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
