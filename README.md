# CobbleTrainer

A **server-side** Fabric mod for Cobblemon that lets you create fully customizable NPC trainers with configurable EVs, experience multipliers, cooldowns, and party Pokémon — all manageable through an in-game GUI.

---

## Features

- 🏆 **Custom NPC Trainers** — create trainers with hand-picked or party-imported Pokémon
- 📊 **EV Rewards** — award up to 252 EVs per stat (any combination) after winning
- ⚡ **EXP Multiplier** — multiply experience gained (e.g. `2.0` = double EXP)
- ⏱️ **Cooldown System** — per-player, per-trainer cooldowns (in seconds)
- 🎮 **In-Game GUI** — 6-row chest GUI to configure everything without editing files
- 📥 **Party Import** — one-click import of your current Pokémon party into the trainer
- 💾 **Persistent Config** — trainers are saved to `config/cobbletrainer/trainers.json`
- 🔄 **Hot Reload** — `/cobbletrainer reload` to reload configs without restarting
- 🖥️ **Server-Side Only** — no client mod required

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.5+ |
| Fabric API | 0.103.0+1.21.1 |
| Fabric Language Kotlin | 1.12.1+kotlin.2.0.0 |
| Cobblemon | 1.6.1+1.21.1 |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.1
2. Add [Fabric API](https://modrinth.com/mod/fabric-api) to your server's `mods/` folder
3. Add [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) to `mods/`
4. Add [Cobblemon (Fabric)](https://modrinth.com/mod/cobblemon) to `mods/`
5. Drop `CobbleTrainer-1.0.0.jar` into `mods/`
6. Start the server — configs are auto-created in `config/cobbletrainer/`

---

## Building from Source

```bash
git clone https://github.com/yourusername/CobbleTrainer
cd CobbleTrainer
./gradlew build
# Output: build/libs/CobbleTrainer-1.0.0.jar
```

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/cobbletrainer create [id]` | OP (2) | Open the trainer creation GUI |
| `/cobbletrainer edit <id>` | OP (2) | Edit an existing trainer |
| `/cobbletrainer delete <id>` | OP (2) | Permanently delete a trainer |
| `/cobbletrainer list` | All | List all configured trainers |
| `/cobbletrainer info <id>` | All | Show trainer details |
| `/cobbletrainer challenge <id>` | All | Start a battle against a trainer |
| `/cobbletrainer toggle <id>` | OP (2) | Enable or disable a trainer |
| `/cobbletrainer cooldown clear <id> [player]` | OP (2) | Clear cooldown for trainer/player |
| `/cobbletrainer reload` | OP (2) | Reload trainer configs from disk |
| `/cobbletrainer help` | All | Show command list |

---

## GUI Layout

Open with `/cobbletrainer create` (6-row chest, server-side):

```
┌─────────────────────────────────────────────────────────────────────────┐
│   [★ CobbleTrainer Title]                                               │
│   [🏷 Trainer Name]  . . . . . . . . . . . . . . [📦 Import Party]     │
│   [🥚 Slot 1] [🥚 2] [🥚 3] [🥚 4] [🥚 5] [🥚 6]  [−] [✦ EXP x] [+] │
│   [💜 EV Label]  [HP▲] [ATK▲] [DEF▲] [SpA▲] [SpD▲] [SPE▲]            │
│   [−] [⏱ Cooldown] [+]  .  .  .  .  .  .  .  . [✔ Enabled]           │
│   [✗ Cancel]  .  .  .  .  .  .  .  .  .  .  .  .  [✓ Save & Apply]   │
└─────────────────────────────────────────────────────────────────────────┘
```

### GUI Controls

| Element | Left Click | Right Click | Shift+Click |
|---|---|---|---|
| Import Party | Import your party | — | — |
| EXP Multiplier −/+ | ±0.1 | — | ±0.5 |
| EV Stat buttons | +4 EVs | −4 EVs | +16/−16 EVs |
| Cooldown −/+ | ±30s | — | ±60s |
| Pokémon Slots | (info) | Clear slot | — |
| Enabled Toggle | Toggle on/off | — | — |
| Save & Apply | Save trainer | — | — |
| Cancel | Close without saving | — | — |

---

## Config Format (`config/cobbletrainer/trainers.json`)

```json
[
  {
    "id": "elite_ev_trainer",
    "name": "§6Elite EV Trainer",
    "team": [
      {
        "species": "garchomp",
        "level": 100,
        "nature": "jolly",
        "ability": "roughskin",
        "moves": ["earthquake", "outrage", "stoneedge", "swordsdance"],
        "ivHp": 31, "ivAtk": 31, "ivDef": 31,
        "ivSpAtk": 0, "ivSpDef": 31, "ivSpd": 31
      }
    ],
    "evRewards": [
      { "stat": "ATTACK", "amount": 252 },
      { "stat": "SPEED",  "amount": 252 }
    ],
    "expMultiplier": 2.0,
    "cooldownSeconds": 300,
    "enabled": true
  }
]
```

### EV Stat Keys

| Key | Stat |
|---|---|
| `HP` | Hit Points |
| `ATTACK` | Attack |
| `DEFENCE` | Defense |
| `SPECIAL_ATTACK` | Sp. Atk |
| `SPECIAL_DEFENCE` | Sp. Def |
| `SPEED` | Speed |

---

## How It Works

1. **Create** a trainer via GUI or by editing `trainers.json` directly
2. **Challenge** the trainer with `/cobbletrainer challenge <id>`
3. **Battle** using Cobblemon's standard battle system (Pokémon Showdown engine)
4. **Win** → your party Pokémon that participated receive the configured EVs and bonus EXP
5. **Cooldown** begins — you must wait before rematching

EV gain is additive and respects Cobblemon's per-stat cap (252) and total cap (510).  
The EXP multiplier adds bonus EXP on top of what Cobblemon normally awards.

---

## License

MIT License — see [LICENSE](LICENSE)

---

## Credits

Built on top of [Cobblemon](https://cobblemon.com/) by CobbledStudios.  
Cobblemon is licensed under [MPL-2.0](https://gitlab.com/cable-mc/cobblemon/-/blob/main/LICENSE).
