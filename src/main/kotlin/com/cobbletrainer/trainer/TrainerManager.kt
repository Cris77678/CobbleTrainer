package com.cobbletrainer.trainer

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.config.TrainerConfig
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object TrainerManager {

    private lateinit var server: MinecraftServer
    val cooldowns: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    
    // Mapeamos el jugador a su desafío activo.
    val activeChallenges: ConcurrentHashMap<UUID, BattleContext> = ConcurrentHashMap()
    
    private val cooldownsFile: Path = FabricLoader.getInstance().configDir.resolve("cobbletrainer").resolve("cooldowns.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun init(server: MinecraftServer) { 
        this.server = server 
        loadCooldowns()
    }

    private fun loadCooldowns() {
        try {
            if (Files.exists(cooldownsFile)) {
                val raw = Files.readString(cooldownsFile)
                val loaded = json.decodeFromString<Map<String, Long>>(raw)
                cooldowns.clear()
                cooldowns.putAll(loaded)
                CobbleTrainerMod.LOGGER.info("Loaded ${cooldowns.size} player cooldowns.")
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

    private fun createNpcActor(uuid: UUID, name: String, pokemons: List<BattlePokemon>): com.cobblemon.mod.common.api.battles.model.actor.BattleActor {
        val clazz = Class.forName("com.cobblemon.mod.common.battles.actor.TrainerBattleActor")
        val text = net.minecraft.text.Text.literal(name)
        
        for (ctor in clazz.constructors) {
            try {
                if (ctor.parameterCount == 3) {
                    return ctor.newInstance(uuid, text, pokemons) as com.cobblemon.mod.common.api.battles.model.actor.BattleActor
                }
            } catch (e: Exception) {}
        }
        
        for (ctor in clazz.constructors) {
            try {
                if (ctor.parameterCount == 2) {
                    return ctor.newInstance(uuid, pokemons) as com.cobblemon.mod.common.api.battles.model.actor.BattleActor
                }
            } catch (e: Exception) {}
        }
        throw IllegalStateException("No se pudo crear el TrainerBattleActor.")
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
            // FIX 1.7.3: Usamos playerOwned para el jugador y safeCopyOf para el equipo del NPC
            val playerBattlePokemons = party.toList().map { BattlePokemon.playerOwned(it) }
            val npcBattlePokemons = trainerTeam.map { BattlePokemon.safeCopyOf(it) }

            val playerActor = PlayerBattleActor(player.uuid, playerBattlePokemons)
            
            val npcUuid = UUID.randomUUID()
            val npcActor = createNpcActor(npcUuid, config.name, npcBattlePokemons)

            // Iniciamos la batalla usando el registro directamente (más compatible entre versiones)
            Cobblemon.battleRegistry.startBattle(
                BattleFormat.GEN_9_SINGLES,
                BattleSide(playerActor),
                BattleSide(npcActor)
            )

            // Guardamos el desafío ligado al jugador
            activeChallenges[player.uuid] = BattleContext(npcUuid, config.id, config)
            ChallengeResult.Started
            
        } catch (e: Exception) {
            CobbleTrainerMod.LOGGER.error("Error al iniciar la batalla para ${player.name.string}: ${e.message}", e)
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
                pokemon
            } catch (e: Exception) {
                CobbleTrainerMod.LOGGER.warn("No se pudo crear el Pokémon '${entry.species}': ${e.message}")
                null
            }
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

sealed class ChallengeResult {
    object Started : ChallengeResult()
    object NotFound : ChallengeResult()
    object Disabled : ChallengeResult()
    object NoHealthyPokemon : ChallengeResult()
    object EmptyTeam : ChallengeResult()
    data class OnCooldown(val secondsRemaining: Long) : ChallengeResult()
    data class BattleError(val reason: String) : ChallengeResult()
}
