package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.server.core.entity.UUIDComponent
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.nio.file.Path
import java.util.UUID

fun World.getFile(playerId: UUID): Path {
    return savePath.resolve("players/${playerId}.json")
}

fun PlayerReadyEvent.playerUuid(): UUID {
    return playerRef.store.getComponent(playerRef, UUIDComponent.getComponentType())!!.uuid
}

val Holder<EntityStore>.uuid: UUID
    get() {
        return getComponent(UUIDComponent.getComponentType())!!.uuid
    }
