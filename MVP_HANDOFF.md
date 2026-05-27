# Breached MVP Handoff

This document summarizes the current implementation state of the Breached Fabric mod as inspected on May 27, 2026. It is intended as a handoff for future Codex sessions and should be treated as implementation context, not a design promise.

Breached is a server-oriented Fabric Minecraft mod (`modid: breached`) on Minecraft `1.21.11`, Yarn `1.21.11+build.5`, Fabric Loader `0.19.2`, and Java 21. The active Java package is `nrd.breached`.

Where the current implementation differs from `Project_Plan.md`, assume the project plan is probably outdated unless a future task explicitly says otherwise. The current structure of the mod is close to the desired MVP direction.

## Main Initialization

### What It Does

`Breached.java` is the main mod initializer. It registers custom blocks/items, the Landlock block entity, team commands, gameplay callbacks, item group entries, config loading, and dimension/structure rules.

Registered content:

- `tier_1_crafting_bench`
- `tier_2_crafting_bench`
- `tier_3_crafting_bench`
- `landlock_block`
- `iron_breacher`
- `diamond_breacher`
- `netherite_breacher`
- `probe`

### Main Files

- `src/main/java/nrd/breached/Breached.java`
- `src/main/resources/fabric.mod.json`
- `src/main/resources/breached.mixins.json`
- `src/client/java/nrd/breached/client/BreachedClient.java`
- `src/client/java/nrd/breached/client/BreachedDataGenerator.java`
- `src/client/resources/breached.client.mixins.json`

### Interactions

- Loads config through `BreachedConfig.load()`.
- Registers `/breached team ...` commands through `TeamCommands.register()`.
- Registers Landlock break/place/door/probe callbacks.
- Registers bed respawn cooldown hooks.
- Disables villager and wandering trader interaction.
- Registers dimension rules, which then registers centralized structure placement.

### Risks / Confusing Details

- `BreachedClient` and `BreachedDataGenerator` are empty.
- Client mixin config exists but has no client mixins.
- The mod is effectively server-side gameplay code plus normal assets.
- `CentralSpawnPoiManager` exists but is not registered anywhere in the current initializer path.

## Config System

### What It Does

`BreachedConfig` creates and loads `config/breached.json` at runtime. It stores structure placement tuning, minor POI lifecycle settings, and major loot restock settings. It normalizes values and rewrites missing defaults back to the config file.

### Main Files

- `src/main/java/nrd/breached/config/BreachedConfig.java`

### Config Areas

- `plannedCandidateEvaluationsPerStructureTick`
- `minorPoi`
- `majorStructureLoot`
- `structures`

Default configured structure keys:

- `breached:swordstatue`
- `breached:portal`
- `breached:horace`
- `breached:pinktree`
- `breached:bigboat`
- `breached:cavehut`
- `breached:pueblo1`
- `breached:pueblo2`
- `breached:raft`
- `breached:waystop`

### Notes / Cleanup

- `abandonedhut` and `cadenboat` are intended to behave like the other minor structures. A future cleanup should expose them in the default config map if per-structure config remains the pattern.
- Config does not appear reloadable in-game; it is loaded during mod initialization.
- Config can override candidate count and spacing, but not every structure definition field.
- Runtime config is outside the repo under the Fabric config directory.

## World Size / Dimension Rules

### What It Does

`BreachedDimensionRules` detects Breached world presets and applies fixed world borders plus a safe world spawn on world load.

Presets:

- `breached:breached_island`
- `breached:small_breached_island`

Border behavior:

- Standard preset: Overworld border size `2500`, Nether border size `1250`.
- Small preset: Overworld border size `1000`, Nether border size `500`.
- Border center is always `0,0`.
- No border rule is applied to the End.

Spawn behavior:

