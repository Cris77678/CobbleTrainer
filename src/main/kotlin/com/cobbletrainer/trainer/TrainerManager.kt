package com.cobbletrainer.trainer

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.config.TrainerConfig
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.battles.BattleBuilder
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.sin

object TrainerManager {

    private lateinit var server: MinecraftServer
    val cooldowns: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    val activeChallenges: ConcurrentHashMap<UUID, BattleContext> = ConcurrentHashMap()
    private val activeNpcs: ConcurrentHashMap<UUID, NpcData> = ConcurrentHashMap()

    private val cooldownsFile: Path = FabricLoader.getInstance().configDir.resolve("cobbletrainer").resolve("cooldowns.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun init(server: MinecraftServer) {
        this.server = server
        loadCooldowns()
        
        // INTERCEPTAR CLIC: Forzar inicio de batalla
        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (!world.isClient && entity is NPCEntity && entity.commandTags.contains("cobbletrainer_npc")) {
                val serverPlayer = player as? ServerPlayerEntity ?: return@register ActionResult.PASS
                val data = activeNpcs[entity.uuid]
                
                if (data != null && data.playerUuid == serverPlayer.uuid) {
                    if (!data.battleStarted) {
                        try {
                            // En Kotlin se llama al método directamente, sin .INSTANCE
                            BattleBuilder.pvn(serverPlayer, entity)
                        } catch (e: Exception) {
                            CobbleTrainerMod.LOGGER.error("Error al iniciar batalla: ${e.message}")
                        }
                    }
                    return@register ActionResult.SUCCESS
                }
            }
            ActionResult.PASS
        }
        
