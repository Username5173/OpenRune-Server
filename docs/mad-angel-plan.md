# Mad Angel (post-quest) — implementation plan

Target: the post-quest Mad Angel boss in Ardeaglais
(<https://oldschool.runescape.wiki/w/Mad_Angel>), implemented as a new instanced boss in
OpenRune, and verified in a live client through the OpenRune Developer Tools MCP.

Reference implementation: the captured TSPS plugin
(`D:\Runescape\tsps\server\plugins\bosses\madangel\*` + `data/mad-angel.json`). Its numeric
data (ids, animations, tiles, timings, sweep geometry) is treated as authoritative where it
agrees with the wiki.

## Testing safety

`::god` toggles invincibility (`AdminCommands.kt`, `Rights.ADMINISTRATOR`; dev realms grant
ADMIN on login). Verified live against the real Mad Angel NPC:

| State | Result |
|---|---|
| `::god` on | 17 hits landed on the player in 60 s, all 0 damage, HP pinned at 99 |
| `::god` off | Angel hit for 21 + 7 → HP 99 → 72 |
| `::god` back on | HP stable, Angel still landing 0s |

The same pipeline reports real numbers for the player's own hits on the Angel (0/5/6/7/9),
so the 0s are god mode, not a broken feed.

`get_combat_history` labels every hitsplat by its **target**, not its source: `actorType:
"player"` entries are hits the Angel landed on the player, `actorType: "npc"` entries are the
player's hits on the Angel. Reading the wrong side is the easy way to conclude "the boss never
lands a hit" or "the boss takes no damage" from a perfectly healthy fight.

`::god` has no varp (`AdminCommands.kt` flips `player.adminGodMode`), and the MCP cannot read
chat, so the only read-out is the `God mode enabled./disabled.` line in the chatbox. Confirm
every toggle from a screenshot before trusting the numbers from that window — otherwise a
"god-off" damage read silently measures an on window.

Other test commands: `::maxhit`, `::die pvm|pvp`, `::npc <cycles> <npc>`, `::tele x y [z]`,
`::item <obj> [n]`, `::anim`, `::spot`, `::synth`, `::object`, `::instanceexit <key>`,
`::varp`, `::varbit`.

Gap: no command sets an NPC's hitpoints, so the module ships `::madangelhp <hp>` for reaching
the 350-HP enrage quickly.

## What already exists (verified)

| Asset | Status |
|---|---|
| `npc.mad_angel` (16305), `_initial` (16306), `_anim` (16307), `_dead` (16308) | In cache; `npc.mad_angel` is combat 588, idle anim 4588 |
| `_quest` / `_cathedral_vis` variants | In cache |
| `loc.wyrmscraig_cathedral_pew_broken_climb` (62250, `Climb`) @ 2538,2215 | Verified live |
| `loc.wyrmscraig_cathedral_pew_broken_exit` (62251) | Not in world — placed inside the instance |
| `loc.mad_angel_catherdral_doorl/doorr` (62368/62369, `Open`) @ 2541,2215-2216 | Verified live (official spelling: *catherdral*) |
| Animations `npc_mad_angel_idle/attack_melee/attack_sweep_*/attack_bomb_throw/attack_death/spawn` | Resolved (4588, 4589, 14429-14448, 3321) |
| VFX `vfx_mad_angel_spawn/attack_bomb_throw(+_projectile)/attack_smite(+_enraged/+_impact)` | Resolved (4010-4017) |
| `varp.total_mad_angel_kills` = 5712 | Official symbol for the kill count |
| Items `sunstone`, `sunstone_crystal`, `hallowfell`, `jar_of_light`, `teleportscroll_ardeaglais`, `mad_angel_sword` | In cache |
| Pet `obj.madangelpet` 1/2000 for `npc.mad_angel` and `npc.mad_angel_quest` | Already wired in `content/other/pets` |
| Ardeaglais map (region 10018) | Present and loaded live |
| `.data/raw-cache/server/npcs.toml`, `varp.toml`, `.data/gamevals/*.rscm` | Tracked in git |

Missing: the *Fallen From Grace* quest, any Ardeaglais area module, and Combat Achievement
infrastructure (no task registry/DSL in this repo).

## Target behaviour

- **Wake**: spawn anim 3321 + vfx 4010, face player, music 885; fight starts 3 ticks later.
- **Standard**: melee, speed 6, hit delay 2, 0-31; Protect from Melee scales max 31 → 5.
  After **3** standards, a special, cycling **sweep → blast → smite** from a random entry
  (the capture's first special was the blast).
- **Sweep** (blocks attacks): face the player on an axis, windup anim per side, then 4 cleaves
  every 6 ticks (enraged 6 cleaves / 4 ticks). Each cleave hits the quarter between the
  boss's facing and the sword side (dust gfx on that shape), 0-31 (×0.5 under Protect from
  Melee), then the side stays or swaps. Reset anim at the end, re-attack after the cleave
  interval. Dodging to the side (beside, not behind) grants `accurate`: next hit is
  guaranteed to land and rolls at least 50% of max hit.
- **Blast** (does not block attacks): projectile at a tile within 2 of the player, including
  the tile the player is standing on (19 August 2026 change), never under the boss and inside
  the arena, shadow gfx. Standing on it bounces the ball (18-22 to the boss, up to 3 throws).
  Missing blows up the whole arena (delay = 2 × manhattan), 30-32, ×0.5 under Protect from
  Magic, only if the player is inside the arena.
- **Smite** (blocks attacks): strikes at tick 5 (enraged 5, 9, 11); damage rolls 0-31.
  Protect from Magic already on → ×0.25; switched on the strike tick or the tick before
  (including a 1-tick flick) → 0 damage and grants `max` (next hit is a max hit).
- **Enrage at ≤350 HP**, at the next attack: force-chat `...!`, wait 4 ticks, then
  smite → sweep → blast back to back (capture 372 / 390 / 420), no standard attacks
  afterwards; sweeps and blasts faster, smite triple.
- The boss must not regenerate between specials.
- **Death**: anim 14448, transform to `_dead`, kill count via varp 5712, fight duration vs
  personal best, drops, HP bar fades 4 ticks later; corpse 26 ticks, then a fresh dormant
  angel on the same tile.

## Architecture mapping (TSPS → OpenRune)

Module `content/bosses/mad-angel/`, modelled on `content/bosses/amoxliatl`.

| Concern | Reference | OpenRune |
|---|---|---|
| Spec/registration | custom `CombatMethod` | `BossPluginScript` + `boss("npc.mad_angel", …_initial, …_anim, …_dead)`, `stats(attackRate = 6)` |
| Standard attack | `Fight.melee` | `ability("melee") { anim("seq.npc_mad_angel_attack_melee"); hit { damage((0..31).roll()) } }` |
| Special rotation | `queue`/`rotation` | `forceEveryAttacks(3, 3, special)` + `choose(Rotation(listOf("sweep", "blast", "smite")))` |
| Enrage | `enragePending` at 350 | `forceWhen(hpBelow(350 / <cache hitpoints>), "enrage")` in the combat phase: `say(..!)`, `nextAttackIn(4)`, `transitionTo("enraged")`, whose rotation is smite → sweep → blast |
| Sweep/blast/smite | imperative | DSL skeleton + `external("madangel.…")` Kotlin handlers |
| Prayer scaling | `angelHitRoll` | player-hit modifier dep from `BossDeps` |
| `next-hit` buff | `angelHitRoll`/`angelHitModify` | `BossCombat.register(…, onModifyHit, onHit)` |
| Instance | 13×13-chunk template | `InstanceScript` + `InstanceArea.copyRegions(centerRegionId = 10018, gridSize = 1)` |
| Exit pew inside | `placeExitPew()` | place `loc.…_exit` on entry |
| HP HUD | custom events | `param.boss_hp_bar_mode` + `BossHpBarScript` |
| Kill count | attribute + varp 5712 | free: `"param.killcount_varp" = "varp.total_mad_angel_kills"` |
| Personal best | persisted attribute | `InstanceKillTimer` (temp attribute, lost on logout) |
| Drops | wiki table | `content/drops/.../monsters/mad_angel.toml`, pet excluded |
| Quest gate | "after Fallen From Grace" | `QuestRequirements.hasCompleted(player, <ffg key>)` on the pew climb |
| Dev command | `::madangelhp` | new `onCommand("madangelhp")` |
| Combat achievements | 9 tasks | blocked, no infra |

## Phases

**Phase 1 — skeleton (bootable, no mechanics)**
1. Module `content/bosses/mad-angel/` with `build.gradle.kts`.
2. `MadAngel.kt` (spec skeleton: states + melee), `MadAngelInstance.kt`, `MadAngelCathedral.kt`.
3. Instance row `dbrow.instance_mad_angel` in `InstanceSettingsTable.kt` + dbrow gameval id.
4. NPC params in `.data/raw-cache/server/npcs.toml` for `npc.mad_angel`.
5. `mergePluginGamevals` → `buildCache` → `run`.

**Phase 2 — fight mechanics**
6. `MadAngelFight.kt`: wake, rotation, enrage.
7. `MadAngelSweep.kt` / `MadAngelBlast.kt` / `MadAngelSmite.kt` external handlers with the
   sweep shape tables from `mad-angel.json`.
8. Player-hit hooks: melee prayer scaling, `accurate`/`max` next-hit buffs, regen suppression.

**Phase 3 — instance and lifecycle**
9. Pew climb entry with fade, exit pew placement inside, leave prompt.
10. Death/respawn in place (~27 ticks) without destroying the instance; repeated kills restart
    the kill timer.

**Phase 4 — data and QA**
11. Kotlin `RSDropTable` drop table from the wiki (no pet roll; the pet is already in
    `PetDropsTable`).
12. `::madangelhp`.
13. `spotlessApply`, `:api:meta:konsistTest`, `test`, then the live MCP pass.

## Open decisions

1. Enrage HP: absolute 350, converted to `entryHp` with the npc's own cache hitpoints read at spec
   build time (`ServerCacheManager`). The live boss bar shows a 755 maximum, so the fraction is
   derived rather than hardcoded.
2. Personal best: accept the framework's temp attribute, or add a persisted varp?
3. Combat achievements: out of scope.
4. Quest gate key: `quest_fallenfromgrace` vs `quest_ffg`.
5. Instance copy: region 10018 alone (verified: the pew enters the copied scene and everything
   outside the region reads as void). OSRS's exact 13-chunk copy is not reproduced.

## Live test protocol (MCP)

```
get_client_state                                  -> LOGGED_IN
type_chat "::god" + screenshot                    -> "God mode enabled."
type_chat "::tele 2539 2216" / walk_to
interact_object "Church pew" Climb                -> instance entry (telejump lands ~10-20 s later)
get_client_state                                  -> region 41217, coords move to the copy
list_npcs "mad angel"                             -> dormant, no Attack, has Wake
get_npc_menu "mad angel"                          -> confirm the Wake op
interact_npc "mad angel" Wake                     -> needs the player adjacent; queue the op then walk
get_effect_history sinceMs 8000                   -> spawn anim 3321 + vfx 4010, then melee every 6t
get_combat_history actorType "player"             -> hits *on* the player: 0s (t12 misses) while god is on
get_combat_history actorType "npc"                -> the player's hits *on* the angel: real numbers
type_chat "::god" + screenshot "disabled."        -> read real melee numbers (0-31 / 0-5 under Protect
                                                     Melee), then toggle back on and screenshot "enabled."
get_effect_history                                -> sweep anims + dust gfx (screenshot)
walk_to <dust-free side>                          -> dodge + "accurate" buff
list_projectiles                                  -> blast ball + target tile
walk_to <shadow tile>                             -> bounce, 18-22 to the boss, x3
pray Protect from Magic on the strike tick        -> 0 damage + next hit is a max hit
type_chat "::madangelhp 350" + screenshots        -> "...!" (4 ticks later) then smite -> sweep -> blast
type_chat "::madangelhp 5" + attack
wait_for npc_dead "mad angel"                     -> death anim 14448
list_ground_items / get_inventory                 -> drops (screenshot shows the KC/PB/collection-log lines)
list_npcs "mad angel" (after ~26 ticks)           -> dormant angel respawned
get_var "varp" 5712                               -> kill count incremented
```

Damage numbers cannot be validated with god mode on; toggle it off only for short, controlled
reads and back on immediately. Turn it off, keep it off only until the next real hit lands, and
watch the player's Hitpoints in `get_skills` as the abort signal.

`type_chat` types the text but the Enter press is unreliable: a command can sit unexecuted in the
chatbox, and an immediate resend can concatenate onto it. Send the command, take a screenshot,
and only trust the effect once the chat shows the reply. `::tele`, `::god` and `::madangelhp`
have all been observed to apply one call late.

## Deviations from the reference (verified while implementing)

- **Sweep shapes** are a runtime flood, not the reference's static quarter tables. The capture's six
  enraged cleaves fit a fixed **102-tile footprint** only because that room happens to be that size:
  transcribed as a table it is clipped to the reference room, and this instance places the angel
  about two tiles east of where the capture's stands, so the dust overshot to the east/south and
  stopped short to the west/north. Each cleave now floods from the angel's centre across the
  bisector half of the room and keeps going until collision stops it (`sweepTiles`, 4-directional,
  `SWEEP_REACH` as a safety cap). The membership rule is unchanged: tiles on the facing/hand
  bisector belong to its counter-clockwise side. The flood tests `canStep` rather than
  `isWalkBlocked`, because room walls set directional `WALL_*` flags and never `BLOCK_WALK`. The
  dust delay is still `2 * Chebyshev` from the centre, which fits every captured tile. Measured
  live the halves are 47 and 55 tiles (dx −4..4, dz −3..5 / −6..3) — the room's real interior
  rather than the shifted table.
- **The blast does not suppress attacks**: the angel keeps its 6-tick melee cadence while the balls
  fly, as in the capture. Movement is not locked during the blast.
- **Buff state is a temporary varbit** (`varbit.mad_angel_next_hit`, backed by a `Temp` varp) so it
  is cleared on logout and follows the "state in varbits/varps" rule; it is consumed only when a hit
  lands on the angel and never needs saving.
- **`dbrow`/`.rscm` ids are masked with `0xFFFF`** — `instance_mad_angel` is `55479`; ids at or
  above `65536` fail the row lookup at boot with `DbException$MissingColumn`.
- **Sounds**: the blast plays the captured `sound_area` ids at the impact tile — `12056` on a bounce,
  `12054` on an explosion, `radius = 20`, `size = 10` — through a numeric `WorldRepository.soundArea`
  overload (those ids have no `synth.*` gameval). The angel's hurt sound `5688` rides
  `param.defend_sound` on every mad-angel npc type, so melee and bounced hits both play it. The
  `2676` that the TSPS data labels a melee sound is the Protect-from-Melee activation sound (the
  capture also plays it when the player only flicks the prayer), so the angel has no attack sound.
  The wake music (`885`) plays through `player.midiSong`.
- **The angel is a spawn of the copy itself** (`InstanceArea.copyRegions(..., npcSpawns =
  listOf(InstanceNpc("npc.mad_angel", ...)))`) rather than a hand-spawned npc: the manager then owns
  the create/join/reset spawn, the kill timer and the cleanup, so every entry into an emptied copy
  gets a fresh dormant angel. A manual spawn queued from the enter prelude raced the entry finalize
  (the player is not yet an occupant) and could silently drop the spawn.
- **Kill count** rides `param.killcount_varp = varp.total_mad_angel_kills`; the personal best uses
  the framework kill timer, so it includes the ~3-tick waking delay.
- **Respawn** is engine-driven from `param.death_anim` + `respawnRate = 26`, so the corpse visual
  is not shown while the angel respawns.
- The fight npc is `npc.mad_angel` kept dormant through `transmog(npc.mad_angel_initial,
  Int.MAX_VALUE)`; its kneel pose is the initial type's stand animation and its `Wake` op is a
  client-side conditional on `varbit.ffg` (visible once the quest-complete value 20 is set).
  Waking restores the fight type, its hitpoints and its hunting. The fight type is kept as the
  spawn type on purpose: the server reads `maxRange`, kill-count params and respawn rate from it,
  while the initial type only supplies the dormant composition.

## Session log — 2026-10-17 (live verification)

### Verified live, in the instance

- Boot is clean: `Server ready`, no unmapped gameval / duplicate handler / duplicate drop table /
  `MissingColumn`.
- Dormant spawn: pose 1991 (`npc.mad_angel_initial`), no `Attack`, `Wake` op only.
- Wake: spotanim 4010 + seq 3321, animation reset after 3 ticks, pose 4588, actions become
  `["Attack"]`, HP bar 755/755 (the max is **755**, not the 565 the first draft assumed).
- Melee: seq 4589, cadence exactly 6 ticks, hit delay 2.
- Rotation: 3 standards → special, cycling sweep → blast → smite.
- Sweep (normal): windup 14429/14430, 4 cleaves 6 ticks apart, stay 14430 / swap 14431 / reset
  14432|14436, dust on the captured quarter. A player standing in that quarter took a hitsplat on
  every cleave tick, which exercises the transcribed shape tables and the hit test.
- Blast: seq 14443 + spotanim 4014, projectile 4015 with slope 40, endHeight 8, flight 180 client
  cycles (6 ticks), target tile 1-2 tiles from the player.
- Smite: seq 4590 + spotanim 4011.
- Enrage (`::madangelhp 350` → 350/755): enraged sweep with 4-tick cleaves, 6 cleaves and the
  enraged anims 14437-14442, in the enraged chain.
- Kill: death anim 14448; "Kill time: 4 minutes and 54 seconds - a new personal best of 4 minutes
  and 54 seconds!"; "Your Mad Angel kill count is: 1"; "New item added to your collection log:
  Granite dust"; drops on the floor (law rune x30, granite dust x27, sunstone crystal x1);
  `get_var {varp 5712}` = 1.
- Respawn: a fresh dormant angel on the same tile about 26 ticks later.
- Instance lifecycle: pew climb → copy (region 41217, base 10304,64); exit pew 62251 offers
  `Exit`/`Quick-exit` and returns the player to 2539,2216; every entry (including a rejoin of an
  emptied copy) now spawns a fresh dormant angel.

### Found and fixed this session

- **Re-entering the instance spawned no boss.** The prelude hand-spawned the angel through
  `worldQueues.add(1) { if (manager.sessionForPlayer(player) == session) spawnAngel(...) }`, which
  races `finalizeEntry` (the player only counts as an occupant once `enter()` runs). It won the
  race on the first entry and lost it on the rejoin. Replaced with `npcSpawns` on the `InstanceArea`
  (see the deviation above); re-verified create → exit → re-enter on the same server.

### Next steps (start here)

1. **Damage numbers both ways, from a *confirmed* god-off window**: angel melee 0-31 (0-5 under
   Protect from Melee), sweep cleave 0-31 (x0.5 under Protect from Melee), blast explosion 30-32
   (x0.5 under Protect from Magic), smite 0-31 (x0.25), and the bounce 18-22 onto the angel. An
   earlier pass already read a god-off window of 21 + 7 damage, so the pipeline is sound; the gap
   is reading each attack's own numbers.
2. **The two next-hit buffs.** `accurate` needs a player standing beside-but-not-behind the sweep
   quarter; `max` needs Protect from Magic high enough for the exact strike tick. Both live in
   `varbit.mad_angel_next_hit`, so they are only observable through the damage they floor — either
   collect enough samples or add a temporary debug `mes` while testing.
3. **The enrage say** (`...!`) — the screenshot missed the window — and the full enraged chain
   order (smite → blast → sweep, no standards between).
4. **Kill #2** to confirm the KC varp increments and the kill timer restarts.
5. Instance copy extent (region 10018 alone; the world beyond the cathedral reads as void) and the
   quest gate / combat achievement decisions below.

### Build state

`:content:bosses:mad-angel:compileKotlin` and `spotlessApply` are green. `:api:meta:konsistTest`
is red on pre-existing repo code (71 wildcard imports, 802 enum-casing, 56
properties-before-functions, 14 companion-last) and not green in this repo state; the module's only
violation was the `@see` rule, now fixed. The rule wants the tag value to *start with `[` and end
with `]`*: `@see [Mad_Angel/Strategies]` passes, a markdown link `[x](url)` fails.

## Session log — 2026-10-18 (reported bug fixes)

Four live defects, each read off the capture and re-measured in the instance.

### Fixed

- **Melee landed one tick early.** The ability queues its hit with `delay`, but
  `PlayerQueueProcessor` decrements a player-targeted queue on the tick it is created, because the
  npc and world queues both run before `PlayerMainProcess` in `GameCycle.preTick`. A player hit
  therefore lands `delay - 1` ticks after the animation. The capture has `attack_melee` → player
  hitsplat at +2 (326→328, 332→334, 338→340, 350→352, 356→358, 362→364); the server showed +1.
  `MELEE_HIT_DELAY` is now 3 (the reference's 2 plus the engine tick), with the reason on the
  constant. The sweep, explosion and smite strikes deliberately keep their own values: the smite's
  damage already lands on the capture's tick (`STRIKE_TICKS` drives `elapsed - 1`), and the sweep
  hits on its cleave tick.
- **The blast's explosion fired one tick before the ball landed.** `bossProjectile` builds
  `ProjAnim(startTime = delay, endTime = delay + travel)`, and the OSRS `endtime` *is* the landing
  cycle (the capture's `starttime=30 endtime=180` lands the ball and re-throws at +6), so `travel`
  must be `flight - PROJECTILE_START_CYCLE` (150 cycles = a 1-tick start plus a 5-tick flight = the
  +6 tick `land()` already runs on). With `travel = flight` the ball flew 210 cycles and touched
  down a tick after the explosion.
- **The angel idled for ~18 ticks after the blast.** `blast()` suppressed attacks for
  `3 * 6 + 6` ticks. The capture resumes melee on the landing tick (throw 344; bounce, a new throw
  and `attack_melee` all at 350, then 356 and 362), so the blast no longer suppresses anything and
  `blastAttackDelay` is gone with it.
- **Sweep and smite facing.** `Npc.lockFacingDirection` aimed at a one-tile target from the npc's
  south-west corner, so the 2x2 angel faced ~18° off the cardinal axis it was cleaving. It now
  locks a target square of the npc's own size, which is identical for size-1 npcs and exact for any
  size (the Mad Angel is the only caller). The smite no longer locks facing at all: the capture
  keeps tracking the player through the strikes (`[facing] player=…` at 376-377), not a fixed axis.
- **Cleave ground was the wrong shape.** The transcribed `ES`/`WS`/`WN`/`EN` quarter tables are
  one tile off (the reference builds them from the angel's south-west tile, not its centre) and the
  `EN` table is truncated. It was first rewritten as the capture's fixed 102-tile footprint halved
  by the bisector of facing and sword hand, which reproduces all six captured cleaves; that table
  is really the size of the capture's room, though, and this instance puts the angel about two
  tiles east, so the dust overshot on one side and stopped mid-floor on the other. Each cleave now
  floods outward from the angel's centre across the bisector half and stops at the walls (see
  **Sweep shapes** above).
- **The reflected ball's hitsplat was not the player's.** The bounce hit was queued without a
  source, so the client drew the public *tint* splat — the one used for damage you did not deal —
  and once the player was passed as source the 18-22 roll was promoted to the max-hit splat (43),
  because the roll beats an unarmed player's own max hit. The hit now carries the player as
  `source` (the reflected damage is theirs and feeds damage contribution) and a `regular_damage`
  group with `max` cleared, so the splat is the capture's plain `[hit] id=16`.

### Verified live (rebuild and restart, test/test in the instance)

Measured on the client through the Developer-Tools MCP after the rebuild.

- **Melee**: `npc_mad_angel_attack_melee (4589)` at tick 499 → player hitsplat at 501 (**+2**, as
  the capture's 326→328 and 350→352). The same pair read +1 before the change.
- **Sweep facing**: sampled during the cleaves, the angel's orientation is exactly `1536 (E)` —
  the axis towards the player — instead of the skewed `1688 (SE)` it showed before. The capture's
  per-cleave 512/1536 alternation is not the sword hand: `facePlayer()` re-reads the player's
  dominant axis after every cleave, so it flips when the *player* crosses the centre line; with a
  stationary player it stays on one axis, as here.
- **Smite facing**: with the lock gone the angel faces the player exactly as it does while
  meleeing — orientation `1688 (SE)` during `npc_mad_angel_attack_smite (4590)` and during ordinary
  melees alike — matching the capture's `[facing] player=…` updates at 376-377. The reference
  never calls `faceTile` in the smite or the blast either.
- **Blast**: `npc_mad_angel_attack_bomb_throw (14443)` at 559; the projectile records
  `startCycle=17943 endCycle=18093`, i.e. **150 cycles of flight** (was 180) starting 30 cycles in,
  so the ball touches down 180 cycles (6 ticks) after the throw — the tick `land()` runs on.
- **Post-blast cadence**: melees at 541/547/553, throw at 559, melees at **565/571/577** — the
  angel keeps its 6-tick cadence straight through the ball flight and attacks again on the landing
  tick, as the capture does (throw 344, melee 350). This was the dodged-ball path, the one that
  used to leave it idle.
- **Enraged** (`::madangelhp 340`): six cleaves at 4-tick intervals (727/731/735/739/743/747,
  matching the capture's 394-414) with the orientation exactly `1536 (E)` on every cleave, and the
  enraged smite (8543) tracking the player.

### Still open

- The `npc.mad_angel_initial` (16306) cheat path no longer disconnects: the spec is registered for
  both `npc.mad_angel` and `npc.mad_angel_initial`, and the cache gives both size 3 and 755 HP, so
  `::npc npc.mad_angel_initial` runs the whole fight after `Wake`.
- Golembane (granite hammer) is not implemented anywhere in the combat formulas; the angel only has
  the cache's golem attribute and 15% earth weakness.
- Quest version (`npc.mad_angel_quest`, 16309, 565 HP) has no encounter; the quest itself is not
  implemented.
- Combat achievements and the persisted personal best remain out of scope.

## Session log — 2026-10-20 (wiki cross-check)

Re-read <https://oldschool.runescape.wiki/w/Mad_Angel> and `/Strategies` against the quest capture
(`Fallen From Grace p2`) and the code. Cache check (osrs-mcp `CacheTool`): `npc.mad_angel` 16305
size 3 / 755 HP, `npc.mad_angel_initial` 16306 size 3 / 755 HP with a `Wake` op, `npc.mad_angel_quest`
16309 size 3 / 565 HP, `npc.mad_angel_anim_quest` 16311 size 3 / 565 HP.

Changed:

- **Enrage order** is now smite → sweep → blast (capture: say 368, enraged smite 372, sweep windup
  390, enraged blast 420; the old code ran smite → blast → sweep).
- **Enrage happens at the next attack slot**, not on the tick the HP crosses: the combat phase has
  `forceWhen(HpBelow(350/755, inclusive), enrage)` and the ability says `...!`, sets
  `nextAttackIn(4)` and `transitionTo("enraged")`. The old `entryHp` phase entry fired on any combat
  tick and used a 6-tick delay.
- **Standard special rotation starts on a random entry** (`RotationBuilder.randomStart` added to the
  DSL; `Selector.Rotation` already supported it). The capture's first special was the blast, which
  is the third entry in the sweep → blast → smite cycle.
- **Blast may target the player's current tile** (wiki change, 19 August 2026); it still never
  targets the boss's tiles and stays inside the arena.
- **Explosion damage is 30-32**, not 16-32 (`EXPLOSION_MIN_HIT` 30, wiki "30+"). The bounce stays
  18-22.
- **Spec covers `npc.mad_angel_initial`** as well, closing the disconnect from the `::npc` cheat path.

Source of the timings: capture ticks, `npc_mad_angel_attack_smite_enraged (8543)` at 372,
`sweep_right_enraged_01` at 390 with cleaves at 394/398/… and `bomb_throw (14443)` at 420, death at
423. The sweep dust delay is `2 × Chebyshev` from `coords + size/2` = (2531,2217), reconfirmed
against the captured tiles (2531..2537 in x, delay 2..12).

Not changed after review: sweep/blast/smite prayer scaling (31→5, ×0.5, ×0.25), smite strike ticks
`[5, 9, 11]` (+4, +2), 4/6 cleaves at 6/4 ticks, the fixed 18-22 bounce, blast not suppressing
attacks, and the drop table (row-for-row match with the wiki, including the 12 August medium-clue
change). **Live verification of this session's changes is still pending** (the client was on the
login screen); `BossEncounterRotationTest` covers the mechanism offline: inclusive HP threshold,
once-only enrage selection, the smite → sweep → blast rotation, and
`RotationBuilder.randomStart` cycling from its random entry.

## Session log — 2026-10-20 (dormant Wake and spawn facing)

Two live defects reported: the dormant angel had no `Wake` option, and it spawned facing south
instead of east.

### Root cause of the missing op

`npc.mad_angel_initial` (16306) is defined with `ops=[0=Wake]` **and a conditional op on slot 0
with empty text** for `varbit.ffg` (15759) in `0..19`. When the condition matches, the client
replaces the op text with the empty string, so the op disappears from the menu. A player who has
not completed Fallen From Grace has `ffg=0`, which is why `Wake` was hidden; `list_npcs` reads the
composition's `actions` and therefore kept reporting `["Wake"]`, which made the menu look broken.

The official capture settles the intended state: `ffg` climbs through the quest and reaches **20**
at the end (`Fallen From Grace p2`, varbit 19→20), i.e. the repeatable boss is post-quest content.
The capture also adds `npc.mad_angel_initial` at (2532,2215) with `spawnangle=east` and no transmog,
then transforms it to the anim/quest types.

### Changed

- **`varbit.ffg` is set to 20 on entry** (`MadAngelInstance.assumeFallenFromGraceComplete`, in the
  enter prelude, guarded so it never lowers a higher value). This is the post-quest state the
  repeatable encounter is gated behind; with `quest-requirements.mode: assume-completed` this
  matches the rest of the server, and it is the only way to make the client's conditional `Wake`
  op appear. The value persists on the player, which is consistent with having unlocked the boss.
- **The instance still spawns `npc.mad_angel`** and keeps the dormant state as a transmog to
  `npc.mad_angel_initial`; spawning the initial type directly was tried first, but that type falls
  back to `Wander` mode and a `maxRange` of 7 instead of the fight type's 40, so the dormant angel
  walked around and an awake one would drop its chase when the player crossed the room.
- **Stationary while dormant**: `dormant()` now sets `npc.mode = NpcMode.None` explicitly (the fight
  type's `defaultMode` already was `None`), so the angel waits on its tile until woken even if the
  type changes.
- **East facing**: `dormant()` sets `npc.respawnDir = Direction.East` and
  `infoProtocol.setFaceAngle(Direction.East.angle, instant = true)` on create and respawn, matching
  the capture's `spawnangle=east`. It still zeroes hunt range and resets hitpoints so the angel does
  not aggro while asleep.
- **Wake** transmogs back to `npc.mad_angel`, which is where `Attack` comes from; the spawn
  animation plays while the fight type is already applied. The capture's intermediate
  `mad_angel_anim` type is not used because it carries the quest stat block (270 combat / 565 HP),
  not the boss's 588/755.

### Verification

- `:content:bosses:mad-angel:compileKotlin`, `:api:bosses:test` and the module tests are green;
  server restarted, `Server ready`, spec validation passes.
- Live: entering the instance set `varbit.ffg` to 20, the dormant angel kept its pose 1991 and the
  client's menu showed `Wake` (it had only `Examine` before), and the facing read east. The
  following rebuild keeps that behaviour and pins the dormant mode to `None`.

## MCP harness notes (these cost real time)

- **Chat is write-only.** `get_chat_history` and `get_timeline {channels:["chat"]}` both return 0
  events; game messages are readable only from screenshots. `get_effect_history`/`get_timeline`
  also do not record **map** spotanims (sweep dust 2184, blast shadow 1448, explosion 4016,
  impact 4013) — only actor-attached graphics — so arena VFX have to be checked visually.
- **`get_combat_history` labels hitsplats by their target.** `actorType: "player"` = hits the angel
  landed on the player; `actorType: "npc"` = the player's hits on the angel. Reading the wrong side
  is the easy way to conclude "the boss never lands" from a healthy fight. `hitsplatType` 12 is the
  miss/block splat, 16 is a landed hit.
- **`get_client_state` lags teleports.** For several seconds after an instance entry it still
  reported the pre-entry region/coords while `list_objects`/`list_npcs` already showed the copy.
  Trust the scene listings (or `::mypos` in the chat) over `worldX/worldY` right after a jump.
- **`walk_to`'s projection is unreliable.** It can pick a screen point on a blocked tile (broken
  pews, benches) and the player simply never moves; retry neighbouring tiles until the position
  changes. `interact_object`/`interact_npc` answer `No menu entry matching 'x'. Current menu:
  [Cancel]` when the target is out of reach, so walk adjacent first — object reach is measured to
  the object's own tiles, not its origin.
- **Instance entry takes roughly 10-20 s** (click → region copy → fade → telejump). Block on it
  with `wait_for {condition:"at_tile", …}` on the destination tile instead of sleeping.
- **`::` commands apply late.** `type_chat` types the text but the Enter press is unreliable: the
  command can sit unexecuted in the chatbox and an immediate resend can concatenate onto it
  (`::god` landing as `test: god` was this). Send, screenshot, verify the chat reply, and only then
  trust the window. `::tele`, `::god` and `::madangelhp` have all been seen to apply one call late.
- Prefer `get_var {type:"varp", id:…}` over `get_var_history`: the latter drops events outside its
  window, which silently hid the kill-count change.

## Framework notes worth keeping (instances + bosses)

- `InstanceScript.defaultInstanceEntry` → `privateOnlyInstanceEntry`: an owner with an existing
  session is **joined**, never recreated, so a module that hand-spawns its boss has to respawn it on
  every fresh run (`Result.isFreshRun()` = `Created`, or `Joined` into an emptied session).
  `join()` already calls `resetNpcs`, which deletes the previous run's npcs and re-spawns
  `placement.npcSpawns`; declaring the boss there is the pattern `vardorvis`, `kbd`, `whisperer`,
  `sucellus`, `scurrius`, `zilyana`, `graardor` and `kril` use, and it gets `startBossKillTimer`
  and cleanup for free.
- `withInstanceEnterTransition` sends the fade, calls `enter()` **synchronously** (telejump +
  `manager.finalizeEntry`, which is what registers the occupant and sets `currentInstanceId`), then
  delays ~1 tick for the minimap. Anything queued through `worldQueues` before `enter()` returns
  runs before the player counts as inside.
- A dormant boss pose can be a `transmog` (`npc.mad_angel` → `npc.mad_angel_initial`,
  `Int.MAX_VALUE`) re-applied from the `NpcStateEvents.Create`/`Respawn` hooks. `transmog` never
  changes `type`, so the boss spec still matches the spawned npc and `::npc npc.mad_angel_initial`
  exercises the entire fight outside an instance.
- Special attack suppression is per-special through `BossDeps.suppressAttacks(npc, ticks)`; the DSL
  exposes no "blocks standard attacks" flag, which is the documented deviation for the blast.
- `InstanceArea.copyRegions(centerRegionId = 10018)` with the default `gridSize = 1` copies exactly
  one region; `gridSize` (or an explicit `regionIds` list) is the lever for the reference's larger
  arena copy.