- On Overworld load, searches for a safe spawn inside the border.
- If no safe spawn is found, creates a small grass fallback platform at `0,0`.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedDimensionRules.java`
- `src/main/resources/data/breached/worldgen/world_preset/breached_island.json`
- `src/main/resources/data/breached/worldgen/world_preset/small_breached_island.json`
- `src/main/resources/data/breached/worldgen/noise_settings/island_overworld.json`
- `src/main/resources/data/breached/worldgen/noise_settings/small_island_overworld.json`
- `src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json`

### Interactions

- Detection uses the selected world preset when possible.
- It also falls back to checking whether the Overworld `NoiseChunkGenerator` matches the Breached noise settings.
- Calls `BreachedStructurePlacementManager.register()` during dimension-rule registration.

### Notes / Confusing Details

- The two Breached noise settings currently hash the same as the top-level copied vanilla `data/minecraft/worldgen/noise_settings/overworld.json`; the "island" behavior appears to come from world borders, not from custom island terrain generation.
- The top-level `data/` folder is outside `src/main/resources` and is likely reference/unpackaged data, not mod-packaged data.
- The current Nether border sizes are intentional for the MVP.

## End Access

### What It Does

The End is not currently gated by an explicit Breached system. The desired future direction is to make End access part of the game in a controlled way similar to official Nether portals.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedDimensionRules.java`
- `src/main/resources/data/breached/worldgen/world_preset/breached_island.json`
- `src/main/resources/data/breached/worldgen/world_preset/small_breached_island.json`
- `src/main/java/nrd/breached/item/BreacherItem.java`

### Current State

- Both Breached world presets still define `minecraft:the_end`.
- There is no mixin or event hook blocking End portal use.
- `BreacherItem` blocks mining `END_PORTAL_FRAME`, but that is unrelated to a complete End access system.

### Future Work

- Decide and implement the controlled End-access equivalent of official Nether breach sites.
- Until then, confirm actual current End access behavior during testing so it is not surprising on a multiplayer server.

## Nether Portal Restrictions

### What It Does

Player-created Nether portals are blocked in Breached preset worlds. Existing/official portals are intended to be the allowed Nether access points.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedDimensionRules.java`
- `src/main/java/nrd/breached/mixin/FlintAndSteelItemMixin.java`
- `src/main/java/nrd/breached/mixin/FireChargeItemMixin.java`
- `src/main/java/nrd/breached/mixin/AbstractFireBlockMixin.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureDefinitions.java`

### Interactions

- `FlintAndSteelItemMixin` and `FireChargeItemMixin` fail use if the action would create a Nether portal.
- Campfires, candles, and candle cakes are allowed to be lit.
- `AbstractFireBlockMixin` cancels portal creation during fire block addition.
- The checks only run for server worlds where `BreachedDimensionRules` detects a Breached preset, and only in Overworld/Nether.

### Risks / Confusing Details

- This blocks frame ignition; it does not globally block every possible vanilla portal side effect.
- Existing portal blocks can still be used.
- Official portal structure placement has a `NO_ACTIVE_NETHER_PORTAL_NEARBY` pre-placement check that can register an already-active portal instead of placing the structure. This is not desired long term and should eventually be removed or replaced with clearer official-site behavior.

## Structure Definitions

### What It Does

Structures are defined as records with placement constraints, priority, spacing policy, protection radius, support behavior, terrain validation, loot behavior, and lifecycle classification.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedStructureDefinition.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureDefinitions.java`
- `src/main/resources/data/breached/structure/*.nbt`

### Major Structures

`PLANNED_STRUCTURES`:

- `swordstatue.nbt` / `breached:swordstatue`
- `portal.nbt` / `breached:portal`
- `horace.nbt` / `breached:horace`
- `pinktree.nbt` / `breached:pinktree`
- `bigboat.nbt` / `breached:bigboat`

Protected major structures:

- Sword statue, radius `72`
- Official portals, radius `24`
- Big boat, radius `48`

Major structures that should be protected but are not currently in `PROTECTED_STRUCTURES`:

- Horace
- Pink tree

### Minor POI Structures

`MINOR_POI_STRUCTURES`:

- `abandonedhut.nbt`
- `cadenboat.nbt`
- `cavehut.nbt`
- `pueblo1.nbt`
- `pueblo2.nbt`
- `raft.nbt`
- `waystop.nbt`

Minor POIs are optional, random, budgeted, and lifecycle-managed.

