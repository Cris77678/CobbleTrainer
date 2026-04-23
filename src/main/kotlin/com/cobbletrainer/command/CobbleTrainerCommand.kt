package com.cobbletrainer.command

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.gui.TrainerGUI
import com.cobbletrainer.trainer.ChallengeResult
import com.cobbletrainer.trainer.TrainerManager
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

object CobbleTrainerCommand {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("cobbletrainer")
                    .requires { src -> src.hasPermissionLevel(2) || src.entity is ServerPlayerEntity }

                    .then(literal("create")
                        .requires { it.hasPermissionLevel(2) }
                        .executes { ctx ->
                            val player = ctx.source.player ?: run {
                                ctx.source.sendError(Text.literal("Must be a player."))
                                return@executes 0
                            }
                            TrainerGUI.open(player, existingId = null)
                            ctx.source.sendFeedback({ Text.literal("§aOpened trainer creation GUI.") }, false)
                            1
                        }
                        .then(argument("id", StringArgumentType.word())
                            .executes { ctx ->
                                val player = ctx.source.player ?: run {
                                    ctx.source.sendError(Text.literal("Must be a player."))
                                    return@executes 0
                                }
                                val id = StringArgumentType.getString(ctx, "id")
                                TrainerGUI.open(player, existingId = id)
                                ctx.source.sendFeedback({ Text.literal("§aOpened trainer GUI for §f$id§a.") }, false)
                                1
                            }
                        )
                    )

