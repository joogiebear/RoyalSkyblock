# RoyalSkyblock — Changelog

Versioning is CalVer (`YYYY.WW.patch`). Dates are when the build was cut.

## 2026.29.0 — Beta 1

First public beta. RoyalSkyblock is a **self-contained** per-island Skyblock plugin — it needs no other
Royal plugin, and runs on whatever Vault economy your server already has.

### Requirements
- **Advanced Slime Paper** server (the ASP fork, not vanilla Paper) — required for islands.
- **Java 21+**.
- **Vault + an economy** — for the bank and coin costs (any Vault economy works).
- Optional: **eco/EcoItems** (per-profile progression), **PlaceholderAPI** (command currencies),
  **WorldEdit/FAWE** (`.schem` starter islands).

The console prints a status panel on boot; `/is admin status` shows the same in-game.

### Features
- **Per-island worlds** on ASP — loaded on demand, unloaded when empty; SQLite or MySQL metadata.
- **Profiles** — Solo / Coop / Ironman, each a self-contained save with its own island, inventory, and
  (with eco) per-profile progression. Ironman blocks trading commands.
- **Coop** — invite (clickable accept/deny), roster GUI, promote/demote co-owners, ownership transfer,
  kick/leave; shared island, separate inventories.
- **Built-in bank** — personal (per-profile) and coop (shared): deposit/withdraw, levels + upgrades,
  interest, transaction ledger. `/bank`. No RoyalBank required.
- **Island levels** — block-value scanning (throttled, off-thread), `/is level`, leaderboard `/is top`,
  optional level-up rewards, background auto-recalc.
- **Island upgrades** — size / guest limit / coop slots, wait-or-skip timers, configurable currency.
- **Visiting** — a browser of listed islands, gamemode-segregated, with privacy settings.
- **Clean configurable menus** — every screen is a `gui/<category>/*.yml` file (rows, filler mask,
  slots by row/column). Nothing is text-command-only.

### Admin quality-of-life
- Boot status panel + `/is admin status` (dependency + config health).
- Config validation on load/reload — warns, with the fix, about common mistakes (bad spawn world,
  missing economy, undefined upgrade currency, empty levels, …).
- Sane zero-edit defaults: works on a blank ASP+Vault server; upgrades cost Vault `coins` out of the box.

### Notes / known scope
- ASP is a hard requirement (this is the trade-off for per-island worlds); without it the plugin enables
  but island create/teleport are off.
- If migrating from a separate RoyalBank setup, bank balances aren't auto-imported (different database).
- MySQL is supported for both metadata and slime worlds (recommended for networks).

See `README.md` for the full config-file map, commands, and permissions.
