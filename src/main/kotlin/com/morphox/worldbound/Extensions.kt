package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.entity.UUIDComponent
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.inventory.Inventory
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.nio.file.Path
import java.util.UUID

fun Holder<EntityStore?>.isValid(): Boolean {
    return getComponent(Player.getComponentType()) != null
}

val Holder<EntityStore>.uuid: UUID
    get() {
        return getComponent(UUIDComponent.getComponentType())!!.uuid
    }

fun HytaleLogger.info(msg: String) {
    atInfo().log(msg)
}

fun HytaleLogger.warn(msg: String) {
    atWarning().log(msg)
}

fun HytaleLogger.error(msg: String) {
    atSevere().log(msg)
}


fun Holder<EntityStore>.reset() {
    val player: Player = getComponent(Player.getComponentType())!!
    val playerRef: PlayerRef = getComponent(PlayerRef.getComponentType())!!

    println("[World-Bound] Clearing entity stats..")

    //if (playerRef.holder != null) {
    //player.resetManagers(playerRef.holder!!)
    //}

    playerRef.copyStats(null)
    playerRef.copyEffects(null)

    player.inventory = Inventory()
}

/**
 *  Load player data from disk to memory
 * **/
fun Holder<EntityStore>.load(data: Holder<EntityStore?>) {
    val player: Player? = getComponent(Player.getComponentType())
    val playerOther: Player? = data.getComponent(Player.getComponentType())

    val playerRef: PlayerRef? = getComponent(PlayerRef.getComponentType())

    if (player != null && playerOther != null) {
        player.inventory = playerOther.inventory
        player.inventory.setEntity(player)
    }

    playerRef?.copyStats(data)
    playerRef?.copyEffects(data)
}

/**
 *  Copy stats from disk data
 * **/
fun PlayerRef.copyStats(other: Holder<EntityStore?>?) {
    val ref = reference
    val type = EntityStatsModule.get().entityStatMapComponentType
    val statMap: EntityStatMap = ref?.store?.getComponent(reference!!, type) ?: return

    val statMapOther: EntityStatMap? = other?.getComponent(type)

    println("[World-Bound] Loading stats..")
    val assetMap = EntityStatType.getAssetMap()
    val indexes = assetMap.assetMap.map { assetMap.getIndex(it.key) }
    for (index in indexes) {
        val valueOther = statMapOther?.get(index)
        if (valueOther == null) {
            statMap.resetStatValue(index)
        } else {
            statMap.setStatValue(index, valueOther.get())
        }
    }

    statMap.update()
}

/**
 *  Copy effects from disk data
 * **/
fun PlayerRef.copyEffects(other: Holder<EntityStore?>?) {
    val ref: Ref<EntityStore?>? = reference
    val store = ref?.getStore()
    val world = store?.getExternalData()?.world
    val effects = store?.getComponent(ref, EffectControllerComponent.getComponentType())

    if (store == null || world == null || effects == null) {
        return
    }

    println("[World-Bound] Clearing effects..")
    effects.clearEffects(ref, store)

    val effectsOther = other?.getComponent(EffectControllerComponent.getComponentType())
    if (effectsOther != null) {
        println("[World-Bound] Loading effects..")

        effects.addActiveEntityEffects(effectsOther.activeEffects.values.toTypedArray())
    }
}

fun World.getPlayerFile(playerId: UUID): Path {
    return savePath.resolve("players/${playerId}.json")
}