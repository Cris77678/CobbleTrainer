package com.cobbletrainer.gui

import com.cobbletrainer.CobbleTrainerMod
import com.cobbletrainer.config.CobbleTrainerConfig
import com.cobbletrainer.config.EVReward
import com.cobbletrainer.config.EVStat
import com.cobbletrainer.config.PokemonEntry
import com.cobbletrainer.config.TrainerConfig
import com.cobbletrainer.util.ChatInputHandler
import com.cobblemon.mod.common.Cobblemon
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

object TrainerGUI {

    fun open(player: ServerPlayerEntity, existingId: String? = null) {
        val existing = existingId?.let { CobbleTrainerConfig.getById(it) }
        val state = GUIState(player, existing)
        val title = if (existing == null) "Create Trainer" else "Edit: ${existing.name}"
        player.openHandledScreen(
            SimpleNamedScreenHandlerFactory(
                { syncId, inv, _ -> CobbleTrainerScreenHandler(syncId, inv, state) },
                Text.literal("§6§lCobbleTrainer §r§8— $title")
            )
        )
    }

    class GUIState(private val player: ServerPlayerEntity, existing: TrainerConfig?) {
        val trainerId: String = existing?.id ?: "trainer_${System.currentTimeMillis()}"
        var trainerName: String = existing?.name ?: "New Trainer"
        val pokemonSlots: Array<PokemonEntry?> = Array(6) { null }
        val evAmounts: MutableMap<EVStat, Int> = EVStat.values().associateWith { 0 }.toMutableMap()
        var expMultiplier: Double = existing?.expMultiplier ?: 1.0
        var cooldownSeconds: Long = existing?.cooldownSeconds ?: 300L
        var enabled: Boolean = existing?.enabled ?: true

        init {
            existing?.let { cfg ->
                cfg.team.forEachIndexed { i, e ->
                    if (i < 6) pokemonSlots[i] = e
                }
                cfg.evRewards.forEach { r -> evAmounts[r.stat] = r.amount }
            }
        }

        fun importParty() {
            for (i in 0 until 6) {
                pokemonSlots[i] = null
            }

            Cobblemon.storage.getParty(player).forEachIndexed { i, pkmn ->
                if (i < 6) {
                    pokemonSlots[i] = PokemonEntry(
                        species = pkmn.species.name.lowercase(),
                        level = pkmn.level,
                        // Fix 1.7.1: Extracción segura de String para atributos
                        nature = pkmn.nature.name.toString().substringAfter(":").lowercase(),
                        ability = pkmn.ability.name.toString().substringAfter(":").lowercase(),
                        ivHp = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: 31,
                        ivAtk = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: 31,
                        ivDef = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: 31,
                        ivSpAtk = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: 31,
                        ivSpDef = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: 31,
                        ivSpd = pkmn.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: 31,
                        moves = pkmn.moveSet.map { it.template.name.lowercase() }
                    )
                }
            }
        }

        fun totalEvReward(): Int = evAmounts.values.sum()
        fun evWarning(): Boolean = totalEvReward() > 510

        fun buildConfig() = TrainerConfig(
            id = trainerId,
            name = trainerName,
            team = pokemonSlots.filterNotNull(),
            evRewards = evAmounts.filter { it.value > 0 }.map { (stat, amt) -> EVReward(stat, amt) },
            expMultiplier = expMultiplier,
            cooldownSeconds = cooldownSeconds,
            enabled = enabled
        )
    }

