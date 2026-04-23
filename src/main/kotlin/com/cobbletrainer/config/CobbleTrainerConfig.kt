package com.cobbletrainer.config

import com.cobbletrainer.CobbleTrainerMod
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persists trainer configs to disk.
 *
 * Fix #3: Uses CopyOnWriteArrayList so concurrent admin commands
 * (multiple staff editing at the same time) never throw ConcurrentModificationException.
 */
object CobbleTrainerConfig {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configDir: Path = FabricLoader.getInstance().configDir.resolve("cobbletrainer")
    private val trainersFile: Path = configDir.resolve("trainers.json")

    // CopyOnWriteArrayList: thread-safe for concurrent reads + infrequent writes
    val trainers: CopyOnWriteArrayList<TrainerConfig> = CopyOnWriteArrayList()

    fun load() {
        try {
            Files.createDirectories(configDir)
            if (Files.exists(trainersFile)) {
                val raw = Files.readString(trainersFile)
                val loaded = json.decodeFromString<List<TrainerConfig>>(raw)
                trainers.clear()
                trainers.addAll(loaded)
                CobbleTrainerMod.LOGGER.info("Loaded ${trainers.size} trainer config(s).")
            } else {
                CobbleTrainerMod.LOGGER.info("No trainers file found — starting fresh.")
            }
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Failed to load trainer configs: ${e.message}", e)
        }
    }

    @Synchronized
    fun save() {
        try {
            Files.createDirectories(configDir)
            // Snapshot to avoid races during serialization
            val snapshot = trainers.toList()
            val raw = json.encodeToString(snapshot)
            Files.writeString(trainersFile, raw)
            CobbleTrainerMod.LOGGER.info("Saved ${snapshot.size} trainer config(s).")
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Failed to save trainer configs: ${e.message}", e)
        }
    }

    @Synchronized
    fun addOrUpdate(config: TrainerConfig) {
        val idx = trainers.indexOfFirst { it.id == config.id }
        if (idx >= 0) trainers[idx] = config else trainers.add(config)
        save()
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = trainers.removeIf { it.id == id }
        if (removed) save()
        return removed
    }

    fun getById(id: String): TrainerConfig? = trainers.firstOrNull { it.id == id }

    fun reload(): Int {
        load()
        return trainers.size
    }
}
