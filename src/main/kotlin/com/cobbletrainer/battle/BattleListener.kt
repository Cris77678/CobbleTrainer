package com.cobbletrainer.battle

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.EVStat
import com.cobbletrainer.trainer.BattleContext
import com.cobbletrainer.trainer.TrainerManager
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

object BattleListener {

    fun register() {
        CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
            val winningPlayer = event.winners
                .filterIsInstance<PlayerBattleActor>()
                .firstOrNull() ?: return@subscribe

            val player = winningPlayer.entity as? ServerPlayerEntity ?: return@subscribe
            
            // Verificamos si el jugador tenía un desafío activo
            val ctx = TrainerManager.activeChallenges[player.uuid] ?: return@subscribe

            // Verificamos que nuestro NPC fue el que perdió la batalla
            val hasOurNpc = event.battle.actors.any { it.uuid == ctx.npcUuid }
            if (!hasOurNpc) return@subscribe

            // ¡Victoria confirmada! Limpiamos el desafío y damos recompensas
            TrainerManager.activeChallenges.remove(player.uuid)
            TrainerManager.onBattleWon(player.uuid, ctx.trainerId, ctx.config.cooldownSeconds)
            
            grantRewards(player, ctx)
        }

        CobbleTrainerMod.LOGGER.info("BattleListener registered.")
    }

    private fun grantRewards(player: ServerPlayerEntity, ctx: BattleContext) {
        val party = Cobblemon.storage.getParty(player).toList()
        
        if (ctx.config.evRewards.isNotEmpty()) {
            party.forEach { pokemon ->
                ctx.config.evRewards.forEach { reward ->
                    val cobbleStat = when (reward.stat) {
                        EVStat.HP -> Stats.HP; EVStat.ATTACK -> Stats.ATTACK; EVStat.DEFENCE -> Stats.DEFENCE
                        EVStat.SPECIAL_ATTACK -> Stats.SPECIAL_ATTACK; EVStat.SPECIAL_DEFENCE -> Stats.SPECIAL_DEFENCE; EVStat.SPEED -> Stats.SPEED
                    }
                    try {
                        val current = pokemon.evs[cobbleStat] ?: 0
                        val totalOther = Stats.values().filter { it != cobbleStat }.sumOf { pokemon.evs[it] ?: 0 }
                        val maxForStat = minOf(252, 510 - totalOther)
                        pokemon.evs[cobbleStat] = (current + reward.amount).coerceIn(0, maxForStat)
                    } catch (e: Exception) {
                        CobbleTrainerMod.LOGGER.warn("Error al aplicar EVs en ${pokemon.species.name}: ${e.message}")
                    }
                }
            }
        }

        if (ctx.config.expMultiplier > 1.0) {
            val totalNpcExp = ctx.config.team.sumOf { entry ->
                try {
                    val npcSpecies = PokemonProperties.parse(entry.species).create().species
                    (npcSpecies.baseExperienceYield * entry.level / 5.0).toInt()
                } catch (e: Exception) { 0 }
            }
            
            val bonusTotal = ((ctx.config.expMultiplier - 1.0) * totalNpcExp).toInt().coerceAtLeast(0)
            if (bonusTotal > 0) {
                val source = SidemodExperienceSource("cobbletrainer")
                party.forEach { pokemon ->
                    pokemon.addExperience(source, bonusTotal)
                }
            }
        }

        val evSummary = ctx.config.evRewards.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { "+${it.amount} ${it.stat.name}" } ?: "ninguno"
        
        val expText = if (ctx.config.expMultiplier != 1.0) " §7(§aEXP ×${ctx.config.expMultiplier}§7)" else ""

        player.sendMessage(Text.literal(
            "§a§l✓ Victoria en PokeLand! §r§a¡Derrotaste a §f${ctx.config.name}§a!\n" +
            "§7EVs ganados por tu equipo: §e$evSummary§r$expText"
        ), false)

        if (ctx.config.cooldownSeconds > 0) {
            player.sendMessage(Text.literal("§7Siguiente revancha en §f${ctx.config.cooldownSeconds}s§7."), false)
        }
    }
}