    class CobbleTrainerScreenHandler(
        syncId: Int,
        private val playerInv: PlayerInventory,
        private val state: GUIState
    ) : GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, SimpleInventory(54), 6) {

        init { refresh() }

        private fun refresh() {
            val inv = inventory as SimpleInventory
            for (i in 0 until 54) inv.setStack(i, bg())

            inv.setStack(4, item(Items.NETHER_STAR, "§6§lCobbleTrainer", "§7Configure your NPC trainer"))

            inv.setStack(10, item(Items.NAME_TAG, "§e§lTrainer Name", "§f${state.trainerName}", "§7Click → type new name in chat"))

            inv.setStack(16, item(Items.ENDER_CHEST, "§a§l↓ Import Your Party", "§7Copies your active party into the slots below"))

            for (i in 0 until 6) {
                val entry = state.pokemonSlots[i]
                inv.setStack(19 + i,
                    if (entry != null)
                        item(Items.DRAGON_EGG, "§b§lSlot ${i+1}", "§f${entry.species} Nv.${entry.level}", "§7Right-click to clear")
                    else
                        item(Items.BARRIER, "§8Slot ${i+1}: empty", "§7Import party or set manually")
                )
            }

            inv.setStack(28, item(Items.RED_DYE,   "§c§l− EXP", "§7Left: −0.1  Right: −0.5"))
            inv.setStack(29, item(Items.EXPERIENCE_BOTTLE, "§e§lEXP Multiplier", "§fCurrent: §a×${state.expMultiplier}", "§7Bonus EXP added on top of normal gain", "§7Range: 0.1 – 20.0"))
            inv.setStack(30, item(Items.LIME_DYE,  "§a§l+ EXP", "§7Left: +0.1  Right: +0.5"))

            inv.setStack(36, item(Items.AMETHYST_SHARD, "§d§lEV Rewards", "§7Total: §e${state.totalEvReward()}§7 / 510", if (state.evWarning()) "§c⚠ Total exceeds 510 — engine will cap it" else "§a✔ Within limits", "§7Left: +4  Right: −4"))

            val evDefs = listOf(
                Triple(37, EVStat.HP, Items.RED_WOOL), Triple(38, EVStat.ATTACK, Items.ORANGE_WOOL),
                Triple(39, EVStat.DEFENCE, Items.YELLOW_WOOL), Triple(40, EVStat.SPECIAL_ATTACK, Items.BLUE_WOOL),
                Triple(41, EVStat.SPECIAL_DEFENCE,Items.CYAN_WOOL), Triple(42, EVStat.SPEED, Items.WHITE_WOOL)
            )
            evDefs.forEach { (slot, stat, mat) ->
                val amt = state.evAmounts[stat] ?: 0
                val label = stat.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                inv.setStack(slot, item(mat, "§b§l$label", "§fAmount: §e$amt", "§7Left §a+4§7 | Right §c−4"))
            }

            inv.setStack(46, item(Items.RED_DYE,  "§c§l− Cooldown", "§7Left: −30s  Right: −60s"))
            inv.setStack(47, item(Items.CLOCK, "§e§lCooldown", "§fCurrent: §a${state.cooldownSeconds}s", "§7Per-player rematch timer", "§70 = unlimited rematches"))
            inv.setStack(48, item(Items.LIME_DYE, "§a§l+ Cooldown", "§7Left: +30s  Right: +60s"))

            inv.setStack(50, if (state.enabled) item(Items.LIME_CONCRETE, "§a§l✔ Enabled", "§7Click to disable this trainer") else item(Items.RED_CONCRETE, "§c§l✘ Disabled", "§7Click to enable this trainer"))

            inv.setStack(45, item(Items.BARRIER, "§c§lCancel", "§7Close without saving"))

            val saveExtra = if (state.evWarning()) "§c⚠ EV total ${state.totalEvReward()} > 510 — Cobblemon will cap excess" else "§a✔ EV total ${state.totalEvReward()} / 510"
            inv.setStack(53, item(Items.EMERALD, "§a§lSave & Apply", "§7${state.pokemonSlots.count { it != null }} Pokémon  |  EXP ×${state.expMultiplier}", "§7Cooldown: ${state.cooldownSeconds}s", saveExtra))
        }

        override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
            if (slotIndex < 0 || actionType == SlotActionType.QUICK_MOVE) return
            val serverPlayer = player as? ServerPlayerEntity ?: return

            when (slotIndex) {
                10 -> {
                    ChatInputHandler.cancel(serverPlayer)
                    serverPlayer.closeHandledScreen()
                    ChatInputHandler.awaitInput(serverPlayer, "Enter new trainer name (max 32 chars):") { input ->
                        state.trainerName = input.take(32)
                        open(serverPlayer, state.trainerId.takeIf { CobbleTrainerConfig.getById(it) != null })
                    }
                }
                16 -> { state.importParty(); refresh(); player.sendMessage(Text.literal("§aParty imported into slots!"), false) }
                in 19..24 -> { if (button == 1) { state.pokemonSlots[slotIndex - 19] = null; refresh() } }
                28 -> { val d = if (button == 1) 0.5 else 0.1; state.expMultiplier = (Math.round((state.expMultiplier - d) * 10) / 10.0).coerceAtLeast(0.1); refresh() }
                30 -> { val d = if (button == 1) 0.5 else 0.1; state.expMultiplier = (Math.round((state.expMultiplier + d) * 10) / 10.0).coerceAtMost(20.0); refresh() }
                in 37..42 -> { val stat = EVStat.values()[slotIndex - 37]; val current = state.evAmounts[stat] ?: 0; state.evAmounts[stat] = if (button == 1) (current - 4).coerceAtLeast(0) else (current + 4).coerceAtMost(252); refresh() }
                46 -> { val d = if (button == 1) 60L else 30L; state.cooldownSeconds = (state.cooldownSeconds - d).coerceAtLeast(0L); refresh() }
                48 -> { val d = if (button == 1) 60L else 30L; state.cooldownSeconds = (state.cooldownSeconds + d).coerceAtMost(86400L); refresh() }
                50 -> { state.enabled = !state.enabled; refresh() }
                45 -> { serverPlayer.closeHandledScreen(); player.sendMessage(Text.literal("§cCancelled — no changes saved."), false) }
                53 -> {
                    val config = state.buildConfig()
                    CobbleTrainerConfig.addOrUpdate(config)
                    serverPlayer.closeHandledScreen()
                    val evWarn = if (state.evWarning()) " §c(⚠ EV total capped by engine)" else ""
                    player.sendMessage(Text.literal("§a§lSaved! §r§7'§f${config.name}§7' — ${config.team.size} Pokémon | EXP ×${config.expMultiplier} | CD ${config.cooldownSeconds}s$evWarn"), false)
                    CobbleTrainerMod.LOGGER.info("Trainer '${config.id}' saved by ${player.name.string}")
                }
            }
        }

        override fun onClosed(player: PlayerEntity) {
            super.onClosed(player)
            (player as? ServerPlayerEntity)?.let { ChatInputHandler.cancel(it) }
        }

        override fun canInsertIntoSlot(stack: ItemStack, slot: net.minecraft.screen.slot.Slot) = false
        override fun canUse(player: PlayerEntity) = true
    }

    private fun bg(): ItemStack {
        val stack = ItemStack(Items.GRAY_STAINED_GLASS_PANE)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "))
        return stack
    }

    private fun item(mat: net.minecraft.item.Item, name: String, vararg lore: String): ItemStack {
        val stack = ItemStack(mat)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name))
        if (lore.isNotEmpty()) {
            stack.set(DataComponentTypes.LORE, LoreComponent(lore.map { Text.literal(it) }))
        }
        return stack
    }
}
