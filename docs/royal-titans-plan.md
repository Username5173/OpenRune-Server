# Royal Titans — implementation plan

Target: the Royal Titans duo encounter (Branda the Fire Queen + Eldric the Ice King,
<https://oldschool.runescape.wiki/w/Royal_Titans>), implemented as a new instanced boss in
OpenRune, with the fight mechanics, the three-way loot choice, the scoreboard and the
kill-count/duration messaging reproduced from the real game.

Sources, in order of authority where they agree:

1. `C:\Public\rsprox logs\giants_boss_1.txt` — clean single kill, full phase 1 →
   transition → phase 2 → death → loot → respawn.
2. `C:\Public\rsprox logs\giants_boss_2.txt` — longer session, second kill, repeat-loot
   refusal, loot-tracker scripts, corpse deletion and re-add.
3. The wiki article and `/Strategies` (phase thresholds, damage caps, drop rates,
   contribution rules).
4. `osrs-dumps/dump.npc` (stats, ops, sizes) and the cache (`gamevals.dat`,
   `.data/raw-cache/server/*.toml`) for ids.

The capture is authoritative for timing, the wiki for numbers the capture cannot show
(drop rates, contribution scaling).

## Testing budget

Live-client time is the expensive resource, so this plan front-loads everything that can be
proven offline and defers all client work into **one** batched session at the end.

Offline before any client session:

- `gradlew :or-cache:mergePluginGamevals :or-cache:buildCache` then `gradlew run` — boots,
  `Server ready in …`, and the boot-time validators are the real test for unmapped gamevals,
  duplicate handlers, duplicate drop tables, invalid boss specs and `MissingColumn`.
- `gradlew :content:bosses:royal-titans:test` for the pure maths: contribution shares,
  quantity scaling, phase-2 sync, blast safe-tile selection, wall coverage geometry.
- `gradlew :api:meta:konsistTest` and `gradlew spotlessApply`.

Only then the single in-game pass in the last section.

## What already exists (verified)

| Asset | Status |
|---|---|
| `npc.rt_fire_queen` 12596, `npc.rt_ice_king` 14147 | In cache: 600 HP, size 3, `Attack` on op2, combat 350, attack 300 / def 100 / str 250 / range 150 / magic 100 |
| `npc.rt_fire_queen_inactive` 14148, `npc.rt_ice_king_inactive` 14149 | In cache; the post-kill `Loot`/`Sacrifice`/`Take pages` carriers |
| `npc.rt_summon_elemental_fire` 14150, `…_ice` 14151 | In cache, 40 HP, size 1 |
| `npc.rs_loc_attack_fire_hitbox` 14152, `…_ice_hitbox` 14153 | In cache, size 1, `op1 = Douse` / `op1 = Melt`, vislevel 0, no minimap dot |
| `npc.royal_titans_area_attack_timer_handler` 14154 | In cache, size 1, invisible; carries the blast headbar |
| `loc.royal_titans_entrance` 55986 @ 2948,9573 | In cache |
| `loc.royal_titans_exit` / `cavewall_shortcut_royal_titans_east` / `_west` | 55987 / 55988 / 55989 |
| `loc.royal_titans_scoreboard` 56004 @ 2954,9577 | In cache |
| `interface.royal_titans_scoreboard` 909 | In cache with `pkc/pd/pbt/gkc/gd/gbt_content` components |
| Wall/elemental/floor visuals (`vfx_*_loc01_*`, `fire_giant_branda_queen_*_floor01/02`, `ice_giant_eldric_king_*_floor01/02`) | In cache, 55993–56003 |
| Sequences (spawn, melee, summon, aoe, teleport, inactive, death) and spotanims | In cache, see Appendix A |
| `varp.total_royal_titan_kills` 4648 | Official symbol; the client already sends the KC message from it |
| `dbrow.collection_log_category_royal_titans` 60083 | Collection log category already in the cache |
| Elements: `param.elemental_weakness_*` on `rt_*` npcs | Already in `.data/raw-cache/server/npcs.toml` (50% fire, 50% ice, 100% elementals) |
| Pet `obj.rtbrandapet` 62005 → `npc.rtbranda_pet` | Already wired in `content/other/pets` |
| Existing RT drop tables | `content/drops/.../BrandaTheFireQueenDropTable.kt` + `EldricTheIceKingDropTable.kt` — rows are right, shape is wrong (see **Loot design**) |

Missing: the Royal Titans module itself, the two inactive states, the walls/elementals/blast
behaviour, the loot choice, the scoreboard wiring, Combat Achievement tasks, and any
personal best / deaths tracking.

## Target behaviour

### Entry

- `loc.royal_titans_entrance` op1 → confirmation `mesbox` (interface 229), exact text:
  *"You are about to begin an encounter with the Royal Titans. Dying during this encounter
  will not be considered a safe death. To leave, you'll need to use
  `<col=ff0000>`'Quick-escape'`</col>` via the tunnel or teleport out. Are you sure you wish to
  proceed?"* → instance entry.
- `royal_titans_enter_warning` is a **dbrow** (16089 in the cache, with no gameval symbol in
  `.data/gamevals/dbrow.rscm`), not a varbit: there is no "don't ask again", so the box shows
  on every entry. The text is inlined as a string constant, not looked up by dbrow.
- The tunnel exit loc inside the copy offers `Quick-escape` (same shape as Brutus's
  `leaveField`), teleporting out without the confirmation. The *copied* tunnel room sits in the
  neighbouring map square, roughly forty tiles east of the arena and not connected to it, so its
  exit object is unreachable from the corridor; `RoyalTitansInstance.placeQuickEscape` therefore
  places a second `loc.royal_titans_exit` (`shape 10`, north) one tile south of `ENTER_COORD`,
  right where the tunnel mouth is, and the op flow registered for this instance key picks it up.
- The mouth is usable from **anywhere in the arena** and takes two clicks: the first prints what the
  tunnel is for, the second leaves. Loc ops require adjacency (`LocInteractions.interact` walks the
  player when `isWithinOpRange` fails), which meant a fire/ice row between the player and the mouth
  produced "You can't reach that!" and locked the escape behind the wall mechanic. `LocInteractions`
  now keeps a per-loc-type AP range override and the instance registers
  `loc.royal_titans_exit` at 40 tiles, so `onApLoc1`/`onApLoc2` fire from anywhere in the room with
  no pathing at all. Op1 is `Inspect` and op2 is `Quick-escape`, so both are hooked.
- Instantiated, 1–2 players; music 469 "In the Pits" on entry.
- Multiway: the arena is not inside any `area.multiway` polygon in the cache data, so the
  instance was single-way. `.data/raw-cache/map/area/royal_titans_multiway.toml` adds the
  arena box (x 2898–2924, z 9552–9582) as `area.multiway`; `AreaChecker` normalizes instance
  coords back to the source region, so the same polygon covers the relocated copy and
  `MultiwayAreaScript` flips `varbit.multiway_indicator` inside it.

### Phase 1 (both titans alive, above 35%)

- Both titans patrol their own strip instead of standing on their spawn tiles: Branda
  (2906,9566) covers x 2904–2906, Eldric (2914,9566) covers x 2914–2916, both roaming
  z 9561–9571 (capture: 1 tile per tick, so `Walk` speed). They alternate a stand-and-attack
  window with a walk to the other edge of the strip and never leave it, so they never chase a
  player out of the arena. The walk has to run with `ignoreCombatInteractions` set: while the
  `ApPlayer2` interaction is live it aborts the npc's route every cycle (and re-applies its
  facing), so the titan would only ever turn.