### Small Preset Overrides

Small preset narrows placement radii for several majors and minor POIs, especially portals, Horace, pink tree, sword statue, big boat, and minor structures.

### Risks / Confusing Details

- Structure definitions are dense and positional; adding fields to `BreachedStructureDefinition` requires updating many copy methods.
- Some definitions have `protectionRadius` set to `0` and `protectedStructure=false`, even if they are major structures.
- Naming still mixes "planned", "major", "protected", "central spawn", and "POI"; verify intent before changing behavior.

## Structure Spawning / Placement

### What It Does

`BreachedStructurePlacementManager` is the centralized placement system. It handles major structure reservations, forced chunk loading, placement evaluation, minor POI spawning, minor POI retirement, structure protection, and major loot restocking.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementManager.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureSpawnManager.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementState.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureSupportGenerator.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureSite.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacement.java`

### Major Placement Flow

- On chunk load, planned structures are enqueued if their reserved candidate chunk loads.
- On world tick, required reserved chunks are force-loaded one at a time.
- Major structures are sorted by priority and reserve the best candidate that passes spacing.
- Candidate evaluation checks spacing, template presence, loaded footprint, terrain/site rules, and obstructions.
- The best candidate per tick is placed.
- Placement state stores center, origin, size, restock data, reservations, and failed candidates.

### Minor POI Flow

- Chunk load adds a pending minor POI chunk.
- Pending chunks are sampled at a configurable rate.
- Player-nearby chunk scans also try to spawn minor POIs.
- Minor POIs use per-chunk candidate indexes and local random candidates.
- Minor POIs respect budget, radius, spread cells, spacing, terrain, obstruction, and loaded footprint.
- Minor POIs can be retired/despawned later.

### Support / Cleanup

`BreachedStructureSupportGenerator` can:

- Do nothing.
- Generate solid support under a water footprint.
- Replace marker blocks and generate support pillars beneath them.

Minor POIs also capture original blocks for later restoration and can clear natural surface obstruction above placed blocks.

### Risks / Confusing Details

- There are several static in-memory pending/forced-chunk sets. They are not persisted, but placement state is persisted.
- Required structure force-loading loads one missing footprint chunk per tick and releases completed/expired forced chunks.
- Structure placement is heavily tick-driven and stateful; bugs may only appear after chunk load/order/restart edge cases.
- Candidate failures persist in `BreachedStructurePlacementState`.

## Spawnpoint Structure

### What It Does

There are two related ideas in the code:

- `BreachedDimensionRules` sets the actual world spawn to a safe surface position.
- `BreachedStructureSpawnManager.CENTRAL_SPAWN` aliases the sword statue definition.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedDimensionRules.java`
- `src/main/java/nrd/breached/worldgen/CentralSpawnPoiManager.java`
- `src/main/java/nrd/breached/worldgen/CentralSpawnPoiState.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructureSpawnManager.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementManager.java`

### Current State

- `CentralSpawnPoiManager.register()` is not called.
- The sword statue is currently handled by the generic planned-structure system.
- Legacy central spawn state can be migrated into generic placement state.

### Note

`CentralSpawnPoiManager` appears to be old unused code. The current generic sword statue placement path is the desired direction.

## Portal Structures

### What It Does

Official Nether portals are major planned structures. They are intended to provide the allowed Nether entry points while player-made portals are blocked.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedStructureDefinitions.java`
- `src/main/resources/data/breached/structure/portal.nbt`
- Nether portal restriction mixins listed above.

### Current Definition

- Count per world: `2`
- Placement mode: `DISTRIBUTED_RING`
- Standard radius: `450` to `1000`
- Small radius override: `200` to `250`
- Roughly opposed: `true`
- Protection radius: `24`
- Priority: `30`
- Pre-placement check: `NO_ACTIVE_NETHER_PORTAL_NEARBY`

### Interactions

- Structure protection prevents normal modification around official portal sites.
- Major loot restock can announce portal restocks.
- Player-made portal ignition is blocked in detected Breached worlds.

## Structure Protection

### What It Does

Protected Breached structures block survival block breaking and block placement inside their protection radius. Explosions are also blocked inside protected structure radius.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementManager.java`
- `src/main/java/nrd/breached/mixin/ExplosionBehaviorMixin.java`