        // LIMPIEZA DE NPCs
        ServerTickEvents.END_SERVER_TICK.register { srv ->
            val now = System.currentTimeMillis()
            val iter = activeNpcs.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val data = entry.value
                val player = srv.playerManager.getPlayer(data.playerUuid)
                val inBattle = player != null && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null
                
                if (inBattle) data.battleStarted = true

                if (!data.battleStarted) {
                    if (now - data.startTime > 60000L) {
                        srv.worlds.forEach { it.getEntity(data.npcUuid)?.discard() }
                        activeChallenges.remove(data.playerUuid)
                        iter.remove()
                    }
                } else if (!inBattle) {
                    srv.worlds.forEach { it.getEntity(data.npcUuid)?.discard() }
                    if (data.endTime == 0L) data.endTime = now
                    else if (now - data.endTime > 2000L) {
                        activeChallenges.remove(data.playerUuid)
                        iter.remove()
                    }
                }
            }
        }
    }

    fun challenge(player: ServerPlayerEntity, trainerId: String): ChallengeResult {
        val config = CobbleTrainerConfig.getById(trainerId) ?: return ChallengeResult.NotFound
        if (!config.enabled) return ChallengeResult.Disabled

        val trainerTeam = buildTrainerTeam(config)
        if (trainerTeam.isEmpty()) return ChallengeResult.EmptyTeam

        return try {
            val level = player.serverWorld
            val yawRad = Math.toRadians(player.yaw.toDouble())
            val spawnX = player.x - sin(yawRad) * 2.5
            val spawnZ = player.z + cos(yawRad) * 2.5

            val npc = NPCEntity(level)
            npc.npc = NPCClasses.getByName("standard") ?: NPCClasses.random()
            
            npc.refreshPositionAndAngles(spawnX, player.y, spawnZ, player.yaw, 0f)
            npc.headYaw = player.yaw
            npc.customName = Text.literal("§6[Entrenador] §e${config.name}")
            npc.isCustomNameVisible = true
            
            npc.addCommandTag("cobbletrainer_npc")
            npc.setAiDisabled(true)
            npc.isInvulnerable = true

            val npcParty = NPCPartyStore(npc)
            trainerTeam.forEachIndexed { i, p -> npcParty.set(i, p) }
            npcParty.initialize()
            npc.party = npcParty

            if (!level.spawnEntity(npc)) return ChallengeResult.BattleError("Error al spawnear")

            activeNpcs[npc.uuid] = NpcData(npc.uuid, player.uuid, System.currentTimeMillis())
            activeChallenges[player.uuid] = BattleContext(npc.uuid, config.id, config)
            
            ChallengeResult.Started
        } catch (e: Exception) {
            ChallengeResult.BattleError(e.message ?: "Error desconocido")
        }
    }

    private fun buildTrainerTeam(config: TrainerConfig): List<Pokemon> {
        return config.team.mapNotNull { entry ->
            try {
                val propStr = "species=${entry.species.lowercase()} level=${entry.level}" +
                        (if (entry.nature.isNotBlank()) " nature=${entry.nature}" else "") +
                        (if (entry.ability.isNotBlank()) " ability=${entry.ability}" else "")
                
                val pokemon = PokemonProperties.parse(propStr).create()
                
                val statsOrder = Stats.values()
                val ivs = intArrayOf(entry.ivHp, entry.ivAtk, entry.ivDef, entry.ivSpAtk, entry.ivSpDef, entry.ivSpd)
                statsOrder.forEachIndexed { i, stat -> if(i < 6) pokemon.ivs[stat] = ivs[i] }

                if (entry.moves.isNotEmpty()) {
                    pokemon.moveSet.clear()
                    entry.moves.take(4).forEach { m -> 
                        Moves.getByName(m)?.let { pokemon.moveSet.add(it.create()) }
                    }
                }
                pokemon.heal()
                pokemon
            } catch (e: Exception) { null }
        }
    }

    fun loadCooldowns() {
        try {
            if (Files.exists(cooldownsFile)) {
                val raw = Files.readString(cooldownsFile)
                val loaded = json.decodeFromString<Map<String, Long>>(raw)
                cooldowns.clear()
                cooldowns.putAll(loaded)
            }
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Failed to load cooldowns: ${e.message}")
        }
    }
    
    fun saveCooldowns() {
        try {
            Files.createDirectories(cooldownsFile.parent)
            val data = cooldowns.toMap()
            Files.writeString(cooldownsFile, json.encodeToString(data))
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Failed to save cooldowns: ${e.message}")
        }
    }
    
    fun onBattleWon(p: UUID, t: String, c: Long) {
        if (c > 0) {
            cooldowns["${t}:${p}"] = System.currentTimeMillis() + c * 1000L
            saveCooldowns()
        }
    }
    
    fun trainerCount() = CobbleTrainerConfig.trainers.size
    
    fun saveAll() { CobbleTrainerConfig.save(); saveCooldowns() }
    
    fun getCooldownRemaining(player: ServerPlayerEntity, trainerId: String): Long {
        val expiry = cooldowns["${trainerId}:${player.uuid}"] ?: return 0L
        return ((expiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    }

    fun clearCooldown(player: ServerPlayerEntity, trainerId: String) {
        cooldowns.remove("${trainerId}:${player.uuid}")
        saveCooldowns()
    }

    fun clearAllCooldowns(trainerId: String) {
        cooldowns.keys.removeIf { it.startsWith("${trainerId}:") }
        saveCooldowns()
    }
}

data class BattleContext(val npcUuid: UUID, val trainerId: String, val config: TrainerConfig)
data class NpcData(val npcUuid: UUID, val playerUuid: UUID, val startTime: Long, var battleStarted: Boolean = false, var endTime: Long = 0L)
sealed class ChallengeResult { object Started : ChallengeResult(); object NotFound : ChallengeResult(); object Disabled : ChallengeResult(); object NoHealthyPokemon : ChallengeResult(); object EmptyTeam : ChallengeResult(); data class OnCooldown(val secondsRemaining: Long) : ChallengeResult(); data class BattleError(val reason: String) : ChallengeResult() }
