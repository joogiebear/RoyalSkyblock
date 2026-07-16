# RoyalSkyblock

Scalable, per-island Skyblock for Paper — profiles, coop, a built-in bank, island levels, and
upgrades, all driven by clean configurable menus. Part of the Royal plugin suite, but **self-contained**:
no other Royal plugin is required.

Every island is its own [Advanced Slime Paper](https://github.com/InfernalSuite/AdvancedSlimePaper)
world, loaded on demand and unloaded when empty, so a server (or a whole network) can host thousands of
islands without a bloated single world.

## Requirements

| | |
|---|---|
| **Advanced Slime Paper** | **Required** — the ASP server fork, *not* vanilla Paper. Without it the plugin still enables, but island create/teleport are disabled (everything else works). |
| **Java 21+** | Required. |
| **Vault + an economy** | Needed for the bank and upgrade/coin costs. Any Vault economy works (EcoBits, Essentials, CMI, …). Without it, the bank and coin costs are disabled. |
| **eco / EcoItems** | Optional. Makes skills/jobs/coins *per-profile* (swapped on profile switch) and enables EcoItems in upgrade/bank item costs. |
| **PlaceholderAPI** | Optional. Used by command-based currencies (e.g. gems) and placeholders. |
| **WorldEdit / FastAsyncWorldEdit** | Optional. Enables `.schem` starter islands; without it, a built-in generator makes the starter island. |

## Quick start

1. Drop the jar on an **ASP** server that has **Vault + an economy**. Start once to generate the configs.
2. Open `config.yml` and set **`spawn.world`** to your hub/spawn world (where players go when they leave
   or delete an island).
3. Check **`currencies:`** in `config.yml` — `coins` is your Vault economy (works out of the box); `gems`
   is an example command-based currency (edit or remove it to match your setup).
4. (Optional) tune `bank.yml` (levels/interest), `upgrades.yml` (island upgrades), `levels.yml` (island
   level block values). Every menu lives in `gui/*.yml`.
5. `/is reload` to apply config/message/menu changes without a restart.

The console prints a status panel on boot showing which dependencies are active and what to configure first.

## Configuration files

Settings in one place, text and content split out — the same layout across the Royal suite.

| File / folder | Holds |
|---|---|
| `config.yml` | Core settings: storage, world backend, island generation, currencies, coop, spawn, flow limiter, teleport. |
| `messages.yml` | Every player-facing string. `&` colours, `%token%` placeholders. |
| `bank.yml` | The built-in bank: levels, max balances, interest, upgrade costs. |
| `levels.yml` | Island-level block values + scan/auto-recalc tuning + level-up rewards. |
| `upgrades.yml` | Island upgrades (size / guest limit / coop slots): tiers, costs, wait/skip times. |
| `gamemodes/*.yml` | One ruleset per gamemode (Solo / Coop / Ironman): display, icon, blocked commands. |
| `gui/<category>/*.yml` | One file per menu, grouped into folders: `core`, `profile`, `island`, `coop`, `bank`, `level`. Rows, a filler mask, and slots placed by row/column with click effects. |

Run `/is reload` to reload **all** of the above (config, messages, gamemodes, currencies, upgrades,
levels, bank, and menus).

## Storage

- **Island metadata** (`storage.type`): `sqlite` (default, zero-setup) or `mysql` (recommended for a network).
- **Island worlds** (`world.slime-data-source`): `file` (single server) or `mysql`/`mongo` (shared across a
  network). RoyalSkyblock ships its own `SlimeLoader`s, so no ASP companion plugin is required.

## Commands

Aliases for `/island`: `/is`, `/sb`, `/skyblock`.

**Players**

| Command | Description |
|---|---|
| `/is menu` | Open the island hub menu |
| `/is create` | Create your island |
| `/is home` (`/is go`) | Teleport to your island |
| `/is visit [player]` | Visit an island, or open the visit browser |
| `/is profile` | Manage profiles (Solo / Coop / Ironman) |
| `/is invite\|accept\|deny\|kick\|leave\|members` | Coop membership |
| `/is transfer\|promote\|demote <player>` | Coop ownership / ranks |
| `/is manage` | Island management (spawns, kick-all, coop) |
| `/is settings` | Island privacy / visitor settings |
| `/is upgrade` | Island upgrades menu |
| `/is level [recalc]` · `/is top` | Island level + leaderboard |
| `/bank` | Your bank (personal, and the coop bank on a coop profile) |
| `/is setspawn` · `/is setguestspawn` · `/is kickall` | Owner island controls |
| `/is delete confirm` | Delete your island (sends you to spawn) |

**Admin** (`royalskyblock.admin`)

| Command | Description |
|---|---|
| `/is reload` | Reload all config, messages, and menus |
| `/is admin testworld` | Diagnostic: ASP world round-trip |
| `/is admin schematic save <name>` | Save your WorldEdit selection as a starter schematic |
| `/is admin upgrade <key> <tier>` | Set an island upgrade tier instantly |

## Permissions

| Node | Default | Grants |
|---|---|---|
| `royalskyblock.use` / `.create` / `.home` / `.visit` / `.invite` / `.upgrade` / `.settings` | true | Normal player actions |
| `royalskyblock.admin` | op | `/is reload`, `/is admin …` |
| `royalskyblock.bypass` | op | Ignore island build protection / flow limiter |
| `royalskyblock.gamemode.bypass` | false | Ignore gamemode command rules (e.g. Ironman blocked commands). **Not** given to ops. |

## Graceful degradation

RoyalSkyblock adapts to whatever the server has:

- **No ASP** → enables, but island create/teleport are off (clear console message).
- **No Vault** → bank and coin costs disabled; everything else works.
- **No eco** → progression isn't per-profile (islands/profiles still work).
- **No WorldEdit/FAWE** → starter islands use the built-in generator.

## License / attribution

Part of the Royal suite by Mystipixel. Built for Advanced Slime Paper (MC 1.21+/Java 21).