### Interactions

- Uses persisted `BreachedStructurePlacementState`.
- Checks only Overworld placements.
- Creative players and level-two creative ops bypass block break/place protection.
- Landlock and Breacher logic do not bypass protected structure logic if the structure protection event returns false.

### Risks / Unfinished Areas

- Does not protect every interaction type, only break/place and explosions.
- Does not appear to block chests or other inventory use, which is likely intentional for loot.
- Does not protect Horace or pink tree because they are not in `PROTECTED_STRUCTURES`.

## Landlock Claim / Protection System

### What It Does

Landlock blocks claim a square 16-block radius in X/Z for all Y levels. Unauthorized players cannot break claimed blocks, place blocks in claims, or open door-like blocks in claims. Breachers are the intended raid bypass for claimed block breaking.

### Main Files

- `src/main/java/nrd/breached/block/LandlockBlock.java`
- `src/main/java/nrd/breached/block/LandlockBlockEntity.java`
- `src/main/java/nrd/breached/landlock/LandlockClaimManager.java`
- `src/main/java/nrd/breached/Breached.java`

### Rules

- Claim radius: `16`
- Required gap: `16`
- Minimum Landlock center spacing: `48`
- Max authorized Landlocks per player: `3`

### Interactions

- `PlayerBlockBreakEvents.BEFORE` blocks unauthorized break attempts unless the held item is a usable Breacher.
- `UseBlockCallback` blocks unauthorized block placement.
- `UseBlockCallback` blocks unauthorized doors, trapdoors, and fence gates.
- The probe item can report whether a block is inside any claim when sneak-used.

### Risks / Fragile Areas

- Landlock authorization is not tied to teams.
- Any non-authorized player can right-click a Landlock and authorize themselves if they are under the max authorization count. This is intentional current gameplay; physical access to the Landlock block matters.
- Claim lookup scans a 33x33 area across the full world height for each check. Placement spacing scans an even larger area. This is simple but can be expensive.
- Authorization counts scan loaded chunks only, so unloaded Landlocks may not count.
- Explosions, pistons, fluids, hoppers, and other anti-cheese vectors are not covered by Landlock protection in the current code.
- Non-door block interactions inside claims, such as containers, are not broadly blocked by Landlock code.

## Landlock Block Entity / Persistence

### What It Does

`LandlockBlockEntity` persists ownership and authorized player UUIDs in block entity data.

### Main Files

- `src/main/java/nrd/breached/block/LandlockBlockEntity.java`

### Data

- `owner_uuid`
- `authorized_players`

### Interactions

- Owner is assigned on block placement.
- Owner is automatically included in the authorized set.
- Sneak-use can remove a non-owner player's own authorization.
- Breaking/removing the block removes the block entity and therefore the claim.

### Risks

- No central index exists; all claim queries discover Landlocks by scanning block entities.
- No client sync behavior is implemented beyond normal block entity persistence.

## Teams

### What It Does

Provides basic persistent team commands and state.

### Main Files

- `src/main/java/nrd/breached/team/TeamCommands.java`
- `src/main/java/nrd/breached/team/TeamState.java`
- `src/main/java/nrd/breached/team/TeamData.java`

### Commands

- `/breached team create <name>`
- `/breached team disband`
- `/breached team invite <player>`
- `/breached team kick <player>`
- `/breached team transfer <player>`
- `/breached team join <name>`
- `/breached team leave`
- `/breached team info`
- `/breached team list`

### Persistence

Team state is a `PersistentState` stored on the Overworld persistent state manager.

### Risks / Unfinished Areas

- Team membership does not currently grant Landlock permissions.
- There is no team chat implementation in the inspected code.
- Team names use `StringArgumentType.word()`, so no spaces.

## Breachers / Custom Mining Behavior

### What It Does

Breachers are custom raid tools. They can break normal blocks with flat, slow mining speed and can bypass Landlock break protection when unauthorized.

