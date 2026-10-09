# Prison Experiment (Fabric 1.21.1)

Run a prison where the guards and prisoners are **villagers** with their own AI. You play the Warden
(give orders) or a Prisoner (try to escape / start a riot). Also works with LAN friends as extra guards/prisoners.

## Build the mod
Needs JDK 21 and Gradle 8.8+ (or copy `gradlew`, `gradlew.bat`, `gradle/` from the official Fabric example mod).

    gradle build

Jar: `build/libs/prison-experiment-1.0.0.jar` -> `.minecraft/mods` with Fabric API (1.21.1) and Fabric Loader 0.16+.

## 60-second setup (singleplayer, no cheats needed)
1. Stand on flat ground at the **north-west corner** of the site. The prison builds toward +X (east) and +Z (south); everything in the area is cleared.
2. `/prison play warden`  (you get uniform, Baton, Cell Key, Warden Radio)
3. `/prison build`        (default **large**; also `/prison build medium` / `small`)
4. Wait ~10 s for construction to finish (it is spread over many ticks), then `/prison populate`
   (default: ~140 prisoners + 23 guards on the large prison, or `/prison populate <guards> <prisoners>`).

| Size | Footprint | Cells | Inmate capacity |
|---|---|---|---|
| small | 56 x 24 | 8 | 24 |
| medium | 56 x 40 | 24 | 72 |
| **large** | 68 x 72 | 56 | 168 |

Be the prisoner instead: `/prison play prisoner` (guards search you, chase you outside the zone and arrest you). Back: `/prison play warden`.

## The prison (large)
- 4 cell blocks, each with 14 cells (3 inmates per cell: two beds, cauldron, barred window) around a lit corridor with its own iron yard door
- Big gravel yard (36 wide) with road, benches, light posts; lanes between the blocks
- Guard barracks (9 beds, chest, crafting) + warden's office, 4 corner towers with ladders
- Main gate in the south wall (iron, locked). Beyond it = freedom.
Save it as a vanilla structure with a Structure Block if you want a copy.

## Performance with 150+ villagers
Calm prisoners think every 2 s in rotating groups; guards every 0.5 s; during escapes/riots everyone involved switches to 0.5 s.
If your game stutters: lower simulation distance, or populate fewer prisoners. The vanilla villager brain is disabled for NPCs.

## Ordering the guards (Warden Radio)
Right-click = pick the next order (shown above the hotbar). **Sneak + right-click = transmit.**
Same orders via `/prison order <name>`:
| Order | Effect |
|---|---|
| patrol | guards walk patrol routes, randomly pat down prisoner players |
| follow | guards follow you |
| stay | guards hold position |
| raid | cells open, every prisoner (villager or player) is searched; contraband is confiscated |
| yard | cells + yard door open, prisoners go to the yard |
| cells | prisoners return to cells, doors close and lock |
| lockdown | toggle: all doors sealed, prisoners to cells, guards man their posts |
| headcount | prisoners line up in the yard, then a head count is announced |
| crush | during a riot: guards fight harder; riot ends after ~20 s |

## What the NPCs do on their own
- Prisoners follow the current phase (cells / yard / line-up). In the yard some pick up contraband and **try to pick the main gate lock**
  (15 s at the gate). Guards react and arrest them if they get there in time; otherwise the gate opens, the prisoner runs and "ESCAPES".
- Guards chase anyone outside the prison zone (including you as a prisoner) and **drag them back** with contraband confiscated.
- A **riot** starts when a prisoner kills a guard (or `/prison riot start`, allowed for prisoner players too).
  Prisoner villagers attack guards, guards stun prisoners. If all guards die, the prisoners take the prison and flee.
- Hit a guard as a prisoner -> you are "wanted" and every guard hunts you for a minute.
- Villager NPCs have their normal brain disabled by a mixin; trading is blocked. Right-click a guard/prisoner to hear them speak.
- Baton: hit = stun, right-click an NPC prisoner = search (confiscates their contraband).
- NPC state (tags) survives saving; current orders reset to patrol/cells after a restart.

## Players / roles / perms
Prisoner: orange uniform, can't build/break (except in a riot), can't open locked doors.
Guard: blue, baton + key. Warden: black, everything. Ops can use every command. Cell Key: sneak-click door = register/lock/unlock;
click = open/close. Keys are contraband - a prisoner who steals one can open doors.

## Commands
`/prison play warden|guard|prisoner` `build` `populate [g] [p]` `npc spawn guard|prisoner [n]` `npc clear` `order <order>`
`role <player> <role|none>` `zone pos1|pos2|clear` `setspawn` `arrest <player>` `lockdown true|false`
`riot start|stop` `give baton|key|radio` `status`

## Tuning (source)
`Npcs.java`: escape frequency (the `RNG.nextInt(1000) < 6` line), lock-pick time (300 ticks), guard speed. `Gear.EXTRA_CONTRABAND`: contraband list.
`PrisonBuilder.java`: the layout. Textures reuse vanilla items (blaze rod, gold nugget, amethyst shard).