- Movement restriction: `Normal` while engaged and `MoveRestrict.Blocked` while walking - see
  the arena bullet under *Framework notes* for why the ledges need the blocked-tiles strategy
  instead of a collision hack.
- They never face the player: `npc.lockFacing` pins the angle to a tile well outside their own
  half (east for Branda, west for Eldric), which makes `facePlayer` a no-op for the live
  interaction, and the lock is re-applied whenever it is cleared (phase transitions call
  `clearFacingLock`).
- **Melee / stomp** on a 7-tick cadence per titan (capture: 101, 108, 115, 122, 129). The
  attack is a 5-deep × 3-wide strip in **front of the titan**, i.e. across the arena: the
  titans stand on either side of the corridor and face it, so Branda always slams east
  (`spawnX+3..+7`, capture x 2909–2913) and Eldric always slams west (`spawnX-5..-1`). The
  strip is not aimed at the player - aiming it at whichever axis the player happened to be on
  used to send it out of the arena whenever the player stood well north or south of the titan.
  `vfx_ice_giant_eldric_king_melee01_proj01` (3211, shared by both titans) plays on every tile,
  rolling outwards from the row touching the titan (delay 0) to the far row (delay 8, two
  ticks per row). The whole strip is hit one tick after the swipe (the ground effect is
  cosmetic), max 26, blocked by Protect from Melee. Offered when the target is within 12 of the
  titan's centre.
