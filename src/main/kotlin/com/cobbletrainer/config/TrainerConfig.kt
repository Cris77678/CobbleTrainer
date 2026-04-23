package com.cobbletrainer.config

import kotlinx.serialization.Serializable

/**
 * Full configuration for a single custom NPC trainer.
 * This is what gets serialized to JSON and persisted on disk.
 */
@Serializable
data class TrainerConfig(
    /** Unique identifier for this trainer (used as storage key) */
    val id: String,

    /** Display name shown in battle / interact message */
    val name: String = "Trainer $id",

    /**
     * Pokémon team definition. Each entry uses Cobblemon's species-string format
     * e.g. "pikachu level=50 nature=timid" or simply "garchomp level=100"
     */
    val team: List<PokemonEntry> = emptyList(),

    /** EV rewards granted to each of the player's Pokémon that participated in battle */
    val evRewards: List<EVReward> = emptyList(),

    /**
     * Experience multiplier applied to the base EXP gained at battle end.
     * 1.0 = vanilla, 2.0 = double, etc.
     */
    val expMultiplier: Double = 1.0,

    /**
     * Cooldown in seconds before a player can challenge this trainer again.
     * 0 = no cooldown.
     */
    val cooldownSeconds: Long = 300L,

    /**
     * Whether to auto-import the creating player's current party into the trainer's team.
     * Only used during creation — after that, [team] is the authoritative source.
     */
    val importParty: Boolean = true,

    /**
     * Whether the trainer is enabled and can be battled.
     */
    val enabled: Boolean = true
)

/**
 * A single Pokémon in the trainer's party.
 * The [species] string maps to Cobblemon's PokemonProperties format.
 */
@Serializable
data class PokemonEntry(
    val species: String,
    val level: Int = 50,
    val nature: String = "",
    val ability: String = "",
    val heldItem: String = "",
    val moves: List<String> = emptyList(),
    val ivHp: Int = 31,
    val ivAtk: Int = 31,
    val ivDef: Int = 31,
    val ivSpAtk: Int = 31,
    val ivSpDef: Int = 31,
    val ivSpd: Int = 31
)

/**
 * EV reward granted per stat after winning the battle.
 */
@Serializable
data class EVReward(
    val stat: EVStat,
    val amount: Int
)

/**
 * Pokémon stat identifiers matching Cobblemon's EVs structure.
 */
@Serializable
enum class EVStat {
    HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED;

    /** Returns the Cobblemon stat key used in pokemon.evs */
    fun cobblemonKey(): String = when (this) {
        HP -> "hp"
        ATTACK -> "attack"
        DEFENCE -> "defence"
        SPECIAL_ATTACK -> "special_attack"
        SPECIAL_DEFENCE -> "special_defence"
        SPEED -> "speed"
    }
}
