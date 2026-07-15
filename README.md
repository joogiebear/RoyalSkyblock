# RoyalSkyblock

Scalable, per-island Skyblock for Paper — part of the Royal plugin suite.

Every island is its own [Advanced Slime Paper](https://github.com/InfernalSuite/AdvancedSlimePaper)
world, loaded on demand and unloaded when empty, so a server (or a whole network) can host thousands
of islands without a bloated single world. Island metadata lives in a dual-dialect SQLite/MySQL store,
matching the rest of the suite.

## Requirements

- A server running **Advanced Slime Paper** (the ASP fork — not vanilla Paper). Without it, the plugin
  still enables but island world operations are disabled.
- Java 21+.
- Optional: Vault, PlaceholderAPI, eco / EcoItems, RoyalBank (soft dependencies).

## Configuration files

Organized the same way as the rest of the Royal suite — settings in one file, text and content split out:

| File / folder | Holds |
|---|---|
| `config.yml` | All settings, grouped by section (storage, world backend, island generation, spawn, teleport). |
| `messages.yml` | Every player-facing string. `&` colours, `%token%` placeholders. |
| `gamemodes/*.yml` | One file per gamemode ruleset (Solo / Coop / Ironman) — *coming with the profile system*. |
| `gui/*.yml` | One file per menu (EcoMenus dialect) — *coming with the GUIs*. |

Run `/is reload` to reload `config.yml` and `messages.yml`.

## Storage

- **Island metadata** (`storage.type`): `sqlite` (default) or `mysql`.
- **Island worlds** (`world.slime-data-source`): `file` (single server) or `mysql` (shared across a
  network). RoyalSkyblock ships its own `SlimeLoader` implementations, so no ASP companion plugin is
  required.

## Commands

| Command | Description |
|---|---|
| `/is create` | Create your island |
| `/is home` | Teleport to your island |
| `/is delete confirm` | Delete your island (sends you to the configured spawn) |
| `/is admin testworld` | Diagnostic: ASP world round-trip (admin) |
| `/is reload` | Reload configuration (admin) |

Aliases: `/island`, `/sb`, `/skyblock`.

## Status

Phase 1 (foundation) is complete and verified on a live ASP server: island create, home, and delete
(with player evacuation to spawn). Members/roles, build protection, borders, visiting, upgrades,
island levels and top-islands are in progress.