### Main Files

- `src/main/java/nrd/breached/item/BreacherItem.java`
- `src/main/java/nrd/breached/mixin/BlockBreakingDeltaMixin.java`
- `src/main/java/nrd/breached/Breached.java`
- `src/main/resources/data/breached/recipe/iron_breacher.json`
- `src/main/resources/data/breached/recipe/diamond_breacher.json`
- `src/main/resources/data/breached/recipe/netherite_breacher.json`

### Current Behavior

- Iron Breacher mining delta: `1/300` per tick, about 15 seconds.
- Diamond Breacher mining delta: `1/200` per tick, about 10 seconds.
- Netherite Breacher mining delta: `1/100` per tick, about 5 seconds.
- All are correct for drops.
- Durability is consumed on successful block mining.
- Blocked blocks include bedrock, command blocks, structure blocks, jigsaw, barrier, and End portal frames.

### Recipes

- Iron Breacher: iron blocks plus diamond blocks.
- Diamond Breacher: diamond blocks plus gold blocks.
- Netherite Breacher: diamond blocks plus netherite block.

### Risks / Confusing Details

- Current Breacher behavior is considered acceptable for the MVP even where it differs from `Project_Plan.md`.
- Breacher balance and exact durability/speed values are expected to be updated later.
- Breachers are central to the mod direction. Preserve pickaxe/Breacher-based raiding unless explicitly changing design.

## Tiered Crafting Tables / Recipes

### What It Does

Adds tiered crafting tables and gates selected recipe outputs by required table tier.

### Main Files

- `src/main/java/nrd/breached/block/IronCraftingTableBlock.java`
- `src/main/java/nrd/breached/block/DiamondCraftingTableBlock.java`
- `src/main/java/nrd/breached/block/NetheriteCraftingTableBlock.java`
- `src/main/java/nrd/breached/screen/IronCraftingScreenHandler.java`
- `src/main/java/nrd/breached/screen/DiamondCraftingScreenHandler.java`
- `src/main/java/nrd/breached/screen/NetheriteCraftingScreenHandler.java`
- `src/main/java/nrd/breached/crafting/CraftingTier.java`
- `src/main/java/nrd/breached/crafting/CraftingTierProvider.java`
- `src/main/java/nrd/breached/crafting/CraftingTierRules.java`
- `src/main/java/nrd/breached/mixin/CraftingScreenHandlerMixin.java`
- `src/main/resources/data/breached/recipe/*.json`

### Current Rules

- Regular crafting table is Tier 0.
- Iron table is Tier 1.
- Diamond table is Tier 2.
- Netherite table is Tier 3.
- Higher tiers can craft lower-tier results.
- The mixin clears the output slot if the result requires a higher tier.

### Recipe Highlights

- Tier 1 table: 8 iron blocks around a crafting table.
- Tier 2 table: diamond/redstone blocks around Tier 1 table.
- Tier 3 table: diamond blocks, beacon, netherite block, and Tier 2 table.
- Breachers are tier-gated through `CraftingTierRules`.

### Risks / Unfinished Areas

- The gating list is hardcoded by result item, not data-driven.
- There is no player-facing message when a recipe output is blocked.
- Landlock block has no recipe in the inspected repo.

## Armor Durability

### What It Does

Overrides max durability for armor through an `ItemStack.getMaxDamage` mixin.

### Main Files

- `src/main/java/nrd/breached/armor/ArmorDurabilityRules.java`
- `src/main/java/nrd/breached/mixin/ItemStackMixin.java`

### Current Values

- Copper: `200`
- Chainmail: `250`
- Leather: `150`
- Gold: `200`
- Iron: `250`
- Diamond: `300`
- Netherite: `500`

### Risks

- This is global for those items.
- Values differ somewhat from the older design text in `Project_Plan.md`.

## Bed Respawn Cooldown

### What It Does

Beds are respawn points only. Sleeping does not skip night. Bed respawns apply a 60-second cooldown; if a player dies while their bed is on cooldown, they respawn at world spawn.

### Main Files