- **Attack timing after a walk** (from the capture's per-tick sections, not wall clock): the
  titan never attacks on the tick its last step lands. Branda's last step is tick 99, her melee
  sequence is tick 101; another walk ends at 169, melee at 170; a walk ends at 156, wall cast at
  158. So the first attack after movement is **1-2 ticks** later, and the facing/pose settles
  around the same time. The patrol therefore ends a walk, waits `SETTLE_TICKS = 2`, re-applies
  the facing lock and only then re-establishes the player interaction - which is what lets the
  first attack land two ticks after the stop instead of on the stopping tick.
- **Dwell at each end of the strip.** In the capture a titan that has stepped to the *front*
  stands and slams four or five times (28-43 ticks between walks) while one that has stepped
  *back* casts once and moves again (12-17 ticks). `FRONT_DWELL = 30` / `BACK_DWELL = 14`
  reproduce that rhythm; `MAX_STAND_TICKS = 60` is only the stuck-boss fallback.
- **Fire wall** (Branda) — 5 hitbox npcs + `vfx_branda_queen_loc01_idle01` visuals
  (`shape 10`, north rotation) on a randomly chosen arena row (`spawnZ-5 .. spawnZ+7`, the
  whole observed 9561–9573 range), columns `spawnX+3..+7` (capture x 2909–2913). Cast anim
  `*_attack_summon01` plus the `*_loc01_spawn01` telegraph on all five tiles; the wall
  materialises 4 ticks later. Standing on a target tile deals up to 12 and the wall still
  forms. Each tile despawns naturally after 38 ticks (`*_loc01_despawn01`), and a new wall
  clears any wall already on its row first. The tile teardown is scheduled by the wall itself
  (npc + decorative loc deleted in the same step) rather than trusting the npc's lifetime
  delete event; the Delete path stays as the douse route. The hitbox npcs are pinned with
  `NpcMode.None` (their type defaults to `Wander`, which used to walk them off their tile and
  strand the visual) and each wall records the tile it formed on, so teardown never depends on
  `npc.coords`.
- Walls are only ever removed by a spell **hitting** them. Casting a water spell (fire wall) or
  fire spell (ice wall) at any tile runs a full visual cast - cast animation, launch spotanim,
  projectile, impact spotanim and cast sound, runes consumed and cast xp granted - and the row
  is torn down on the tick the projectile lands, never when the click is received. No hit is
  queued: the wall is an obstacle, not a target. Spell hooks are matched per spellbook component
  at startup through `MagicSpellRegistry`; the hook is an **AP** trigger (`NpcTEvents.Ap`), not
  an op, because the client sends the spell as an npc-T interaction and the AP range is 10
  tiles, so the cast resolves from where the player stands instead of walking adjacent. An op
  hook never fired at range because the default `PvNCombatScript` AP handler (component-only)
  took the interaction first and rejected the non-attackable hitbox npc. Left-clicking a wall
  douses it with the player's current **autocast** spell when that spell matches the element
  (`varbit.autocast_set` + `varbit.autocast_spell` through `MagicSpellRegistry`); otherwise the
  op only prints which element to cast. That click is hooked as `NpcEvents.Ap1`
  (`onApNpc1`), not as an op: an op interaction requires the player to be adjacent
  (`Interactions.earlyStep` only triggers `TriggerScriptOp` on `validOpLine`), so a left-click
  walked the character into the wall before casting. The AP path resolves at
  `InteractionNpcT`'s range instead, so the click casts from where the player stands.
- **Icicles** (Eldric) — same rows and shape, ice columns `spawnX-5..-1`
  (`vfx_eldric_king_loc01_idle01`, `rs_loc_attack_ice_hitbox`).
- **Elementals** — `*_attack_summon02`, 3 spawn 1 tick later in a vertical 3-tile line: a
  random column in the titan's wall columns (fire `+3..+7`, ice `-5..-1`) and a random start
  row in `spawnZ-5..spawnZ+5` (capture: fire at (2913, 9571–9573), ice at (2910, 9561–9563)).
  They melee on their own ~4-tick cadence, self-destruct for 8–10 in a 3×3 after 30–37 ticks,
  die instantly to the opposing element, and **do not count for loot contribution**.

### Phase transition

- Trigger: **either** titan at ≤210 HP (35% of 600). Both titans then:
  `teleport01` (spotanim 3216) → +2 ticks both `spawn01` (spotanim 3218 Branda / 3221 Eldric)
  → both stationary on the arena centre.
- The HP bar keeps following whichever titan was last attacked (`boss_hp_bar_mode = 1`
  rebinds it on each new attack, which is exactly what the capture shows).

### Phase 2

- **Elemental blast**, roughly every 22–36 ticks per titan pair:
  `attack_aoe01` (charge, +8 ticks) → `attack_aoe02` (release, +6 ticks) → three
  arena-wide waves 2 ticks apart (`vfx_*_attack_aoe_proj01` spotanims 3209/3210).
- Exactly **one safe tile**, random inside the central 5×3 (x 2909–2913, z 9566–9568),
  marked by `loc.vfx_giant_attack_aoe_protection01` and by
  `npc.royal_titans_area_attack_timer_handler` spawning on it with headbar 78 (7 ticks).
- Out of the safe tile: up to 30 damage. Standing on the safe tile: no damage.
- A player standing where a titan lands is knocked back 15–30.
- Melee continues on the 7-tick cadence between blast cycles.

### Survivor phase

- When one titan dies the other becomes enraged: its stomp covers the **whole arena**,
  pierces protection prayers (~10), and the blast's safe tile is placed **farthest** from it.

### Death, loot and respawn

- Death → both titans transmog to their `*_inactive` type and play `*_inactive01`; the
  following lines fire on the death tick:
  - `Your Royal Titans kill count is: <col=ff0000>N</col>.` — the capture shows this is
    client-generated from `varp.total_royal_titan_kills` changing, so the coordinator writes
    the varp once and only sends the line itself if the client does not (see open decision 5).
  - `Fight duration: <col=ff0000>M:SS</col> (new personal best)` or
    `Fight duration: <col=ff0000>M:SS</col>. Personal best: M:SS`
  - `You are eligible to take loot from either Branda the Fire Queen or Eldric the Ice King.`
- Each inactive titan has exactly three ops (`opflags = 0b01101` in the capture):
  `Loot` (op1), `Sacrifice` (op3), `Take pages` (op4).
- The **first** loot interaction by any occupant prints
  `<col=ef1020>The Royal Titans will reinvigorate themselves in 18 seconds.</col>` and starts
  the 18-second respawn clock. Interacting again with an already-looted titan prints
  `You do not feel worthy enough to loot <titan>.`
- Loot appears as **personal ground items at the looter's tile** (`obj_add_specific … ownership=self`),
  each accompanied by `<col=ef1020>Valuable drop: N x Item (V coins)</col>` and a loot-tracker
  entry (scripts 7191/7198/7192 with `[npc, eventId, count]`).
- ~20 ticks later both corpses play `*_death01` with the death floor locs, are deleted, and
  fresh `rt_fire_queen` / `rt_ice_king` are added on the spawn tiles with the HP bar reset.

### Rewards and contribution

- **Loot**: two primary rolls per titan table (see Appendix B), plus tertiary rolls.
- **Sacrifice**: no primary or unique roll; pet chance **doubled**; clue rolls still happen.
- **Take pages**: 10–19 desiccated pages + tertiary rolls only.
- Contribution is each player's share of the **combined** damage to both titans (elementals
  and walls excluded), as a whole percent, minimum 1 for anyone who dealt 1–11.
  - Quantities: `max(1, ceil(base * pct / 100))`.
  - Uniques (twinflame staff pieces 1/75, prayer scrolls 1/75, giantsoul amulet 1/16) scale
    linearly by percent.
  - Pet (1/3000, 1/1500 sacrificed) and clues (hard 1/25 → 1/23 with CA, elite 1/100 →
    1/95 with CA) do **not** scale.

## Architecture mapping

Module `content/bosses/royal-titans/`, shaped on `content/bosses/mad-angel` (spec + instance
+ external handlers) and `content/bosses/brutus` (entry warning, Quick-escape, HP bar,
scoreboard, world stats).

| Concern | Capture / wiki | OpenRune |
|---|---|---|
| Two bosses | separate npcs, separate HP | two `BossSpec`s registered in one `BossPluginScript`, one per npc type |
| Shared phase state | either titan at 35% flips both | `TriggerSpec(HpBelow(0.35), External("rt.phase_two"))` on both specs; the handler transitions both encounters via `EncounterRegistry.of(...)` |
| Melee | 7-tick cadence | `stats(attackRate = 7)`, `ability("melee")` |
| Stomp | 3×5 in front, 26 max, prayer-blocked | `TileAoE`/`tileSet` + `hitRules` cap under `TargetPraying(MELEE)` |
| Fire wall / icicles | 5 hitbox npcs + locs, 4-tick formation | `Effect.Summon` for the hitbox npcs + `SpawnLoc` visuals, ext handler for the douse/melt lifecycle |
| Elementals | 3 summons, explode, buff the killer | `Effect.Summon`, `TimerSpec` for the self-destruct, `BossCombat.register(…, onKill)` for the accuracy/attack-delay buff |
| Elemental blast | charge → release → 3 waves, one safe tile | external handler driving `MapSpotanim` waves + `TileAoE`, `npc.royal_titans_area_attack_timer_handler` as the safe-tile marker |
| Inactive titans | same npc transmogged | `Effect.Transmog(npc.rt_*_inactive, Int.MAX_VALUE)` + `*_inactive01` seq |
| Loot choice | 3 ops on the inactive titan | `onOpNpc1/3/4` on the inactive types, `RoyalTitansLoot` rolls the tables |
| Drop tables | two 55/56-weight primary tables | plain (unregistered) tables in the boss module; `NpcDeathDropHook.suppressesDropTable` blocks the auto-roll |
| HP HUD | one bar that switches titan | `param.boss_hp_bar_mode = 1` (ON_ATTACK) on both titan types — no custom HUD code |
| Kill count | one message per clear | coordinator increments `varp.total_royal_titan_kills` once when both are dead, `killcount_notify` off |
| Duration / PB | one message per clear | `recordsKillTime = false` on the instance; coordinator formats via `formatInstanceKillTime` and stores `varp.royal_titans_best_time` |
| Scoreboard | interface 909 at the tunnel | `onOpLoc1(loc.royal_titans_scoreboard)` filling the six components, plus a `boss_world_stats` row like Brutus's |
| Combat achievements | 11 tasks | blocked, no CA registry in this repo |

