package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.entity.UUIDComponent
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent
import com.hypixel.hytale.server.core.inventory.Inventory
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

@Suppress("DEPRECATION")
class WorldBound(init: JavaPluginInit) : JavaPlugin(init) {
    private val storage = SplitStorage()

    init {
        LOGGER.atInfo().log("[World-Bound] Running on version ${manifest.version}!")
    }

    override fun setup() {
        LOGGER.atInfo().log("[World-Bound] Setting up..")
        eventRegistry.registerGlobal(AllWorldsLoadedEvent::class.java, ::onStart)
        eventRegistry.registerGlobal(PlayerReadyEvent::class.java, ::onReady)
        eventRegistry.registerGlobal(DrainPlayerFromWorldEvent::class.java, ::onLeave)
        eventRegistry.registerGlobal(PlayerDisconnectEvent::class.java, ::onDisconnect)
    }

    companion object {
        private val LOGGER = HytaleLogger.forEnclosingClass()
    }

    private fun swapWorld(world: World, holder: Holder<EntityStore>) {
        val uuid = holder.getComponent(UUIDComponent.getComponentType())!!.uuid
        val playerRef = holder.getComponent(PlayerRef.getComponentType())!!
        val player = holder.getComponent(Player.getComponentType())!!

        LOGGER.atInfo().log("[World-Bound] Player ${playerRef.username} has entered '${world.name}'!")

        storage.apply {
            val data = load(uuid, world)!!
            val comp = data.getComponent(Player.getComponentType())
            if (comp == null) {
                LOGGER.atInfo().log("[World-Bound] Creating ${playerRef.username}'s data in world '${world.name}'..")

                player.inventory = Inventory()
            } else {
                LOGGER.atInfo().log("[World-Bound] Found ${playerRef.username}'s data in world '${world.name}'!")

                player.inventory = comp.inventory
            }
            storage.save(uuid, holder)
            player.markNeedsSave()
            player.sendInventory()
        }
    }

    // Server started
    private fun onStart(event: AllWorldsLoadedEvent) {
        storage.copyFirst()

        Universe.get().playerStorage = storage
    }

    // Player is in the middle of the fadeout transition
    private fun onReady(event: PlayerReadyEvent) {
        LOGGER.atInfo().log("[World-Bound] onReady(), ${event.player}")

        val world = event.player.world!!
        val holder = event.player.toHolder()!!

        swapWorld(world, holder)
    }

    // Player is leaving the world
    private fun onLeave(event: DrainPlayerFromWorldEvent) {
        val universe = Universe.get()
        val world = event.world
        val holder = event.holder
        val uuid = holder.uuid
        val player = universe.getPlayer(uuid)!!

        LOGGER.atInfo().log("[World-Bound] Saving ${player.username} in world '${world.name}'..")

        storage.save(uuid, holder)
    }

    // Player is disconnecting
    private fun onDisconnect(event: PlayerDisconnectEvent) {
        try {
            storage.save(event.playerRef.uuid, event.playerRef.holder!!)
        } finally {
            storage.closeFor(event.playerRef.uuid)
        }
    }
}