- `src/main/java/nrd/breached/respawn/RespawnCooldownManager.java`
- `src/main/java/nrd/breached/mixin/ServerPlayerRespawnMixin.java`
- `src/main/java/nrd/breached/Breached.java`

### Interactions

- `EntitySleepEvents.ALLOW_RESETTING_TIME` always returns false.
- `ServerPlayerRespawnMixin` intercepts respawn target resolution.
- `ServerPlayerEvents.AFTER_RESPAWN` applies the cooldown after a valid bed respawn.

### Risks

- Cooldowns are static in-memory maps and are not persisted across server restart.
- Cooldown uses wall-clock milliseconds, not server ticks.

## Loot Tables / Container NBT Behavior

### What It Does

Structure containers use template NBT `LootTable` fields. Placement code reapplies those loot tables after placing a template, and major structures can restock later by resetting tracked container loot tables with new seeds.

### Main Files

- `src/main/java/nrd/breached/worldgen/BreachedStructureSpawnManager.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementManager.java`
- `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementState.java`
- `src/main/resources/data/breached/loot_table/chests/tier_1.json`
- `src/main/resources/data/breached/loot_table/chests/tier_2.json`
- `src/main/resources/data/breached/loot_table/chests/tier_3.json`
- `src/main/resources/data/breached/loot_table/containers/tier_1.json`
- `src/main/resources/data/breached/loot_table/containers/tier_2.json`
- `src/main/resources/data/breached/loot_table/containers/tier_3.json`

### Observed Structure Loot References

- `abandonedhut.nbt`: tier 1
- `bigboat.nbt`: tier 1, tier 2, tier 3
- `cadenboat.nbt`: tier 1
- `cavehut.nbt`: tier 1
- `horace.nbt`: tier 2, tier 3
- `pinktree.nbt`: tier 1, tier 2, tier 3
- `portal.nbt`: tier 2
- `pueblo1.nbt`: tier 1, tier 2
- `pueblo2.nbt`: tier 1, tier 2
- `raft.nbt`: tier 2
- `swordstatue.nbt`: tier 1, tier 3
- `waystop.nbt`: tier 1

### Major Restock Behavior

- Config-controlled.
- Default scan interval: `1200` ticks.
- Default restock interval: `36000` to `144000` ticks.
- Can announce restocks to all players.
- Clears inventory, sets loot table with a restock seed, marks inventory dirty, and updates listeners.

### Risks / Confusing Details

- `loot_table/containers/tier_*.json` are simple placeholder one-item tables and do not appear to be referenced by structure NBT.
- Major restock tracking depends on saved container positions and chunk loading.
- Minor POI containers are not restocked by the major restock system.

## Villager Trading Lock

### What It Does

Blocks interaction with villagers and wandering traders.

### Main Files

- `src/main/java/nrd/breached/Breached.java`

### Behavior

- Server-side `UseEntityCallback` returns `SUCCESS` for villager/trader interaction.
- Sends "Villager trading is disabled in Breached."
- Deduplicates repeated same-tick messages per player.

### Risks

- The dedupe map is static and not explicitly cleaned up.

## Server / Client Split Concerns

### Current State

- `fabric.mod.json` has `environment: "*"` and main/client/datagen entrypoints.
- Client initializer is empty.
- Client mixin config exists but has no client mixins.
- Custom crafting tables use vanilla crafting screen handling, so no custom client screen registration is present.
- Most gameplay systems use server-side Fabric callbacks or server-world checks.

### Risks

- If future UI/screens are added, the current split source-set structure will matter.
- Server-only assumptions should be preserved for multiplayer gameplay rules.

## Mixins

Declared in `src/main/resources/breached.mixins.json`:

- `AbstractFireBlockMixin`: cancels Nether portal creation from fire.
- `BlockBreakingDeltaMixin`: applies flat Breacher mining speed.
- `CraftingScreenHandlerMixin`: blocks insufficient-tier crafting outputs.
- `ExplosionBehaviorMixin`: protects protected structures from explosions.
- `FireChargeItemMixin`: blocks portal creation via fire charges.
- `FlintAndSteelItemMixin`: blocks portal creation via flint and steel.
- `ItemStackMixin`: overrides armor max damage.
- `ServerPlayerRespawnMixin`: applies bed respawn cooldown behavior.

