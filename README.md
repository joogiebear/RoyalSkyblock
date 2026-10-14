# RoyalSkyblock

Scalable, per-island Skyblock for Paper: profiles, coop, a built-in bank, island levels, and
upgrades, all driven by clean configurable menus. Part of the Royal plugin suite, but **self-contained**:
no other Royal plugin is required.

Every island is its own [Advanced Slime Paper](https://github.com/InfernalSuite/AdvancedSlimePaper)
world, loaded on demand and unloaded when empty, so a server can host thousands of
islands without a bloated single world.

## Requirements

| | |
|---|---|
| **eco** | **Required**: RoyalSkyblock is an eco plugin and will not load without the platform. Install [eco](https://polymart.org/resource/eco.773) first, and keep it on the same `YEAR.WEEK` version line as the rest of the suite. |
| **Advanced Slime Paper** | **Required**: the ASP server fork on 26.2 or newer, *not* vanilla Paper. Without it the plugin still enables, but island create/teleport are disabled (everything else works). |
| **Java 25** | Required: Paper 26.2 and the ASP API both require JVM 25. |
| **Vault + an economy** | Needed for the bank and upgrade/coin costs. Any Vault economy works (EcoBits, Essentials, CMI, …). Without it, the bank and coin costs are disabled. |
| **EcoItems** | Optional. Makes skills/jobs/coins *per-profile* (swapped on profile switch) and enables EcoItems in upgrade/bank item costs. |
| **PlaceholderAPI** | Optional. Used by command-based currencies (e.g. gems) and placeholders. |
| **WorldEdit / FastAsyncWorldEdit** | Optional. Enables `.schem` starter islands; without it, a built-in generator makes the starter island (grass platform + a cozy oak hut, pond, crop farm, and tree). |
| **EconGuard** | Optional. If installed, bank deposits, withdrawals, coop bank activity and interest are reported to its shared audit ledger. See [EconGuard integration](#econguard-integration). |

### Tested eco versions

| RoyalSkyblock | Built against | Tested on |
|---|---|---|
| 2026.39.3 | eco 2026.35, libreforge 2026.35.1 | eco 2026.38 (with the 2026.38 Auxilor suite) |
| 2026.39.2 | eco 2026.35, libreforge 2026.35.1 | eco 2026.38 (with the 2026.38 Auxilor suite) |

Newer eco releases usually work, but these are the ones confirmed. Before moving a server to a newer
eco, boot a test copy, open `/is` and its menus, and check the log for RoyalSkyblock errors.

For developers: eco and libreforge types are kept to a fixed set of adapter files (the plugin class,
commands, `gui/Eco*`/`GuiManager`, `hooks/`, `libreforge/`, `data/EcoStorage`). `EcoBoundaryTest` fails
the build if eco is imported anywhere else, so an eco API change only ever touches those files.

## Quick start

1. Drop the jar on an **ASP** server that has **Vault + an economy**. Start once to generate the configs.
2. Open `config.yml` and set **`spawn.world`** to your hub/spawn world (where players go when they leave
   or delete an island).
3. Check **`currencies:`** in `config.yml`: `coins` is your Vault economy (works out of the box); `gems`
   is an example command-based currency (edit or remove it to match your setup).
4. (Optional) tune `bank.yml` (levels/interest), `upgrades.yml` (island upgrades), `levels.yml` (island
   level block values). Every menu lives in `gui/*.yml`.
5. `/is reload` to apply config/message/menu changes without a restart.

The console prints a status panel on boot showing which dependencies are active and what to configure first.

## Configuration files

Settings in one place, text and content split out, the same layout across the Royal suite.

| File / folder | Holds |
|---|---|
| `config.yml` | Core settings: storage, world backend, island generation, currencies, coop, spawn, flow limiter, teleport. |
| `lang.yml` | Every player-facing string. `&` colours, `%token%` placeholders. Also eco's language file. |
| `bank.yml` | The built-in bank: levels, max balances, interest, upgrade costs. |
| `levels.yml` | Island-level block values + scan/auto-recalc tuning + level-up rewards. |
| `upgrades.yml` | Island upgrades (size / guest limit / coop slots): tiers, costs, wait/skip times. |
| `gamemodes/*.yml` | One ruleset per gamemode (Solo / Coop / Ironman): display, icon, blocked commands. |
| `gui/<category>/*.yml` | One file per menu, grouped into folders: `core`, `profile`, `island`, `coop`, `bank`, `level`. Rows, a filler mask, and slots placed by row/column with click effects. |

Run `/is reload` to reload **all** of the above (config, messages, gamemodes, currencies, upgrades,
levels, bank, and menus).

## Storage

- **Island metadata** (`storage.type`): `sqlite` (default, zero-setup), `mysql`,
  or `eco` (eco's own data layer, like the rest of the suite; **single server only**: eco caches data per
  server and has no atomic updates, so servers sharing one eco database overwrite each other's island and
  profile lists; the plugin warns at startup if it sees eco storage on a shared eco handler).
- **Island worlds** (`world.slime-data-source`): `file` (default), `mysql` or `mongo`. RoyalSkyblock ships
  its own `SlimeLoader`s, so no ASP companion plugin is required.

### Several servers, one database

Servers may share one MySQL or MariaDB database. Set on every server:

- `storage.type: mysql` and the same `storage.mysql` block.
- `world.slime-data-source: mysql`, so island blocks live in the shared database too. With `file`, each
  server keeps its own copy of the blocks and the plugin warns at startup.
- `storage.server-id`: a name of your choosing per server (`hub`, `islands-1`). Players see it when an
  island is open somewhere else. Left blank, an id is generated and kept in `server-id.txt`; a copied
  server folder copies that file too, so give copies their own id.

How it behaves:

- **One host per island.** The server that loads an island holds a claim on it in `rsb_island_hosts`
  and renews it every 30 seconds. Another server refuses to load it while the claim stands and tells
  the player which server has it (`island.hosted-elsewhere`, `visit.hosted-elsewhere`). The claim is
  released when the island unloads (after `world.unload-grace-seconds`) or the server stops. A crashed
  server's claims expire after 90 seconds.
- **Player hand-off.** A player joining one server while the last one is still saving them is held at
  the login screen for up to 5 seconds, then turned away with `profile.still-saving` if the save hasn't
  landed. Proxy (Velocity, BungeeCord) server switches are not supported yet.
- **Shared data.** Island and profile changes are written column by column, so a change on one server
  never undoes one made on another; other servers pick it up within 5 seconds. Bank movements lock the
  account row, so a coop balance can't be spent twice from two servers.
- **Upgrade timers** finish on the server hosting the island, or when it next loads.
- **Schema.** The first server to start on a new plugin version migrates the database; the others wait
  for it. A server running an older plugin version than the database refuses to start.
- Coop invites must be accepted on the server they were sent from, and the island trash stays on the
  disk of the server that deleted the island.
- Keep the servers' clocks in sync: offline catch-up and upgrade timers use the server clock.

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
| `/is admin status` | Dependency + config health panel (in-game) |
| `/is admin border <blue\|red\|green\|off>` | Set the island border colour, live |
| `/is admin testworld` | Diagnostic: ASP world round-trip |
| `/is admin schematic save <name>` | Save your WorldEdit selection as a starter schematic |
| `/is admin upgrade <key> <tier>` | Set an island upgrade tier instantly |

## Permissions

| Node | Default | Grants |
|---|---|---|
| `royalskyblock.use` / `.create` / `.home` / `.visit` / `.invite` / `.upgrade` / `.settings` / `.bank` | true | Normal player actions |
| `royalskyblock.admin` | op | `/is reload`, `/is admin …` |
| `royalskyblock.bypass` | op | Ignore island build protection / flow limiter |
| `royalskyblock.gamemode.bypass` | false | Ignore gamemode command rules (e.g. Ironman blocked commands). **Not** given to ops. |
| `royalskyblock.playmode.bypass` | op | Ignore the per-world gamemode enforcement |

## EconGuard integration

If [EconGuard](https://github.com/joogiebear/EconGuard) is installed, the island bank reports its
movements to the shared ledger (`bank` source), the same way RoyalBank does, so `/eg history <player>`
shows them next to the player's other economy activity. There is nothing to enable.

How each movement counts in EconGuard's detection:

- Moving your own money between purse and bank (personal deposit and withdrawal, coop deposit, the
  personal balance paid out after leaving a coop) is not incoming.
- A coop bank withdrawal is split by who paid the money in. Your own deposits coming back are not
  incoming. Money another member deposited is incoming, with that member as counterparty. Money with no
  known depositor (paid in before the last restart, or coop interest) is incoming with no counterparty.
- Interest on a personal account is incoming with no counterparty. Coop interest is not reported when
  it is claimed, because no member has it yet; it counts when someone withdraws it.

A failed report never fails the bank action.

## Graceful degradation

RoyalSkyblock adapts to whatever the server has:

- **No ASP** → enables, but island create/teleport are off (clear console message).
- **No Vault** → bank and coin costs disabled; everything else works.
- **No EcoItems** → progression isn't per-profile (islands/profiles still work). eco itself is required and the plugin will not load without it.
- **No WorldEdit/FAWE** → starter islands use the built-in generator.

## API for other plugins

`com.mystipixel.royalskyblock.api.RoyalSkyblockApi` holds static methods that use JDK types only, so a
plugin can call them by reflection without depending on RoyalSkyblock at build time.

`savePlayerNow(UUID player)` saves the player's active profile (inventory, ender chest, XP, health,
hunger, eco progression) right away instead of at the next autosave or quit. Call it after anything
that moves items or money between players, such as a completed trade, so a crash can't roll one side
back. It uses the same path as the periodic autosave: captured on the main thread, written on the
storage thread, and never written over a later save. Calls for the same player within one second fold
into one save at the end of that second. It returns false if RoyalSkyblock isn't enabled or the player
has no loaded profile, and can be called from any thread.

```java
try {
    Class.forName("com.mystipixel.royalskyblock.api.RoyalSkyblockApi")
            .getMethod("savePlayerNow", UUID.class)
            .invoke(null, player.getUniqueId());
} catch (ReflectiveOperationException | LinkageError notInstalled) {
    // RoyalSkyblock not installed, or too old to have the method
}
```

Resolve the `Method` once at startup rather than on every call. Extensions that compile against
RoyalSkyblock can call `RoyalSkyblockApi.savePlayerNow` directly.

## License / attribution

Part of the Royal suite by Mystipixel. Built for Advanced Slime Paper (MC 26.2 / Java 25) on the eco platform.
