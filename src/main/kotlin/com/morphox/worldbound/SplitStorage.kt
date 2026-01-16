package com.morphox.worldbound

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import com.hypixel.hytale.server.core.util.BsonUtil
import org.bson.BsonDocument
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension

class SplitStorage : PlayerStorage {
    private val saves = ConcurrentHashMap<Path, ReentrantLock>()
    private var locations = ConcurrentHashMap<UUID, World>()

    // TODO: Allow users to enable this for worlds manually

    @StorageDsl
    fun <T> lock(path: Path, block: SplitStorage.(Path) -> T): T {
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
        } catch (_: Exception) {
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

    override fun load(playerId: UUID): CompletableFuture<Holder<EntityStore?>?> {
        var path: Path
        try {
            val world = getWorld(playerId)
            path = world.getFile(playerId)

            locations[playerId] = world
        } catch (_: Exception) {
            path = Universe.get().defaultWorld!!.savePath.resolve("players/${playerId}.json")
        }

        return lock(path) {
            BsonUtil.readDocument(it).thenApply { doc: BsonDocument? ->
                EntityStore.REGISTRY.deserialize(
                    doc ?: BsonDocument()
                )
            }
        }
    }

    override fun save(
        playerId: UUID, holder: Holder<EntityStore>
    ): CompletableFuture<Void?> {
        var path: Path
        var world: World
        try {
            world = getWorld(playerId)
            path = world.getFile(playerId)

            if (locations[playerId] != world) {
                return CompletableFuture()
            }
        } catch (_: Exception) {
            return CompletableFuture()
        }
        return lock(path) {
            val document = EntityStore.REGISTRY.serialize(holder)
            BsonUtil.writeDocument(it, document)
        }
    }

    override fun remove(playerId: UUID): CompletableFuture<Void?> {
        return lock(getWorld(playerId).getFile(playerId)) {
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