Risks:

- `defaultRequire` is `1`, so changed Minecraft method signatures/injection points can hard-fail.
- These are tightly coupled to current Yarn/Minecraft method names.

## Event Listeners / Callbacks

Registered callbacks include:

- `CommandRegistrationCallback`: team commands.
- `EntitySleepEvents.ALLOW_RESETTING_TIME`: prevent night skipping.
- `ServerPlayerEvents.AFTER_RESPAWN`: apply bed cooldown.
- `UseEntityCallback`: villager/trader lock.
- `PlayerBlockBreakEvents.BEFORE`: Landlock protection and protected structure protection.
- `UseBlockCallback`: Landlock placement protection, Landlock door protection, probe debug, protected structure placement protection.
- `ServerWorldEvents.LOAD`: borders and spawn.
- `ServerChunkEvents.CHUNK_LOAD`: planned structure enqueue and minor POI pending chunks.
- `ServerTickEvents.END_WORLD_TICK`: centralized structure placement, minor lifecycle, major restock.

`CentralSpawnPoiManager` defines additional callbacks but is not registered.

## Ticking Systems

### Structure Placement Tick

Runs every server world tick through `BreachedStructurePlacementManager.placePendingStructures`.

Responsibilities:

- Migrate legacy central spawn state.
- Reserve major planned structures.
- Force-load required structure chunks.
- Release completed forced chunks.
- Retire minor POIs.
- Try pending minor POI chunks.
- Try minor POIs near players.
- Restock major structure loot.
- Release expired restock chunks.
- Evaluate/place pending major structures.

### Minor POI Lifecycle Tick

Controlled by config:

- `lifecycleEnabled`
- `despawnScanIntervalTicks`
- `despawnChecksPerScan`
- `minDespawnIntervalTicks`
- `maxDespawnIntervalTicks`
- `despawnRetryIntervalTicks`
- `intactBlockRetirePercent`

Player-touched minor POIs are retired without removing blocks if enough structure blocks were replaced or a Landlock claim overlaps the footprint.

### Major Loot Restock Tick

Controlled by config:

- `enabled`
- `announceRestocks`
- `minRestockIntervalTicks`
- `maxRestockIntervalTicks`
- `scanIntervalTicks`

## Debug / Logging Behavior

### Probe Item

Sneak-use with `probe` on a non-Landlock block reports whether the block is inside any Landlock claim.

### Logging

Many systems log through `System.out.println` with `[Breached]` prefixes:

- Config creation/load/failure.
- Border and spawn application.
- Structure reservations, candidate accept/reject, placement, support generation.
- Forced chunk load/release.
- Minor POI placement and retirement.
- Major loot restock tracking/announcements.
- Legacy central spawn migration.

### Commands

Only team commands were found. No structure debug/admin commands were found.

## What Appears Complete

- Basic mod initialization and registration pipeline.
- Custom blocks/items/assets/lang/models/recipes for crafting benches and Breachers.
- Tiered crafting output gating.
- Basic Landlock claim ownership and block/placement/door protection.
- Breacher-based claimed-block raiding direction.
- Bed respawn cooldown and no-night-skip behavior.
- World border application for Breached presets.
- Centralized major/minor structure placement pipeline.
- Major structure loot table tracking and restocking.
- Structure protection for selected protected structures.
- Villager/wandering trader interaction lock.
- Basic persistent teams and team commands.

## What Appears Partially Implemented

- Controlled End access: future system, likely similar to official Nether portals.
- Team/Landlock integration: teams exist, but Landlock permissions are independent.
- Horace and pink tree structure protection: intended, but not currently in `PROTECTED_STRUCTURES`.
- Landlock anti-cheese: explosions, fluids, pistons, hoppers, and general block interactions are not comprehensively covered.
- Minor POI lifecycle is implemented but stateful/complex and needs runtime validation.
- Structure terrain generation is border-limited but not obviously custom island terrain.
- Client/datagen source sets are present but empty.