## Module layout

```text
content/bosses/royal-titans/
├── build.gradle.kts
├── src/main/kotlin/org/rsmod/content/bosses/royaltitans/
│   ├── RoyalTitans.kt              # both BossSpecs + registration
│   ├── RoyalTitansScript.kt        # ops: entrance, exit, inactive loot ops, scoreboard
│   ├── RoyalTitansInstance.kt      # InstanceScript: copy, spawns, warning, respawn clock
│   ├── RoyalTitansCoordinator.kt   # @Singleton: phase sync, per-run state, kill/duration
│   ├── RoyalTitansWalls.kt         # wall spawn, tile blocking, douse/melt, despawn
│   ├── RoyalTitansElementals.kt    # summon, self-destruct, per-kill buffs
│   ├── RoyalTitansBlast.kt         # charge/release/waves, safe tile, survivorship variant
│   ├── RoyalTitansLoot.kt          # contribution, loot/sacrifice/pages, ground items
│   ├── RoyalTitansTables.kt        # per-part drop tables (unregistered)
│   └── RoyalTitansScoreboard.kt    # interface 909 + world stats
├── src/main/resources/gamevals.toml
└── src/test/kotlin/org/rsmod/content/bosses/royaltitans/
    ├── RoyalTitansContributionTest.kt
    ├── RoyalTitansLootTest.kt
    └── RoyalTitansPhaseSyncTest.kt
```

`settings.gradle.kts` auto-includes any `content/**/build.gradle.kts` and `server:shared`
exposes it through `findPlugins()`, so no registration file is needed.

## Data and config changes

1. **`src/main/resources/gamevals.toml`**

   ```toml
   [gamevals.dbrow]
   instance_royal_titans = 63890

   [gamevals.varp]
   royal_titans_deaths = 64412
   royal_titans_best_time = 64413
   ```

   **The dbrow id must be below 65536 and must not collide with an existing dbrow
   gameval** (a `dbtable` row id is 16-bit; an id ≥ 65536 is masked and the row lookup then
   fails at boot with `DbException$MissingColumn`, which is the caveat
   `dbrow.instance_mad_angel = 55479` documents). 63890–63892 are the only free slots next to
   the other instance rows (`instance_vardorvis = 63893`, `instance_graardor = 64594`); the
   block 64602–65535 is fully occupied. The two varp ids are free and neighbour Brutus's
   64410 / 64411.

2. **`.data/raw-cache/server/varp.toml`** — `varp.royal_titans_deaths` and
   `varp.royal_titans_best_time`, both `isServerOnly = true`, `scope = "Perm"`,
   `transmit = "Never"`, exactly as `brutus_deaths` / `brutus_best_time`.

3. **`or-cache/.../InstanceSettingsTable.kt`** — one row:

   ```kotlin
   row("dbrow.instance_royal_titans") {
       column(KEY, "royal_titans")
       // Enter coord is the capture's post-teleport tile (2911, 9573); exit is the tile the
       // player stood on before clicking the entrance loc at (2948, 9573).
       columnCoord(EXIT_COORD, CoordGrid(2949, 9574, 0))
       columnCoord(ENTER_COORD, CoordGrid(2911, 9573, 0))
       column(FEE, 0)
       column(MAX_PLAYERS, 2)
       column(TIME_LIMIT_MINUTES, 0)
       column(GRACE_MINUTES, 10)
       columnRSCM(BOSS_NPC, "npc.rt_fire_queen", "npc.rt_ice_king")
       column(BOSS_NAME, "Royal Titans")
       column(RECOMMENDED_COMBAT, 80, 126)
       column(TEAM_SIZE, 2)
       column(LOOT_MULTIPLIER, "x1.0")
       column(
           DESCRIPTION,
           "Branda the Fire Queen and Eldric the Ice King, twin rulers of the Giants' Plateau.",
       )
       columnRSCM(ENTER_OBJECT, "loc.royal_titans_entrance")
       columnRSCM(EXIT_OBJECT, "loc.royal_titans_exit")
   }
   ```

   `MAX_PLAYERS = 2` is the hard cap the wiki states; the instance still needs the
   `team_size` column for the group panel.

4. **`.data/raw-cache/server/npcs.toml`** — on `rt_fire_queen`, `rt_ice_king` and both
   inactive types add `"param.boss_hp_bar_mode" = 1`. Do **not** set `param.killcount_varp`:
   the coordinator owns the single increment and the message. `killcount_notify = false` on
   all four keeps the client quiet if anything else ever attaches the param.

5. **Instance copy** — `InstanceArea.copyRegions(centerRegionId = 11669, gridSize = 3)`.
   Region 11669 is the arena (x 2880–2943 / z 9536–9599); `gridSize = 3` also copies 11925
   (x 2944+, the tunnel/scoreboard) and its neighbours, the same lever Brutus uses. The
   copied scoreboard at 2954,9577 is fine — it reads global stats anyway.

6. **Drop tables** — `content/drops/.../BrandaTheFireQueenDropTable.kt` and
   `EldricTheIceKingDropTable.kt` are deleted and their rows moved into `RoyalTitansTables.kt`
   (unregistered), with the three fixes from **Loot design**. Leaving them registered would
   double-roll on every kill.

## Loot design

`RSDropTable` hides its part tables (`guaranteed`, `preRoll`, `mainTable`, `tertiaries` are
private) and repeats `mainTable` `mainRolls` times, so a whole-table roll cannot express
"primary rolls only" (Loot), "pet only" (Sacrifice) and "pages + tertiary" (Take pages).
The two existing tables also carry three concrete errors for this purpose: `mainRolls`
defaults to **1** where the wiki says **2** primary rolls, `obj.desiccated_page` sits in
`guaranteed` (so it rolls on every kill instead of only on *Take pages*), and the clue rates
are the CA-boosted 1/23 and 1/95 although no CA infrastructure exists (base is 1/25 and
1/100). Their **rows** are otherwise correct and reusable.

Instead:

