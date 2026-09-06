## 2026.36.1 — 2026-09-06

### 🐛 Fixes
- declare the expand placeholders as processResources inputs (`7cf7a8f`)

## 2026.36.0 — 2026-09-06

### ✨ Features
- island trash can - deletes archive, restores exist, orphans park (`d177b5c`)
- extensions.disabled, so an extension can be switched off without deleting it (`2a7acc6`)
- registry for third-party backends, so integrations can be extensions (`8aa2845`)
- let eco resolve profiles directly instead of copying keys (`85c680b`)
- starter chest accepts any eco item, not just vanilla materials (`a13098e`)
- migrate an existing islands.db into eco on the switch to it (`cdb05e1`)
- ship overseer enabled, parked only where EcoMinions is absent (`22c66d8`)
- /is admin split-content — convert monoliths to the folder layout (`b22fe96`)
- EcoStorage — islands and profiles on eco's data layer (`13d9468`)
- upgrades and perks as one file per thing (`65b20e3`)
- register with eco, not just PlaceholderAPI (`8c191db`)
- level-up rewards can be libreforge effect chains (`f6fc05d`)
- menu buttons run libreforge effect chains (`9bface0`)
- publish island events as triggers (`c6b0294`)
- publish island state as conditions (`48e8556`)
- per-button click sounds, no hardcoded sound anywhere (`1e142e3`)
- minion_count_above condition (`74c57c8`)
- publish EcoMinions activity as libreforge triggers (`05fc7e2`)
- rebuild perks and add a pure-effects upgrade track (`f15c061`)
- render the data-driven menus through eco (`6b6bb7a`)
- render the fully config-driven menus through eco (`b1da941`)
- EcoMenuFactory — build eco Menus from the existing templates (`c513cf3`)
- perks and island upgrades as libreforge effect holders (`f57d79e`)
- make the plugin a first-class eco/libreforge plugin (`cb19c49`)
- minions slot-upgrade track for the RoyalMinions cap (`75d195c`)

### 🐛 Fixes
- declare condition arguments without libreforge's default-argument bridge (`d506343`)
- recompile against eco 2026.35 and libreforge 2026.35.1 (`2111c35`)
- deduplicate concurrent island creation per profile (`175c5a0`)
- never save a profile whose items failed to deserialize (`ed060ca`)
- only clear per-player borders this service applied (`e9370a1`)
- create indexes without IF NOT EXISTS on MySQL (`6a76888`)
- scope minion-slot grants to the island's own world (`897fbb5`)
- a configured starter schematic that doesn't resolve now says so (`86dc7f4`)
- make the minion-slot track actually grant minion slots (`f3c2822`)
- compile perk and upgrade chains from the content folders (`19ff223`)
- warn when a generator upgrade tier has no generators.yml entry (`4b79d2b`)
- personal bank menu was missing its Back button (`923ebbb`)
- don't let shipped defaults shadow an existing perks.yml (`0bafd4f`)
- compile menu chains at load, not on first click (`5c49b71`)
- give generated buttons a sound again (`1227506`)
- register minion elements before compiling perk and upgrade chains (`de41606`)
- add EcoMinions to softdepend so the minion triggers actually register (`7d1f878`)
- derive the click sound for buttons that open another menu (`d0bdea4`)
- restore click parity for data-driven slots (`e0ac529`)
- make the build reproducible off this machine (`8085d43`)
- read config.yml off disk instead of eco's toBukkit conversion (`7b3b827`)
- add the lang.yml EcoPlugin requires to load (`289cfa6`)

### ♻️ Refactors
- remove the minion elements - the core now hooks no third-party plugin (`c9c9974`)
- remove the built-in EcoMobs and EcoSkills integrations (`4f865f0`)
- perk switches move to config.yml, perks.yml stops shipping (`8d98562`)
- /island and /bank on eco's command framework (`6c10011`)
- rename the renewal perk id to regen (`ed9a117`)
- extract Storage into an interface (`e046306`)
- compact cost form, a NONE effect, and one sound debounce (`f70047e`)
- remove the legacy inventory rendering path (`e8570a8`)
- fold messages.yml into eco's lang.yml (`2a8d6b9`)

