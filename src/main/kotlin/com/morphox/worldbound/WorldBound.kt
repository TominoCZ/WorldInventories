package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.entity.UUIDComponent
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

class WorldBound(init: JavaPluginInit) : JavaPlugin(init) {
    private val storage = WorldStorage()

    companion object {
        private val LOG = HytaleLogger.forEnclosingClass()
    }

    init {
        LOG.info("[World-Bound] Running on version ${manifest.version}!")
    }

    override fun setup() {
        LOG.info("[World-Bound] Setting up..")
        eventRegistry.registerGlobal(AllWorldsLoadedEvent::class.java, ::onLoad)
        eventRegistry.registerGlobal(PlayerReadyEvent::class.java, ::onEnter)
        //eventRegistry.registerGlobal(AddPlayerToWorldEvent::class.java, ::onEnter)
        eventRegistry.registerGlobal(DrainPlayerFromWorldEvent::class.java, ::onLeave)
        eventRegistry.registerGlobal(PlayerDisconnectEvent::class.java, ::onDisconnect)
    }

    // Server started and someone joined
    private fun onLoad(event: AllWorldsLoadedEvent) {
        storage.copyFirst()

        Universe.get().playerStorage = storage
    }

    private fun swapWorld(world: World, holder: Holder<EntityStore>) {
        val uuid: UUIDComponent = holder.getComponent(UUIDComponent.getComponentType())!!
        val playerRef: PlayerRef = holder.getComponent(PlayerRef.getComponentType())!!
        val player: Player = holder.getComponent(Player.getComponentType())!!

        LOG.info("[World-Bound] Player ${playerRef.username} has entered '${world.name}'!")

        storage.apply {
            val data = load(uuid.uuid, world)!!
            if (!data.isValid()) {
                LOG.info("[World-Bound] Creating ${playerRef.username}'s data in world '${world.name}'..")

                holder.reset()
            } else {
                LOG.info("[World-Bound] Found ${playerRef.username}'s data in world '${world.name}'!")

                holder.load(data)
            }

            save(uuid.uuid, holder).get()

            player.markNeedsSave()
            player.sendInventory()
        }
    }

    // Player is in the middle of the fadeout transition
    private fun onEnter(event: PlayerReadyEvent) {
        LOG.info("[World-Bound] onEnter()")
        //val world = event.world
        //val holder = event.holder

        val world = event.player.world!!
        val holder = event.player.toHolder()!!

        swapWorld(world, holder)
    }

    // Player is leaving the world
    private fun onLeave(event: DrainPlayerFromWorldEvent) {
        val world = event.world
        val holder = event.holder
        val uuid = holder.uuid
        val dnc: DisplayNameComponent? = holder.getComponent(DisplayNameComponent.getComponentType())

        LOG.info("[World-Bound] Saving ${dnc?.displayName?.rawText} in world '${world.name}'..")

        storage.save(uuid, holder)
    }

    // Player is disconnecting
    private fun onDisconnect(event: PlayerDisconnectEvent) {
        storage.closeFor(event.playerRef.uuid)
    }
}