- `RoyalTitansTables` holds per titan, per part, as **unregistered** tables (no
  `@RegisterDropTable`), lifting the row data from the retiring tables:
  `main` (weights 55 / 56, including the 6–24 page row), `uniques` (twinflame piece 1/75,
  prayer scroll 1/75, giantsoul amulet 1/16), `pet` (1/3000), `clues` (hard 1/25, elite
  1/100), `pages` (10–19).
- `RoyalTitansLoot` rolls only the parts the chosen op needs with
  `table.roll(player, ArgMap(...)).flatten()` and scales each result's
  `rollCount(random)` by the player's contribution percent:
  - Loot → `main` rolled twice + `uniques` + `clues`;
  - Sacrifice → `pet` at 1/1500 + `clues`;
  - Take pages → `pages` + `clues`.
- `RoyalTitansLoot` also implements `NpcDeathDropHook` and returns
  `suppressesDropTable = true` for both titan types, so `NpcDropTableKillHook` never
  auto-rolls. It records `context.lootTrackerEventId` per kill so the click-time
  `ClientScripts.lootTrackerAddLoot(player, npcId, eventId, objId, count)` entries group
  under the same kill.
- Items are spawned with `objRepo.add(obj, player.coords, duration, player, count)` —
  `ownership = self` in the capture — and each spawn sends the
  `<col=ef1020>Valuable drop: …</col>` line and `CollectionLog.grant`.
- Prayer scroll gating ("no longer dropped after reading") cannot be expressed: there is no
  prayer-unlock module. Ship it unconditionally with a `TODO` referencing the unlock check,
  rather than inventing a varp.

## Phases

**Phase 1 — skeleton, bootable, no mechanics**

1. Module + `build.gradle.kts` (deps: `api.bosses`, `api.instances`, `api.bossHpBarPlugin`,
   `api.combat.commons`, `api.npc`, `api.player`, `api.pluginCommons`, `api.repo`,
   `api.script`, `api.drop-table`).
2. `gamevals.toml`, `varp.toml`, `InstanceSettingsTable` row, `npcs.toml` params.
3. `RoyalTitansInstance` (copy 3×3, `npcSpawns` with both titans, `recordsKillTime = false`,
   `bestTimeVarp = varp.royal_titans_best_time`).
4. `RoyalTitans` with two specs: `stats(attackRate = 7)`, one `melee` ability each.
5. `mergePluginGamevals` → `buildCache` → `run`; confirm `Server ready` and that both titans
   spawn, are attackable and hit back.

**Phase 2 — phase 1 abilities (done, awaiting live confirmation)**

6. Stomp replaces the single-target melee ability: a 5-deep × 3-wide strip projected from
   the titan toward its target, `vfx_ice_giant_eldric_king_melee01_proj01` on each tile
   (delay `depth * 2`, near row first), and one 0–26 melee `tileAoE` per row two ticks apart.
   Offered by `TargetWithin(8)`; the target must still be standing in a row when it resolves.
7. `RoyalTitansWalls`: summon anim, 4-tick formation, 5 hitbox npcs + 5 decorative locs on a
   random arena row (`spawnZ-4 .. spawnZ+6`, columns `spawnX+3..+7` fire, `spawnX-5..-1` ice),
   ≤12 damage to players on the forming tile (the wall still forms), 38-tick lifetime with a
   `*_loc01_despawn01` per tile, a new wall clearing its row first, and the douse path
   (`onOpNpc1` or a water/fire spell cast on a tile removes the whole row). The hitbox npcs run
   `NpcMode.None` and the wall stores its formed tile, so teardown cannot miss the visual. An
   opposing wall forming on a tile destroys the elementals standing there.
