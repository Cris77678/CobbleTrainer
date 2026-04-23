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
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
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

    private val cooldownsFile: Path = FabricLoader.getInstance().configDir.resolve("cobbletrainer").resolve("cooldowns.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun init(server: MinecraftServer) {
        this.server = server
        loadCooldowns()
        
        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            // 1. Filtrar doble clic (Mano principal y secundaria)
            if (hand != Hand.MAIN_HAND) return@register ActionResult.PASS

            if (entity is NPCEntity && entity.commandTags.contains("cobbletrainer_npc")) {
                
                // 2. IMPORTANTE: Avisarle al cliente que la interacción fue exitosa 
                // para que el brazo haga la animación y no bloquee el evento.
                if (world.isClient) return@register ActionResult.SUCCESS

                val serverPlayer = player as? ServerPlayerEntity ?: return@register ActionResult.PASS
                val idTag = entity.commandTags.find { it.startsWith("cobbletrainer_id:") }
                
                if (idTag != null) {
                    val trainerId = idTag.substringAfter("cobbletrainer_id:")
                    
                    if (Cobblemon.battleRegistry.getBattleByParticipatingPlayer(serverPlayer) != null) {
                        return@register ActionResult.SUCCESS
                    }

                    val result = tryChallenge(serverPlayer, entity, trainerId)
                    when (result) {
                        is ChallengeResult.OnCooldown -> serverPlayer.sendMessage(Text.literal("§cDebes esperar ${result.secondsRemaining}s para la revancha."), false)
                        is ChallengeResult.NoHealthyPokemon -> serverPlayer.sendMessage(Text.literal("§cNo tienes Pokémon sanos para luchar."), false)
                        is ChallengeResult.BattleError -> serverPlayer.sendMessage(Text.literal("§cError: ${result.reason}"), false)
                        else -> {}
                    }
                    return@register ActionResult.SUCCESS
                }
            }
            ActionResult.PASS
        }
    }

    fun spawnTrainer(player: ServerPlayerEntity, trainerId: String): ChallengeResult {
        val config = CobbleTrainerConfig.getById(trainerId) ?: return ChallengeResult.NotFound

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
            npc.addCommandTag("cobbletrainer_id:$trainerId")
            
            npc.setAiDisabled(true)
            npc.isInvulnerable = true
            npc.setPersistent()

            if (!level.spawnEntity(npc)) return ChallengeResult.BattleError("Error al spawnear")

            ChallengeResult.Started
        } catch (e: Exception) {
            ChallengeResult.BattleError(e.message ?: "Error desconocido")
        }
    }

    fun deleteNearestNpc(player: ServerPlayerEntity) {
        val world = player.serverWorld
        val box = player.boundingBox.expand(4.0)
        val npcs = world.getOtherEntities(player, box) { 
            it is NPCEntity && it.commandTags.contains("cobbletrainer_npc") 
        }
        
        if (npcs.isEmpty()) {
            player.sendMessage(Text.literal("§cNo hay entrenadores cerca de ti."), false)
            return
        }

        val nearest = npcs.minByOrNull { it.squaredDistanceTo(player) }
        nearest?.discard()
        player.sendMessage(Text.literal("§aEntrenador eliminado."), false)
    }

    private fun tryChallenge(player: ServerPlayerEntity, npc: NPCEntity, trainerId: String): ChallengeResult {
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
            val npcParty = NPCPartyStore(npc)
            trainerTeam.forEachIndexed { i, p -> npcParty.set(i, p) }
            npcParty.initialize()
            npc.party = npcParty

            // LA FORMA MANUAL Y SEGURA DE INICIAR BATALLAS EN 1.7.1
            // A) Construir el lado del Jugador (En Kotlin la lambda es simplemente { })
            val playerBattleParty = party.mapNotNull { p -> 
                if (p != null) BattlePokemon(p, p) { } else null 
            }
            val playerActor = PlayerBattleActor(player.uuid, playerBattleParty)
            val playerSide = BattleSide(playerActor)

            // B) Construir el lado del NPC
            val npcActor = NPCBattleActor(npc, npcParty, 0)
            val npcSide = BattleSide(npcActor)

            // C) Iniciar el Registro Oficial
            BattleRegistry.startBattle(
                BattleFormat.GEN_9_SINGLES,
                playerSide,
                npcSide,
                isPvP = false
            )

            activeChallenges[player.uuid] = BattleContext(npc.uuid, config.id, config)
            
            ChallengeResult.Started
        } catch (e: Exception) {
            e.printStackTrace()
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
                
                val statsOrder = Stats.entries.toTypedArray()
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
sealed class ChallengeResult { object Started : ChallengeResult(); object NotFound : ChallengeResult(); object Disabled : ChallengeResult(); object NoHealthyPokemon : ChallengeResult(); object EmptyTeam : ChallengeResult(); data class OnCooldown(val secondsRemaining: Long) : ChallengeResult(); data class BattleError(val reason: String) : ChallengeResult() }