## What Appears Risky Or Fragile

- Claim lookup is brute-force block-entity scanning across full world height.
- Authorization counting only sees loaded chunks.
- Structure placement uses many static pending/forced sets plus persisted state; restart/chunk-order edge cases need testing.
- `CentralSpawnPoiManager` is old unused code and should be removed or formally deprecated after confirming no migration need remains.
- The official portal pre-placement check for existing active Nether portals is undesirable long term.
- Default config should treat all minor POIs consistently, including `abandonedhut` and `cadenboat`.
- Mixin injection points may break on Minecraft/Yarn updates.

## What I Should Test Before Calling This MVP Stable

- Create a new `breached:breached_island` world and confirm Overworld border `2500`, Nether border `1250`, and safe spawn.
- Create a new `breached:small_breached_island` world and confirm Overworld border `1000`, Nether border `500`.
- Confirm current End access behavior and decide what temporary behavior is acceptable until controlled End access is implemented.
- Confirm player-made Nether portals cannot be ignited in Overworld or Nether.
- Confirm official portal structures spawn, are protected, and can be used.
- Confirm all required major structures eventually place after chunk loading/restart.
- Confirm minor POIs spawn, respect budget/spacing, and retire/despawn safely.
- Confirm major loot containers populate and restock after the configured interval.
- Confirm Landlock break/place/door protection for owner, authorized player, unauthorized player, and Breacher user.
- Confirm Landlock right-click authorization behavior works as intended when the Landlock block is physically accessible.
- Confirm team membership does or does not grant claim access according to current desired design.
- Confirm Breacher mining speed, durability loss, drops, and blocked blocks.
- Confirm tiered crafting allows/blocks expected recipes on Tier 0-3 tables.
- Confirm beds set respawn, do not skip night, and enforce the 60-second cooldown.
- Confirm explosions do not damage protected structures.
- Confirm villager/wandering trader interaction stays blocked.

## Most Important Files To Understand First

1. `src/main/java/nrd/breached/Breached.java`
2. `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementManager.java`
3. `src/main/java/nrd/breached/worldgen/BreachedStructureDefinitions.java`
4. `src/main/java/nrd/breached/worldgen/BreachedDimensionRules.java`
5. `src/main/java/nrd/breached/worldgen/BreachedStructureSpawnManager.java`
6. `src/main/java/nrd/breached/worldgen/BreachedStructurePlacementState.java`
7. `src/main/java/nrd/breached/landlock/LandlockClaimManager.java`
8. `src/main/java/nrd/breached/block/LandlockBlock.java`
9. `src/main/java/nrd/breached/item/BreacherItem.java`
10. `src/main/java/nrd/breached/crafting/CraftingTierRules.java`
11. `src/main/java/nrd/breached/mixin/CraftingScreenHandlerMixin.java`
12. `src/main/java/nrd/breached/mixin/ServerPlayerRespawnMixin.java`
13. `src/main/java/nrd/breached/config/BreachedConfig.java`
14. `src/main/resources/data/breached/structure/*.nbt`
15. `src/main/resources/data/breached/loot_table/chests/*.json`

## Recommended Next Steps

These are focused on improving the current MVP structure without changing the overall direction.

1. Add Horace and pink tree to protected structure coverage.
2. Normalize minor POI config so `abandonedhut` and `cadenboat` are handled like the other minor structures.
3. Remove or replace the official Nether portal `NO_ACTIVE_NETHER_PORTAL_NEARBY` pre-placement shortcut.
4. Decide the future controlled End-access model and document the intended structure/portal equivalent before implementing it.
5. Remove or formally deprecate old central spawn POI code once legacy save migration is no longer needed.
6. Keep Breachers as the core raid path, then tune their speed/durability/recipes intentionally in one focused balance pass.
7. Improve structure placement observability with admin/debug commands for placement state, reservations, failed candidates, and protected radii.
8. Add focused multiplayer test coverage or manual test scripts for structure placement, restocks, Landlocks, Breachers, portals, and respawn cooldowns.