8. `RoyalTitansElementals`: 1-tick summon of 3 in a vertical 3-tile line (random column in the
   titan's wall columns, random start row in `spawnZ-5..spawnZ+5`), melee the player targeted
   by the cast, 30–37-tick self-destruct (8–10 typeless in a 3×3, then a 2-tick fade), instant
   death to the opposing wall, and −1 tick of attack delay to the player who kills one. Ranged
   accuracy ×6 while out of melee range is still deferred (needs the `PvNCombat` accuracy hook).

**Phase 3 — transition and phase 2**

9. `HpBelow(0.35)` trigger on each spec → `External("rt.phase_two")`; the shared handler
   transitions **both** encounters (the triggered one and
   `EncounterRegistry.of(sibling)`) to `phase_two`, so whichever titan crosses first flips
   the pair.
10. `RoyalTitansBlast`: teleport01 → spawn01 (both) → charge → release → 3 waves;
    safe tile from the central 5×3; `vfx_giant_attack_aoe_protection01` +
    `royal_titans_area_attack_timer_handler` with headbar 78; up to 30 outside the tile;
    knockback 15–30 when a titan lands on a player.
11. Survivor variant: whole-arena stomp, prayer-piercing ~10, farthest safe tile.

**Phase 4 — death, loot, respawn**

12. Death → transmog both to `*_inactive` + `*_inactive01`; coordinator writes the kill-count
    varp and sends the duration/PB and eligibility lines once.
13. Inactive ops `Loot` / `Sacrifice` / `Take pages` with the contribution-scaled rolls,
    the 18-second clock, the "not worthy" refusal, valuable-drop lines and loot-tracker
    entries.
14. Corpse cleanup (~20 ticks after the first interaction), death floor locs, fresh titans on
    the spawn tiles, HP bar reset.

**Phase 5 — entry, scoreboard, persistence**

15. Entrance `mesbox` on every entry, Quick-escape on the tunnel exit, music 469.
16. `RoyalTitansScoreboard`: `onOpLoc1(loc.royal_titans_scoreboard)` → interface 909 with
    personal KC (`varp.total_royal_titan_kills`), personal deaths, personal best, and the
    world equivalents from a `boss_world_stats` row, mirroring `BrutusScoreboard` /
    `BrutusWorldStats`. Deaths increment from the instance death hook.
17. Collection log already exists via `dbrow.collection_log_category_royal_titans` — no code,
    only verifying the drop ids are the ones the category references.

**Phase 6 — QA**

18. Unit tests: contribution maths (1–11 damage floor, whole-percent rounding, 2-player
    split, elementals excluded), loot scaling and dupe safety (roll → ground item count,
    pages 10–19, sacrifice has no primary), phase sync (either titan crossing 210 flips both,
    once only), safe-tile selection (excluding titan tiles, farthest under survivorship).
19. `spotlessApply`, `:api:meta:konsistTest`, `test`, `buildCache`, one boot.
20. The single batched live pass below.

## Open decisions

1. ~~**Wall dousing interaction path.**~~ **Resolved:** the hitbox npcs carry only `op1`
   (`Douse`/`Melt`), but the client sends a spell as an npc-T interaction (AP), not an op. The
   wall registers `NpcTEvents.Ap` per (hitbox type, spellbook component) pair from
   `MagicSpellRegistry`, which is looked up before `PvNCombatScript`'s component-only default
   handler, so the cast lands at AP range (10 tiles) without walking and without a combat
   target. `onOpNpc1` stays as the adjacent fallback.
2. **Wall douse cast visuals.** The douse is resolved by our own AP handler, so no spell
   animation/projectile plays; only the wall's `*_loc01_despawn01` fires. Reusing the real
   `ElementalSpellAttack` would require a 1-hp attackable hitbox npc and would splash
   occasionally. Deferred polish.
2. **Damage buffs need small combat additions.** `rollRangedDamage(..., accuracyMultiplier)`
   exists but `PvNCombat` does not pass one; adding a `PlayerAccuracyModifier`-style hook at
   the call site is a contained change (still deferred). The −1-tick attack-delay-per-elemental
   kill is implemented directly in the elemental kill hook. Both are scoped to the RT module's
   needs and inert for everything else.
3. **Drop table ownership.** Move RT data into `content/bosses/royal-titans` (chosen) versus
   adding a public part-table accessor to `RSDropTable`. Moving keeps `content/drops`
   declarative and the choice logic in one place; the accessor is cleaner API but touches a
   shared library for one boss.
4. **Titan positioning.** Keep both titans stationary on their spawn tiles and cast from
   there (chosen, simplest, matches the captured wall rows and blast geometry) versus
   scripted walks to the arena edge as the wiki describes. If the live client looks wrong,
   the fallback is an external that moves the titan to a fixed cast tile before each summon.
5. **Kill count attribution.** Coordinator increments `varp.total_royal_titan_kills` once per
   clear with `param.killcount_notify = false` on both titans (chosen), versus letting
   `param.killcount_varp` fire per titan — which would count 2 per kill and print the message
   twice. If the official client does *not* print the line on the varp write, the coordinator
   sends the same text itself; the live pass settles which side prints it.
6. **Respawn fallback.** The 18-second clock starts on the first loot interaction (observed).
   If nobody interacts, start it from the death tick instead so an abandoned instance does
   not hold corpses forever (unverified assumption).
7. **Combat achievements** are out of scope, as with Mad Angel.
8. **Prayer scroll gating** ships unconditional with a `TODO` (no prayer module).

## Batched live test protocol (one session)

Prerequisite: `gradlew run` reached `Server ready`, and the module's tests are green.

```
get_client_state                                   -> LOGGED_IN
type_chat "::tele 2948 9574" + screenshot           -> confirm the position before clicking
interact_object "royal titans entrance"             -> mesbox with the entry warning (screenshot)
click "Yes"                                         -> instance entry (~10-20 s fade)
list_npcs "branda" / "eldric"                       -> both present, Attack op, size 3
interact_npc "branda" Attack                        -> HP bar appears; it switches when the other titan is hit
get_effect_history {sinceMs: 8000}                  -> seqs 11974/11973 on a 7-tick cadence
watch a melee stomp                                 -> 15 ground effects rolling away from the titan, 5x3
(wait for the summon anim)                          -> wall cast, then at +4 five hitbox npcs + locs
list_npcs "hitbox"                                  -> rs_loc_attack_fire_hitbox on a random z row, x2909-2913
click "Douse" on a wall tile                        -> that tile + 2 neighbours disappear (open decision 1)
stand on a tile before the next cast                -> up to 12 and the tile still forms
(wait for the elemental summon)                     -> 3 elementals in a vertical line, random column/rows
kill one elemental                                   -> next attack delay 1 tick shorter (hit cadence)
attack Branda to ≤210 (::maxhit to speed up)        -> both titans teleport, spawn01, then the blast cycle
screenshot at charge/release                        -> charge anim, 3 waves 2 ticks apart, one protection loc
walk onto the protection tile                       -> 0 damage; step off on the next cycle -> up to 30
kill both titans                                    -> KC line, duration/PB line, eligibility line (screenshots)
get_var {type:"varp", id:4648}                      -> incremented by exactly 1
interact_npc "branda" (inactive) Loot               -> ground items at my tile + valuable-drop lines
list_ground_items                                   -> counts match the rolls; Sacrifice yields no primary
interact_npc "branda" (inactive) Loot again         -> "not worthy" refusal
interact_npc "eldric" (inactive) Take pages         -> 10-19 desiccated pages
wait 18 s                                           -> corpses play death seq, then fresh titans at spawn tiles
interact_object "royal titans scoreboard"           -> interface 909 with KC / deaths / PB
```

`::` commands apply late and `type_chat`'s Enter is unreliable (see Mad Angel's harness
notes): screenshot after each command before trusting the effect. Damage numbers need a
short god-off window; use `get_skills` HP as the abort signal and toggle `::god` back on
immediately. Chat is not readable through the MCP — every message above is verified from a
screenshot.

## Wiki cross-check (revised against the packet capture)

Re-read of <https://oldschool.runescape.wiki/w/Royal_Titans> plus the two rsprox captures, used to
correct the first draft of this plan:

