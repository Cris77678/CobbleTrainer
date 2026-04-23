package com.cobbletrainer.command

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.trainer.ChallengeResult
import com.cobbletrainer.trainer.TrainerManager
import com.cobbletrainer.gui.TrainerGUI
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

object CobbleTrainerCommand {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }
        CobbleTrainerMod.LOGGER.info("Commands registered.")
    }

    private fun registerCommands(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val rootNode = CommandManager.literal("cobbletrainer")
            .requires { Permissions.check(it, "cobbletrainer.command.base", 0) }

        // ----- COMANDO HELP -----
        val helpNode = CommandManager.literal("help")
            .executes { ctx ->
                val source = ctx.source
                source.sendMessage(Text.literal("§6=== CobbleTrainer Help ==="))
                source.sendMessage(Text.literal("§e/cobbletrainer list §7- Lista NPCs."))
                if (Permissions.check(source, "cobbletrainer.command.spawn", 2)) {
                    source.sendMessage(Text.literal("§e/cobbletrainer spawn <id> §7- Genera un NPC estático."))
                    source.sendMessage(Text.literal("§e/cobbletrainer delete §7- Borra el NPC frente a ti."))
                    source.sendMessage(Text.literal("§e/cobbletrainer edit <id> §7- Abre la GUI."))
                    source.sendMessage(Text.literal("§e/cobbletrainer reload §7- Recarga configs."))
                }
                1
            }

        // ----- COMANDO LIST -----
        val listNode = CommandManager.literal("list")
            .requires { Permissions.check(it, "cobbletrainer.command.list", 0) }
            .executes { ctx ->
                val source = ctx.source
                val trainers = CobbleTrainerConfig.trainers
                source.sendMessage(Text.literal("§6=== Entrenadores Configurados (${trainers.size}) ==="))
                trainers.forEach { t ->
                    val status = if (t.enabled) "§a[ON]" else "§c[OFF]"
                    source.sendMessage(Text.literal("$status §e${t.id} §7- ${t.name} §8(${t.team.size} PKMN)"))
                }
                1
            }

        // ----- COMANDO EDIT -----
        val editNode = CommandManager.literal("edit")
            .requires { Permissions.check(it, "cobbletrainer.command.edit", 2) }
            .then(CommandManager.argument("trainerId", StringArgumentType.word())
                .suggests { _, builder ->
                    CobbleTrainerConfig.trainers.forEach { builder.suggest(it.id) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val player = ctx.source.player ?: return@executes 0
                    val trainerId = StringArgumentType.getString(ctx, "trainerId")
                    
                    val config = CobbleTrainerConfig.getById(trainerId)
                    if (config != null) {
                        TrainerGUI.open(player, config)
                    } else {
                        player.sendMessage(Text.literal("§cTrainer no encontrado. ¡Usa /cobbletrainer list!"), false)
                    }
                    1
                }
            )

        // ----- COMANDO SPAWN -----
        val spawnNode = CommandManager.literal("spawn")
            .requires { Permissions.check(it, "cobbletrainer.command.spawn", 2) }
            .then(CommandManager.argument("trainerId", StringArgumentType.word())
                .suggests { _, builder ->
                    CobbleTrainerConfig.trainers.forEach { builder.suggest(it.id) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val player = ctx.source.player ?: return@executes 0
                    val trainerId = StringArgumentType.getString(ctx, "trainerId")
                    
                    val result = TrainerManager.spawnTrainer(player, trainerId)
                    when (result) {
                        is ChallengeResult.NotFound -> player.sendMessage(Text.literal("§cEntrenador no encontrado."), false)
                        is ChallengeResult.BattleError -> player.sendMessage(Text.literal("§cError: ${result.reason}"), false)
                        is ChallengeResult.Started -> player.sendMessage(Text.literal("§aNPC spawneado correctamente frente a ti."), false)
                        else -> {}
                    }
                    1
                }
            )

        // ----- COMANDO DELETE -----
        val deleteNode = CommandManager.literal("delete")
            .requires { Permissions.check(it, "cobbletrainer.command.delete", 2) }
            .executes { ctx ->
                val player = ctx.source.player ?: return@executes 0
                TrainerManager.deleteNearestNpc(player)
                1
            }

        // ----- COMANDO RELOAD -----
        val reloadNode = CommandManager.literal("reload")
            .requires { Permissions.check(it, "cobbletrainer.command.reload", 2) }
            .executes { ctx ->
                CobbleTrainerConfig.load()
                TrainerManager.loadCooldowns()
                ctx.source.sendMessage(Text.literal("§aCobbleTrainer config y cooldowns recargados."))
                1
            }

        rootNode.then(helpNode)
        rootNode.then(listNode)
        rootNode.then(editNode)
        rootNode.then(spawnNode)
        rootNode.then(deleteNode)
        rootNode.then(reloadNode)

        dispatcher.register(rootNode)
    }
}
