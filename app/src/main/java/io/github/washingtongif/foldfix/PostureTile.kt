package io.github.washingtongif.foldfix

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to cycle Auto → Tabletop → Tent → Auto. */
class PostureTile : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        FoldService.setPosture(this, FoldService.nextPosture(FoldService.posture))
        // Show the new posture straight away; the service confirms it a moment later.
        render(FoldService.nextPosture(FoldService.posture))
    }

    private fun render(p: String = FoldService.posture) {
        val tile = qsTile ?: return
        tile.label = "Fold: ${FoldService.label(p)}"
        tile.subtitle = if (FoldService.running) "FoldFix" else "FoldFix (off)"
        tile.state = if (p == "auto") Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}

object TileUpdater {
    fun refresh(ctx: Context) = try {
        TileService.requestListeningState(ctx, ComponentName(ctx, PostureTile::class.java))
    } catch (e: Throwable) {
    }
}