### 📝 Documentation
- runbook for rebuilding when Auxilor releases (`b4a08cd`)
- state the Paper 26.2-or-newer requirement (`0b28814`)
- correct the engine line in every menu header (`3016dc4`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- let a slot opt out of the menu's click sound (`9d276e9`)
- rebuild the generated starter island around a house and a portal isle (`daaa00b`)
- make menu sounds configurable (`2b43fc6`)
- tiered ore generator (`b295b3f`)
- let another plugin own a hotbar slot (`ec7c07d`)
- report anonymous usage stats via bStats (`0286c68`)

### 🐛 Fixes
- protect visitors' theft routes, not just their block edits (`e9984fb`)
- never persist a null inventory blob over a good profile row (`132bcd2`)
- shutdown island save never ran — use a synchronous save path (`0a13278`)

### ⚡ Performance
- keep player join and hot reads off the main thread (`d8fd34c`)
- take the island browser and leaderboard off the main thread (`e58c831`)

### 📝 Documentation
- drop internal roadmap language and correct the command list (`e2b61de`)

## 2026.29.5 — 2026-07-18

### ✨ Features
- intimidation targeting bridge — weak mobs ignore the player (`96cbf5e`)
- pluggable, player-driven island mob spawning (`495a6a9`)
- %royalskyblock_profile_id% — stable active-profile id (`4b0dfd3`)

### 🐛 Fixes
- stop witches — clear target and block damage from intimidated mobs (`d97723e`)
- disable vanilla hostiles alongside tiered mobs; show intimidation state (`a9ac128`)
- save online players + loaded islands on shutdown (onDisable) (`d9b5a91`)
- resolve EcoSkills combat skill lazily (registry empty at startup) (`c6542c7`)
- soft-depend EcoMobs + EcoSkills so they load before RoyalSkyblock (`e805327`)

## 2026.29.4 — 2026-07-18

### ✨ Features
- void protection — catch the fall instead of slow void ticks (`7144f18`)
- configurable per-island world rules (gamemode + gamerules) (`e48d56f`)
- disable hunger server-wide (gameplay.disable-hunger, default on) (`4c5d42c`)

### 🔧 Other
- move hunger control out to the standalone NoHunger plugin (`df8f3b9`)

## 2026.29.3 — 2026-07-17

### ✨ Features
- scale custom-mob strength by island level (`65dee82`)
- %royalskyblock_island_level% — level of the island you're IN (`8b106f7`)
- /is admin loadtest — island lifecycle benchmark (`b5b92b8`)
- per-simulator catch-up logging + accept any air for stacking plants (`8dfc7d0`)
- BlockSimulator registry, and cane/cactus catch-up (`1be36d0`)
- unload empty islands and pay back the missed time (`34afc73`)

### 🐛 Fixes
- save an island the moment it empties, not 60s later (`961e138`)
- section index is measured from the world's min height (`3101f76`)
- take chunk snapshots with the height map (`a3b8c6c`)
- scan the island's own chunks, not the loaded ones (`623fb08`)
- SELECT the unloaded_at column the reader expects (`af73a32`)
- pass the Modrinth payload as a file, not inline (`6630430`)

### ⚡ Performance
- cap islands unloaded per pass (`58eb6fd`)

## 2026.29.2 — 2026-07-17

### ✨ Features
- on_island / on_own_island for gating eco effects to islands (`01435c5`)
- route bedless respawns to spawn (hub), respect beds (`e3f921e`)
- teleport players to spawn on join (skip island-loggers) (`1919a8e`)
- PlaceholderAPI expansion (%royalskyblock_...%) (`5653bb9`)

### 🐛 Fixes
- defer boot config check to first tick (`2699b96`)
- correct EcoBits currency example syntax (`d0b95df`)

## 2026.29.1 — 2026-07-16

_Maintenance release._

## 2026.29.0 — 2026-07-16

### ✨ Features
- add a cozy starter hut to the built-in generator (`9f45457`)
- /is admin border command + debug toggle (`4fa4f1d`)
- show island size as NxN in the upgrades menu (`fa4e4c6`)
- per-player island border — scales with size, colour, admin-bypass (`e8a474b`)
- optional level-gated perks (off by default) (`63f626f`)
- graceful degradation & config validation (`22786f7`)
- in-game admin experience (`e229cf5`)
- native self-contained bank — no RoyalBank required (`f8256e5`)
- per-profile personal banks + full coop bank + bank hub (`51e9c62`)
- shared coop bank via RoyalBank (Vault fallback) (`d64b25a`)
- ownership transfer + co-owner promote/demote (`67b17de`)
- level-up rewards + background auto-recalc (`0c79c43`)
- island levels — block scan, /is level, /is top (`a52cd1e`)
- coop management + island management menus (`46bc1f9`)
- pin dynamic entries to exact slots + clean upgrades configs (`be40a93`)
- visit browser + click sounds (`b0fca82`)
- wait-or-skip purchasing + upgrades GUI (U-3, U-4) (`f932b2d`)
- upgrade framework + effects (size/guest-limit/coop-slots) (`f8ff4df`)
- richer code-generated starter island (`357a0e8`)
- configurable currency layer for upgrade costs (`a2bb3ee`)
- admin bucket bypass window (`5075f3a`)
- spawn commands + anti-crash liquid flow limiter (`67dfc12`)
- per-island settings + visitor privacy (`eed99a8`)
- invite/accept/kick/leave for shared-island Coop profiles (`387a7ef`)
- WorldEdit/FAWE schematic support + code-gen fallback (`c03a1da`)
- profile switcher + gamemode picker menus (`4ec407b`)
- Hypixel-style profile backbone (islands now belong to profiles) (`9eb55a5`)
- port the suite EcoMenus engine + island main menu (`f332f6f`)
- configurable starter island + per-island world border (`696a203`)
- eco per-profile data bridge (spike) (`5740340`)
- build protection + /is visit (`c7e76fc`)
- RoyalSkyblock foundation — per-island SlimeWorld backend (`5610e62`)

### 🐛 Fixes
- world-border max is 59,999,968 not 60,000,000 (`95765a1`)
- starter chest items now actually populate (`16a75c3`)
- gate uses dedicated royalskyblock.gamemode.bypass (default false) (`9cbc716`)
- connection-pool deadlock on profile load (`de59e94`)

### ⚡ Performance
- live GUI countdown, guarded to near-zero idle cost (`0ec9f29`)

### ♻️ Refactors
- organize menus into category folders (`a6f378a`)
- externalize player-facing text to messages.yml (`ec6932a`)

### 📝 Documentation
- document currency versatility + more examples (`fbd32a2`)
- message audit + beta changelog (`136e265`)
- truth & first-run orientation (`0aa4e0c`)

### 🔧 Other
- config coherence & sane defaults (`237767b`)
- accept cap re-check, clickable invites, join notices, cleanup (`9920699`)
- invite error names your current profile + gamemode (`a3138ed`)

