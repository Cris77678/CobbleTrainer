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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
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
        
        ServerTickEvents.END_SERVER_TICK.register { srv ->
            val now = System.currentTimeMillis()
            val iter = activeNpcs.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val data = entry.value
                
                if (now - data.startTime > 60000L) {
                    val player = srv.playerManager.getPlayer(data.playerUuid)
                    val inBattle = player != null && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null
                    
                    if (!inBattle) {
                        srv.worlds.forEach { world ->
                            world.getEntity(data.npcUuid)?.discard()
                        }
                        activeChallenges.remove(data.playerUuid) // Previene memory leaks
                        iter.remove()
                    }
                } else if (now - data.startTime > 3000L) {
                    val player = srv.playerManager.getPlayer(data.playerUuid)
                    val inBattle = player != null && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null
                    if (!inBattle) {
                        srv.worlds.forEach { world ->
                            world.getEntity(data.npcUuid)?.discard()
                        }
                        activeChallenges.remove(data.playerUuid) // Previene memory leaks
                        iter.remove()
                    }
                }
            }
        }
    }

    private fun loadCooldowns() {
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

    private fun saveCooldowns() {
        try {
            Files.createDirectories(cooldownsFile.parent)
            val data = cooldowns.toMap()
            Files.writeString(cooldownsFile, json.encodeToString(data))
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Failed to save cooldowns: ${e.message}")
        }
    }

    fun trainerCount() = CobbleTrainerConfig.trainers.size

    fun saveAll() {
        CobbleTrainerConfig.save()
        saveCooldowns()
    }

    fun challenge(player: ServerPlayerEntity, trainerId: String): ChallengeResult {
        val config = CobbleTrainerConfig.getById(trainerId) ?: return ChallengeResult.NotFound
        if (!config.enabled) return ChallengeResult.Disabled

        val cooldownKey = "${trainerId}:${player.uuid}"
        val now = System.currentTimeMillis()
        val expiry = cooldowns[cooldownKey] ?: 0L
        if (now < expiry) return ChallengeResult.OnCooldown((expiry - now) / 1000L)

        val party = Cobblemon.storage.getParty(player)
        if (party.none { !it.isFainted() }) return ChallengeResult.NoHealthyPokemon

        val trainerTeam = buildTrainerTeam(config)
        if (trainerTeam.isEmpty()) return ChallengeResult.EmptyTeam

        return try {
            val level = player.serverWorld
            val yawRad = Math.toRadians(player.yaw.toDouble())
            val spawnX = player.x - sin(yawRad) * 2.5
            val spawnY = player.y
            val spawnZ = player.z + cos(yawRad) * 2.5

            val npc = NPCEntity(level)
            val npcClass = NPCClasses.getByName("standard") ?: NPCClasses.random()
            npc.npc = npcClass
            
            // Usamos refreshPositionAndAngles que es el equivalente a moveTo en Yarn
            npc.refreshPositionAndAngles(spawnX, spawnY, spawnZ, player.yaw, 0f)
            npc.headYaw = player.yaw
            
            npc.addCommandTag("cobbletrainer_npc")
            npc.customName = Text.literal("§6[Entrenador] §e${config.name}")
            npc.isCustomNameVisible = true
            npc.skill = 3
            npc.isInvulnerable = true
            npc.setAiDisabled(true)

            val npcParty = NPCPartyStore(npc)
            trainerTeam.forEachIndexed { index, pokemon ->
                npcParty.set(index, pokemon)
            }
            npcParty.initialize()
            npc.party = npcParty

            if (!level.spawnEntity(npc)) {
                return ChallengeResult.BattleError("No se pudo spawnear el NPC en el mundo.")
            }

            val npcUuid = npc.uuid
            activeNpcs[npcUuid] = NpcData(npcUuid, player.uuid, System.currentTimeMillis())
            activeChallenges[player.uuid] = BattleContext(npcUuid, config.id, config)
            
            player.sendMessage(Text.literal("§a¡El entrenador §f${config.name}§a ha aparecido! Haz clic derecho en él para comenzar la batalla."), false)
            ChallengeResult.Started

        } catch (e: Exception) {
            ChallengeResult.BattleError(e.message ?: "Error desconocido al iniciar.")
        }
    }

    fun onBattleWon(playerUUID: UUID, trainerId: String, cooldownSeconds: Long) {
        if (cooldownSeconds > 0) {
            cooldowns["${trainerId}:${playerUUID}"] =
                System.currentTimeMillis() + cooldownSeconds * 1000L
            saveCooldowns()
        }
    }

    private fun buildTrainerTeam(config: TrainerConfig): List<Pokemon> {
        return config.team.mapNotNull { entry ->
            try {
                val propStr = "${entry.species.lowercase()} level=${entry.level}" +
                        (if (entry.nature.isNotBlank()) " nature=${entry.nature}" else "") +
                        (if (entry.ability.isNotBlank()) " ability=${entry.ability}" else "") +
                        (if (entry.heldItem.isNotBlank()) " held_item=${entry.heldItem}" else "")

                val pokemon = PokemonProperties.parse(propStr).create()

                pokemon.ivs[Stats.HP] = entry.ivHp.coerceIn(0, 31)
                pokemon.ivs[Stats.ATTACK] = entry.ivAtk.coerceIn(0, 31)
                pokemon.ivs[Stats.DEFENCE] = entry.ivDef.coerceIn(0, 31)
                pokemon.ivs[Stats.SPECIAL_ATTACK] = entry.ivSpAtk.coerceIn(0, 31)
                pokemon.ivs[Stats.SPECIAL_DEFENCE] = entry.ivSpDef.coerceIn(0, 31)
                pokemon.ivs[Stats.SPEED] = entry.ivSpd.coerceIn(0, 31)

                if (entry.moves.isNotEmpty()) {
                    pokemon.moveSet.clear()
                    entry.moves.take(4).forEach { moveName ->
                        val template = Moves.getByName(moveName)
                        if (template != null) pokemon.moveSet.add(template.create())
                    }
                }
                
                pokemon.persistentData.putBoolean("cobbletrainer_npc_pokemon", true)
                pokemon.heal()
                
                pokemon
            } catch (e: Exception) { null }
        }
    }

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

data class BattleContext(
    val npcUuid: UUID,
    val trainerId: String,
    val config: TrainerConfig
)

data class NpcData(
    val npcUuid: UUID, 
    val playerUuid: UUID, 
    val startTime: Long
)

sealed class ChallengeResult {
    object Started : ChallengeResult()
    object NotFound : ChallengeResult()
    object Disabled : ChallengeResult()
    object NoHealthyPokemon : ChallengeResult()
    object EmptyTeam : ChallengeResult()
    data class OnCooldown(val secondsRemaining: Long) : ChallengeResult()
    data class BattleError(val reason: String) : ChallengeResult()
}