| Wiki / capture | Implementation state |
|---|---|
| Phase 1: titans "roam freely around their half of the arena" and perform one of three actions (wall row, 3 elementals, smash) | Done: patrol per half, front/back gating (smash at the front, wall/elementals backed off) |
| Smash is "fully blocked with Protect from Melee" | N/A by design: the engine's queued-hit processor applies protection-prayer reduction to every `HitType.Melee` hit, so `tileAoE(type = Melee)` is already fully blocked |
| Elementals are killed with opposite-element magic; damage to them does **not** count towards contribution | Douse/kill paths exist; contribution math not implemented |
| Walls are cleared with opposite-element spells | Done, including impact-timed removal and autocast left-click |
| Phase 2 at 35%: both titans teleport onto the arena, become static, keep stomp + elementals, **stop** conjuring rows, and periodically cast an arena-wide spell with a safe spot shown before the hit | Implemented: `phase(PHASE_TWO, entryHp = 0.35, lockMovement = true)`, entry ability teleports to the middle (Branda one row south of the safe centre, Eldric four rows north so they do not overlap), pulse on a 30-tick timer: charge anim/spotanim, `CHARGE_TICKS = 8`, release, 2 ticks, damage every arena tile except the marked safe tile (up to 30) |
| Phase-2 safe spot is marked 2-3 s before the hit | `loc.vfx_giant_attack_aoe_protection01` on the safe tile for the charge, plus three arena-wide `*_aoe_proj01` passes 2 ticks apart |
| Survivor: melee pierces protection prayers, arena-wide safe spot is as far from the survivor as possible | Not implemented |
| Loot: Loot / Sacrifice (double pet rate) / Take pages (10-19 desiccated), two rolls scaled by contribution | Not implemented (drop tables planned) |
| Death is unsafe, grave outside the instance; entrance warning text; `Quick-escape` via the tunnel | Warning/tunnel wired; grave + loot flow pending |
| "I need room": all fires extinguished / icicles melted **before** they dissipate | Wall lifetime 38 ticks + douse path support this; no CA tracking |

Capture-derived numbers used above, all read from the packet log's per-tick sections (the log's
`[N]` headers are ticks, which is what makes the delays measurable):

- walk speed 1 tile/tick; walk distances 2-5 tiles
- last step -> first attack: **+1 to +2 ticks** (99→101, 169→170, 156→158)
- melee cadence while standing: **7 ticks** (101, 108, 115, 122, 129)
- dwell: front (x 2906) 28-43 ticks / 4-5 slams, back (x 2904) 12-17 ticks / 1 cast
- melee telegraph: 15 `map_anim` tiles, delays 0/2/4/6/8 along the depth axis
- walls: 5 locs (`shape 10`) on one row, columns x 2909-2913, rows 9561-9573

## Framework notes

- **Two specs, one coordinator.** `BossCombat.register`'s collection overload requires
  identical npc types, so each titan gets its own `register` call —
  `rt_fire_queen` + `rt_fire_queen_inactive`, and `rt_ice_king` + `rt_ice_king_inactive` —
  and a `@Singleton` coordinator carries the cross-titan state. `EncounterRegistry.of(npc)`
  and `BossEncounter.transitionTo(...)` are public, so the phase-2 handler can flip the
  sibling directly.
- **Inactive is a transmog, not a new npc type.** `Effect.Transmog(npc.rt_*_inactive,
  Int.MAX_VALUE)` keeps the slot, the loot ops live on the inactive type, and the fight type
  stays `type`, so the spec/HP-bar/kill-count registrations still match.
- **`recordsKillTime = false`** on the instance: the duration message must fire once for the
  pair, not once per titan, and the message text differs between the PB and non-PB forms.
  `formatInstanceKillTime` and `InstanceKillTimer.reportKillTime` are public; the coordinator
  reads the stored best, compares, writes `varp.royal_titans_best_time`, and emits the string.
- **Spawns belong in `InstanceArea.copyRegions(..., npcSpawns = …)`**, not in the enter
  prelude: the manager then owns create/join/reset, and a re-entry into an emptied copy gets
  fresh titans. Mad Angel's re-entry bug is the cautionary tale.
- **State in varps/varbits, not attrs**: per-run phase/contribution/dead flags live in the
  coordinator keyed by instance id, with only persisted values (deaths, best time, KC) in
  varps.
- **Sounds** have gameval symbols for the boss attacks; the `sound_area` ids (10190 fire
  minion spawn, 10172 ice, 3825 explode, 10146/2238 wall) do not, so they go through the
  numeric `WorldRepository.soundArea` overload as Mad Angel's blast does.
- **Instance copies live at different absolute coordinates.** `InstanceArea.copyRegions`
  relocates the copied map squares into the instance X band (`x >= 6400`), so nothing inside
  the arena may use captured overworld coordinates: wall rows, elemental tiles and any
  telegraph geometry must be offsets from the caster's `spawnCoords` (or the session's local
  coords). The walls pick a random row in `spawnZ-5 .. spawnZ+7` and use the element's
  columns (`dx +3..+7` fire, `dx -5..-1` ice); elementals take a random column from those
  same ranges and a random 3-row line starting in `spawnZ-5 .. spawnZ+5`.
- **A copied zone whose source has no collision must stay walkable.** `RegionRegistry.registerZone`
  copies map-file flags by reading the *source* zone through `collision[x, z]`; an unallocated
  source zone does not read `0`, it reads `CollisionFlagMap.DEFAULT_COLLISION_FLAG` (`-1`), and
  `-1 and (ROOF or BLOCK_WALK)` is non-zero, so every destination tile would be written as blocked.
  The destination zone is now always allocated, and the flag copy is skipped when the source zone
  is not allocated, so such a zone copies as empty (walkable).
- **Arena geometry (from the capture).** The play area is the 5-wide corridor `x 2909-2913`,
  roughly `z 9561-9573`; Branda patrols `x 2904-2906` and Eldric `x 2914-2916` along the sides,
  both roaming `z 9561-9571`. Walls are 5 wide on a single row, spanning the corridor - which is
  exactly why they span the corridor and not the whole arena. The corners of the rectangle are
  closed off with `invisible_type8_blocking` (26209) locs and the floor is marked with
  `invisible_type8_nonblocking` (29733), all added at runtime.
- **The map blocks the side ledges - walk with `MoveRestrict.Blocked`.** The cache marks every tile
  outside the corridor as `BLOCK_MAP_SQUARE`, so the copied instance is `0x200000` (BLOCK_WALK)
  everywhere except the corridor, the titans' patrol strips included. Carving that flag out of the
  map would also let players leave the corridor, so instead the titans walk with
  `MoveRestrict.Blocked` - the engine restriction that only accepts *blocked* tiles - which is
  exactly "this npc lives on the other side of the barrier". `Normal` while engaged. The walkable
  rows are measured from collision (`measureStrip`) at engage rather than assumed, so a destination
  is never aimed at a tile the restriction would reject, and `validatedCollisionFlag` returns 0 for
  `Blocked`, so summoned elementals never block the path.
- **Front/back duty cycle.** The patrol alternates the two x extremes of the strip, so each titan is
  either at the *front* (as close to the corridor as it patrols) or *backed off*. Abilities are
  gated on that: the melee slam is only selectable at the front
  (`Condition.Custom` on `coords.x` vs `spawnCoords.x`), the wall and the elemental summon only
  when backed off. That reproduces the OSRS pattern of stepping in to slam and stepping back to
  cast, instead of picking abilities at random regardless of position.
