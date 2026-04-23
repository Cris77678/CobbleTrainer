package com.cobbletrainer.util

import com.cobbletrainer.CobbleTrainerMod
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatInputHandler {

    private val pendingInputs: ConcurrentHashMap<UUID, (String) -> Unit> = ConcurrentHashMap()

    fun register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            val callback = pendingInputs[sender.uuid]
            if (callback != null) {
                val text = message.content.string.trim()
                
                // Permitimos que los comandos (como /spawn o /help) pasen normalmente
                if (text.startsWith("/")) {
                    return@register true 
                }
                
                // Removemos al jugador de la lista de espera
                pendingInputs.remove(sender.uuid)
                
                if (text.isNotBlank()) {
                    callback.invoke(text)
                }
                false // Ocultamos el mensaje del chat público
            } else {
                true 
            }
        }

        // PREVENCIÓN DE MEMORY LEAK: Si el jugador se desconecta, limpiamos la memoria
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (pendingInputs.remove(handler.player.uuid) != null) {
                CobbleTrainerMod.LOGGER.debug("Se limpió el input pendiente de ${handler.player.name.string} por desconexión.")
            }
        }

        CobbleTrainerMod.LOGGER.info("ChatInputHandler registered.")
    }

    fun awaitInput(player: ServerPlayerEntity, prompt: String, callback: (String) -> Unit) {
        pendingInputs[player.uuid] = callback
        player.sendMessage(
            Text.literal("§e$prompt §7(escribe en el chat — no será visible para otros)"),
            false
        )
    }

    fun cancel(player: ServerPlayerEntity) {
        if (pendingInputs.remove(player.uuid) != null) {
            CobbleTrainerMod.LOGGER.debug("Cancelled pending chat input for ${player.name.string}")
        }
    }

    fun hasPending(player: ServerPlayerEntity): Boolean = pendingInputs.containsKey(player.uuid)
}
