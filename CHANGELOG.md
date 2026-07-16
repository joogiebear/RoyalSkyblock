## 2026.29.1 — 2026-07-16

_Maintenance release._

## 2026.29.0 — 2026-07-16

### ✨ Features
- add a cozy starter hut to the built-in generator (`9f45457`)
- /is admin border command + debug toggle (`4fa4f1d`)
- show island size as NxN in the upgrades menu (`fa4e4c6`)
- per-player island border — scales with size, colour, admin-bypass (`e8a474b`)
- optional level-gated perks (off by default) (`63f626f`)
- Phase 4 — graceful degradation & config validation (`22786f7`)
- Phase 3 — in-game admin experience (`e229cf5`)
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
- RoyalSkyblock Phase 1 foundation — per-island SlimeWorld backend (`5610e62`)

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
- Phase 5 — message audit + beta changelog (`136e265`)
- Phase 1 — truth & first-run orientation (`0aa4e0c`)

### 🔧 Other
- Phase 2 — config coherence & sane defaults (`237767b`)
- accept cap re-check, clickable invites, join notices, cleanup (`9920699`)
- invite error names your current profile + gamemode (`a3138ed`)