                    .then(literal("edit")
                        .requires { it.hasPermissionLevel(2) }
                        .then(argument("id", StringArgumentType.word())
                            .suggests { _, builder ->
                                CobbleTrainerConfig.trainers.forEach { t -> builder.suggest(t.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val player = ctx.source.player ?: run {
                                    ctx.source.sendError(Text.literal("Must be a player."))
                                    return@executes 0
                                }
                                val id = StringArgumentType.getString(ctx, "id")
                                if (CobbleTrainerConfig.getById(id) == null) {
                                    ctx.source.sendError(Text.literal("§cTrainer '$id' not found."))
                                    return@executes 0
                                }
                                TrainerGUI.open(player, existingId = id)
                                1
                            }
                        )
                    )

                    .then(literal("delete")
                        .requires { it.hasPermissionLevel(2) }
                        .then(argument("id", StringArgumentType.word())
                            .suggests { _, builder ->
                                CobbleTrainerConfig.trainers.forEach { t -> builder.suggest(t.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                val removed = CobbleTrainerConfig.remove(id)
                                if (removed) {
                                    TrainerManager.clearAllCooldowns(id)
                                    ctx.source.sendFeedback({ Text.literal("§aTrainer §f$id§a deleted.") }, true)
                                } else {
                                    ctx.source.sendError(Text.literal("§cTrainer '$id' not found."))
                                }
                                if (removed) 1 else 0
                            }
                        )
                    )

                    .then(literal("list")
                        .executes { ctx ->
                            val trainers = CobbleTrainerConfig.trainers
                            if (trainers.isEmpty()) {
                                ctx.source.sendFeedback({ Text.literal("§7No trainers configured.") }, false)
                            } else {
                                ctx.source.sendFeedback({
                                    Text.literal("§6§lTrainers (${trainers.size}):\n" +
                                        trainers.joinToString("\n") { t ->
                                            val status = if (t.enabled) "§a●" else "§c●"
                                            "$status §f${t.id} §7— ${t.name} | " +
                                            "${t.team.size} Pokémon | " +
                                            "EXP x${t.expMultiplier} | " +
                                            "CD ${t.cooldownSeconds}s"
                                        }
                                    )
                                }, false)
                            }
                            1
                        }
                    )

                    .then(literal("reload")
                        .requires { it.hasPermissionLevel(2) }
                        .executes { ctx ->
                            val count = CobbleTrainerConfig.reload()
                            ctx.source.sendFeedback({ Text.literal("§aCobbleTrainer reloaded §f$count§a trainer(s).") }, true)
                            1
                        }
                    )

                    .then(literal("challenge")
                        .then(argument("id", StringArgumentType.word())
                            .suggests { _, builder ->
                                CobbleTrainerConfig.trainers
                                    .filter { it.enabled }
                                    .forEach { t -> builder.suggest(t.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val player = ctx.source.player ?: run {
                                    ctx.source.sendError(Text.literal("Must be a player."))
                                    return@executes 0
                                }
                                val id = StringArgumentType.getString(ctx, "id")
                                handleChallenge(ctx.source, player, id)
                            }
                        )
                    )

                    .then(literal("cooldown")
                        .requires { it.hasPermissionLevel(2) }
                        .then(literal("clear")
                            .then(argument("id", StringArgumentType.word())
                                .suggests { _, builder ->
                                    CobbleTrainerConfig.trainers.forEach { t -> builder.suggest(t.id) }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    val id = StringArgumentType.getString(ctx, "id")
                                    TrainerManager.clearAllCooldowns(id)
                                    ctx.source.sendFeedback({ Text.literal("§aCleared all cooldowns for trainer §f$id§a.") }, true)
                                    1
                                }
                                .then(argument("player", EntityArgumentType.player())
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val target = EntityArgumentType.getPlayer(ctx, "player")
                                        TrainerManager.clearCooldown(target, id)
                                        ctx.source.sendFeedback({
                                            Text.literal("§aCleared cooldown for §f${target.name.string}§a on trainer §f$id§a.")
                                        }, true)
                                        1
                                    }
                                )
                            )
                        )
                    )

                    .then(literal("toggle")
                        .requires { it.hasPermissionLevel(2) }
                        .then(argument("id", StringArgumentType.word())
                            .suggests { _, builder ->
                                CobbleTrainerConfig.trainers.forEach { t -> builder.suggest(t.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                val existing = CobbleTrainerConfig.getById(id)
                                if (existing == null) {
                                    ctx.source.sendError(Text.literal("§cTrainer '$id' not found."))
                                    return@executes 0
                                }
                                val updated = existing.copy(enabled = !existing.enabled)
                                CobbleTrainerConfig.addOrUpdate(updated)
                                val status = if (updated.enabled) "§aenabled" else "§cdisabled"
                                ctx.source.sendFeedback({ Text.literal("Trainer §f$id§r is now $status§r.") }, true)
                                1
                            }
                        )
                    )

                    .then(literal("info")
                        .then(argument("id", StringArgumentType.word())
                            .suggests { _, builder ->
                                CobbleTrainerConfig.trainers.forEach { t -> builder.suggest(t.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                val t = CobbleTrainerConfig.getById(id)
                                if (t == null) {
                                    ctx.source.sendError(Text.literal("§cTrainer '$id' not found."))
                                    return@executes 0
                                }
                                val evStr = if (t.evRewards.isEmpty()) "none"
                                else t.evRewards.joinToString(", ") { "+${it.amount} ${it.stat.name}" }
                                ctx.source.sendFeedback({
                                    Text.literal(
                                        "§6§l${t.name} §7(${t.id})\n" +
                                        "§7Status: ${if (t.enabled) "§aEnabled" else "§cDisabled"}\n" +
                                        "§7Team: §f${t.team.size} Pokémon\n" +
                                        "§7EV Rewards: §e$evStr\n" +
                                        "§7EXP Multiplier: §ax${t.expMultiplier}\n" +
                                        "§7Cooldown: §f${t.cooldownSeconds}s\n" +
                                        "§7Pokémon: §f${t.team.joinToString(", ") { "${it.species} L${it.level}" }}"
                                    )
                                }, false)
                                1
                            }
                        )
                    )

                    .then(literal("help")
                        .executes { ctx ->
                            ctx.source.sendFeedback({
                                Text.literal(
                                    "§6§lCobbleTrainer Commands:\n" +
                                    "§e/cobbletrainer create §7[id] §f— Open creation GUI\n" +
                                    "§e/cobbletrainer edit §7<id> §f— Edit existing trainer\n" +
                                    "§e/cobbletrainer delete §7<id> §f— Delete a trainer\n" +
                                    "§e/cobbletrainer list §f— List all trainers\n" +
                                    "§e/cobbletrainer info §7<id> §f— Show trainer details\n" +
                                    "§e/cobbletrainer challenge §7<id> §f— Battle a trainer\n" +
                                    "§e/cobbletrainer toggle §7<id> §f— Enable/disable trainer\n" +
                                    "§e/cobbletrainer cooldown clear §7<id> [player] §f— Clear cooldown\n" +
                                    "§e/cobbletrainer reload §f— Reload configs from disk"
                                )
                            }, false)
                            1
                        }
                    )
            )
        }

        CobbleTrainerMod.LOGGER.info("Commands registered.")
    }

    private fun handleChallenge(
        source: ServerCommandSource,
        player: ServerPlayerEntity,
        trainerId: String
    ): Int {
        val config = CobbleTrainerConfig.getById(trainerId)
        if (config == null) {
            source.sendError(Text.literal("§cTrainer '$trainerId' not found. Use §e/cobbletrainer list §cto see available trainers."))
            return 0
        }

        val remaining = TrainerManager.getCooldownRemaining(player, trainerId)
        if (remaining > 0) {
            val mins = remaining / 60
            val secs = remaining % 60
            val timeStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            source.sendError(Text.literal("§cYou must wait §f$timeStr §cbefore challenging §f${config.name}§c again."))
            return 0
        }

        source.sendFeedback({ Text.literal("§7Challenging §f${config.name}§7...") }, false)

        return when (val result = TrainerManager.challenge(player, trainerId)) {
            is ChallengeResult.Started -> {
                source.sendFeedback({ Text.literal("§a§lBattle started! §r§aGood luck against §f${config.name}§a!") }, false)
                1
            }
            is ChallengeResult.OnCooldown -> {
                val mins = result.secondsRemaining / 60
                val secs = result.secondsRemaining % 60
                val timeStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                source.sendError(Text.literal("§cCooldown active! Wait §f$timeStr§c."))
                0
            }
            is ChallengeResult.NoHealthyPokemon -> {
                source.sendError(Text.literal("§cAll your Pokémon have fainted! Heal up first."))
                0
            }
            is ChallengeResult.EmptyTeam -> {
                source.sendError(Text.literal("§cTrainer §f${config.name}§c has no Pokémon configured!"))
                0
            }
            is ChallengeResult.Disabled -> {
                source.sendError(Text.literal("§cTrainer §f${config.name}§c is currently disabled."))
                0
            }
            is ChallengeResult.BattleError -> {
                source.sendError(Text.literal("§cCould not start battle: ${result.reason}"))
                CobbleTrainerMod.LOGGER.warn("Battle error for trainer '$trainerId': ${result.reason}")
                0
            }
            is ChallengeResult.NotFound -> {
                source.sendError(Text.literal("§cTrainer '$trainerId' not found."))
                0
            }
        }
    }
}