- **NPC movement while interacting.** `NpcInteractionProcessor` sets `interacted = true`
  whenever a script AP trigger fires, which skips `movement.process` for that tick, so an npc
  holding a player interaction in AP range cannot walk. The patrol therefore swaps between
  `ApPlayer2` (stand and attack) and `ignoreCombatInteractions` + `npc.walk(dest)` (walk with
  no interaction; the flag makes `NpcModeProcessor` drop the interaction every cycle, and the
  plain step walk is used because `walkTo`'s routefinder returned no route for the size-3
  titan); `apRangeOverride` is only there so the titans attack from their strip without
  chasing, and `BossEncounter.busyUntil` gates the walk so a titan never strolls off mid-cast.
  A forced walk after `3 × STAND_TICKS` keeps a permanently busy titan moving.

## Appendix A — ids used by the implementation

| Group | Symbol | Id |
|---|---|---|
| npc | `rt_fire_queen` / `rt_ice_king` | 12596 / 14147 |
| npc | `rt_fire_queen_inactive` / `rt_ice_king_inactive` | 14148 / 14149 |
| npc | `rt_summon_elemental_fire` / `_ice` | 14150 / 14151 |
| npc | `rs_loc_attack_fire_hitbox` / `_ice_hitbox` | 14152 / 14153 |
| npc | `royal_titans_area_attack_timer_handler` | 14154 |
| loc | `royal_titans_entrance` | 55986 |
| loc | `royal_titans_exit` | 55987 |
| loc | `cavewall_shortcut_royal_titans_east` / `_west` | 55988 / 55989 |
| loc | `vfx_branda_queen_loc01_idle01` / `vfx_eldric_king_loc01_idle01` | 56001 / 56002 |
| loc | `vfx_giant_attack_aoe_protection01` | 56003 |
| loc | `royal_titans_scoreboard` | 56004 |
| loc | `fire_giant_branda_queen_spawn01_floor01/02`, `death01_floor01/02` | 55993–55996 |
| loc | `ice_giant_eldric_king_spawn01_floor01/02`, `death01_floor01/02` | 55997–56000 |
| seq | Branda `spawn01` 11966, `melee` 11974, `summon01` 11976, `summon02` 11978, `aoe01` 11981, `aoe02` 11982, `teleport` 11984, `inactive` 11990, `death` 11992 | — |
| seq | Eldric `spawn01` 11963, `melee` 11973, `summon01` 11975, `summon02` 11977, `aoe01` 11979, `aoe02` 11980, `teleport` 11983, `inactive` 11985, `death` 11987 | — |
| seq | elementals fire/ice: spawn 11956/11954, attack 11947/11950, explode 11961/11959, death 11957/11951 | — |
| spotanim | titan spawn01/02/despawn, aoe proj, attack_summon01/02, aoe01/02, teleport, melee proj, death | 3202–3224 |
| varp | `total_royal_titan_kills` | 4648 |
| dbrow | `collection_log_category_royal_titans` | 60083 |
| dbrow | `royal_titans_enter_warning` (id only — no gameval symbol; the text is inlined) | 16089 |
| interface | `royal_titans_scoreboard` (title `Royal Titans Statistics`) | 909 |
| interface | `hpbar_hud` (303) / `hpbar_hud_hp` 6099, `_basehp` 6100, `_npc` 1683, `_boss` 12401 | — |

## Appendix B — drop tables to encode

The retiring `content/drops` tables already carry the rows; the work is restructuring them
into parts and fixing the three errors listed under **Loot design**. Item symbols verified
in the cache: `obj.twinflame_piece_1` (30628), `obj.twinflame_piece_2` (30631),
`obj.twinflame_staff` (30634), `obj.deadeye_prayer_scroll` (30626),
`obj.mystic_vigour_prayer_scroll` (30627), `obj.giantsoul_amulet_charged` (30638),
`obj.desiccated_page` (30640), `obj.rtbrandapet` (30622), `obj.rteldricpet` (30624).

| Part | Branda | Eldric |
|---|---|---|
| `main` (2 rolls) | 55-weight table: fire staffs, rune plateskirt/scimitar/pickaxe, fire/chaos/death/nature/law/soul/blood runes, rune arrows, coal, gold ore, fire orbs, herbs, tree seeds, coins, prayer potions, pages 6–24 | 56-weight table: water staffs, rune platelegs/sq shield/axe, rune pickaxe at weight 1, water/chaos/death/nature/law/soul/blood runes, rune arrows, coal, gold ore, water orbs, herb seeds, tree seeds, coins, prayer potions, pages 6–24 |
| `uniques` | 1/75 `twinflame_piece_2`, 1/75 `mystic_vigour_prayer_scroll`, 1/16 `giantsoul_amulet_charged` | 1/75 `twinflame_piece_1`, 1/75 `deadeye_prayer_scroll`, 1/16 `giantsoul_amulet_charged` |
| `pet` | `rtbrandapet` 1/3000 (1/1500 on Sacrifice) | `rteldricpet` 1/3000 (1/1500 on Sacrifice) — confirm which pet the wiki ties to which titan; the cache ships one per titan |
| `clues` | hard 1/25, elite 1/100 | same |
| `pages` | 10–19 `desiccated_page` | same |

`giantsoul_amulet_charged` and the two pets are also granted through the collection log
category, so `CollectionLog.grant` must run on the click-time spawn, not at kill time.

## Appendix C — capture timings used above

| Event | Ticks |
|---|---|
| Melee cadence (per titan) | 7 |
| Wall cast → hitbox/loc materialise | 4 |
| Wall natural despawn | ~38 |
| Elemental cast → 3 spawns | 1–2 |
| Elemental melee cadence | ~4 |
| Elemental self-destruct after spawn | 10–15 |
| `teleport01` → `spawn01` | 2 |
| `spawn01` → `attack_aoe01` (charge) | 8 |
| `attack_aoe01` → `attack_aoe02` (release) | 6 |
| Blast waves | release +0 / +2 / +4 |
| Blast cycle length | 22–36 |
| Safe-tile headbar | 7 |
| Death → first loot interaction | player-driven |
| First loot interaction → respawn | 18 s (≈30 ticks) |
| Loot click → corpse death seq + floor locs | ~20 |
