package com.cobbletrainer

import com.cobbletrainer.battle.BattleListener
import com.cobbletrainer.command.CobbleTrainerCommand
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.trainer.TrainerManager
import com.cobbletrainer.util.ChatInputHandler
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

// 1. Cambiamos 'object' por 'class' para que Fabric pueda instanciarlo
class CobbleTrainerMod : ModInitializer {

    // 2. Metemos las constantes globales en un companion object
    companion object {
        const val MOD_ID = "cobbletrainer"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("═══════════════════════════════════════")
        LOGGER.info("  CobbleTrainer — Initializing...")
        LOGGER.info("═══════════════════════════════════════")

        // Register systems
        BattleListener.register()
        CobbleTrainerCommand.register()
        ChatInputHandler.register()

        // Load config on server start
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            CobbleTrainerConfig.load()
            TrainerManager.init(server)
            LOGGER.info("Loaded ${TrainerManager.trainerCount()} trainer(s) from disk.")
        }

        // Save on stop
        ServerLifecycleEvents.SERVER_STOPPING.register {
            CobbleTrainerConfig.save()
            TrainerManager.saveAll()
            LOGGER.info("CobbleTrainer shut down — configs saved.")
        }

        LOGGER.info("CobbleTrainer ready! Use /cobbletrainer help in-game.")
    }
}
