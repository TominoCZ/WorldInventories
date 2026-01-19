package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import com.hypixel.hytale.server.core.util.BsonUtil
import com.hypixel.hytale.sneakythrow.SneakyThrow
import org.bson.BsonDocument
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

class WorldStorage : PlayerStorage {
    private val saves = ConcurrentHashMap<Path, ReentrantLock>()
    private var locations = ConcurrentHashMap<UUID, World>()

    // TODO: Allow users to enable this for worlds manually

    @StorageDsl
    fun <T> lock(path: Path, block: WorldStorage.(Path) -> T): T {
        val mutex = saves.getOrPut(path) {
            ReentrantLock()
        }
        mutex.lock()
        try {
            return block(path)
        } finally {
            mutex.unlock()
        }
    }

    fun <T> future(func: () -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(SneakyThrow.sneakySupplier<T, Exception?> {
            func()
        })
    }

    fun copyFirst() {
        val uni = Universe.get()
        val path = uni.path.resolve("players")
        val newPath = uni.defaultWorld!!.savePath.resolve("players")

        try {
            Files.list(path).use { stream ->
                stream.filter {
                    it != null && it.fileName.toString().endsWith(".json")
                }.map {
                    it to newPath.resolve(it.fileName)
                }.filter { (_, b: Path) ->
                    !Files.exists(b)
                }.forEach { (a: Path, b: Path) ->
                    Files.createDirectories(b.parent)
                    Files.copy(a, b)
                }
            }
        } catch (e: Exception) {
            println("[World-Bound] Failed to copyFirst(): ${e.stackTraceToString()}")
        }
    }

    fun getWorld(playerId: UUID): World {
        val universe = Universe.get()
        val player = universe.getPlayer(playerId)!!
        return universe.getWorld(player.worldUuid!!)!!
    }

    fun load(playerId: UUID, world: World): Holder<EntityStore?>? {
        locations[playerId] = world

        return load(playerId).get()
    }

    fun read(path: Path): CompletableFuture<Holder<EntityStore?>?> {
        return lock(path) {
            BsonUtil.readDocument(path).thenApply { doc: BsonDocument? ->
                EntityStore.REGISTRY.deserialize(
                    doc ?: BsonDocument()
                )
            }
        }
    }

    @JvmName("writeNotNull")
    fun write(path: Path, holder: Holder<EntityStore>): CompletableFuture<Void?> {
        EntityStore.REGISTRY.serialize(holder).apply {
            return BsonUtil.writeDocument(path, this)
        }
    }

    // In the original code, this used to take in Holder<EntityStore> (the non-null EntityStore)
    @JvmName("writeNullable")
    fun write(path: Path, holder: Holder<EntityStore?>): CompletableFuture<Void?> {
        EntityStore.REGISTRY.serialize(holder).apply {
            return BsonUtil.writeDocument(path, this)
        }
    }

    fun updateText(path: Path, proc: (MutableList<String>) -> Unit) {
        try {
            val lines = with(FileReader(path.absolutePathString())) {
                readLines()
            }.toMutableList()

            proc(lines)

            with(FileWriter(path.absolutePathString(), Charsets.UTF_8)) {
                repeat(lines.count()) { index ->
                    if (index == lines.count() - 1) {
                        write(lines[index])
                    } else {
                        write("${lines[index]}\n")
                    }
                }
            }
        } catch (e: Exception) {
            println("[World-Bound] Failed to updateText(): ${e.stackTraceToString()}")
        }
    }

    fun updateData(path: Path, proc: WorldStorage.(Holder<EntityStore?>) -> Unit) {
        lock(path) {
            try {
                val data = read(path).get()!!

                proc(data)

                write(path, data)
            } catch (e: Exception) {
                println("[World-Bound] Failed to updateData(): ${e.stackTraceToString()}")
            }
        }
    }

    override fun load(playerId: UUID): CompletableFuture<Holder<EntityStore?>?> {
        var path: Path
        var world: World?
        try {
            world = getWorld(playerId)
            path = world.getPlayerFile(playerId)

            locations[playerId] = world
        } catch (_: Exception) {
            path = Universe.get().defaultWorld!!.savePath.resolve("players/${playerId}.json")
        }

        println("[World-Bound] Reading: $path")

        return read(path)
    }

    override fun save(
        playerId: UUID, holder: Holder<EntityStore>
    ): CompletableFuture<Void?> {
        val file = "players/${playerId}.json"
        var path: Path
        var world: World
        try {
            world = getWorld(playerId)
            path = world.getPlayerFile(playerId)

            if (locations[playerId] != world) {
                return CompletableFuture()
            }
        } catch (e: Exception) {
            println("[World-Bound] Failed to updateData(): ${e.stackTraceToString()}")
            return CompletableFuture()
        }
        return future {
            lock(path) {
                // Update the shared data file with the last player position in this world
                val player: Player? = holder.getComponent(Player.getComponentType())
                val cfg = player?.playerConfigData
                val shared = Universe.get().path.resolve(file)
                if (cfg != null) {
                    updateData(shared) { data ->
                        val playerShared: Player? = data.getComponent(Player.getComponentType())
                        val cfgShared = playerShared?.playerConfigData ?: return@updateData
                        println("[World-Bound] Updating shared data file..")
                        val map: MutableMap<String, PlayerWorldData> = mutableMapOf()
                        cfg.perWorldData.forEach { (k, v) ->
                            map[k] = v
                        }
                        cfgShared.perWorldData = map

                        cfgShared.preset = cfg.preset
                        cfgShared.world = world.name
                        cfgShared.knownRecipes = cfg.knownRecipes

                        cfgShared.activeObjectiveUUIDs = cfg.activeObjectiveUUIDs
                        cfgShared.discoveredInstances = cfg.discoveredInstances
                        cfgShared.discoveredZones = cfg.discoveredZones
                        cfgShared.reputationData = cfg.reputationData
                        cfgShared.blockIdVersion = cfg.blockIdVersion

                        println("[World-Bound] Successfully updated shared data file")
                    }
                }
                // Save player data
                write(path, holder).get()
            }
        }
    }

    override fun remove(playerId: UUID): CompletableFuture<Void?> {
        return lock(getWorld(playerId).getPlayerFile(playerId)) {
            try {
                Files.deleteIfExists(it)
                return@lock CompletableFuture.completedFuture<Void?>(null)
            } catch (e: IOException) {
                return@lock CompletableFuture.failedFuture(e)
            }
        }
    }

    @Throws(IOException::class)
    override fun getPlayers(): MutableSet<UUID?> {
        Files.list(Universe.get().path).use { stream ->
            return stream.map<UUID?> { p: Path? ->
                val fileName = p!!.fileName.toString()
                if (!fileName.endsWith(".json")) {
                    return@map null
                } else {
                    try {
                        return@map UUID.fromString(fileName.dropLast(".json".length))
                    } catch (_: IllegalArgumentException) {
                        return@map null
                    }
                }
            }.filter { obj: UUID? -> Objects.nonNull(obj) }.collect(Collectors.toSet())
        }
    }

    fun closeFor(playerId: UUID) {
        locations.remove(playerId)

        val str = playerId.toString()
        for (key in saves.keys) {
            if (key.nameWithoutExtension.contains(str)) {
                saves.remove(key)
            }
        }
    }
}