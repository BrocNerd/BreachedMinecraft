Breached - Rust in Minecraft

Core concept - convert minecraft progression and game loop to be comparable to rust, while leaving the other aspects largely the same. This will include making items much more scarce. Small play area with expectation of 1-2 week long wipe.

Balanced around a 30 player count server, with 6-10 teams and 1-2 week long wipe.


Tiered Crafting System/Updated Crafting Recipes:

Higher Tier tables can craft all recipes from the lower tiers before them.


Armor Durability
leather/gold/chain
150-250
iron
300
diamond
400
netherite
500


Tier 0 - Regular crafting table. Can only craft minimal progression items, wood, stone, and copper tools.

Tier 1 - Iron. Updated crafting recipe to 8 iron blocks around crafting table. Bow unlocked.

Tier 2 - Diamond. Updated crafting recipe to 4 diamond blocks and 4 redstone blocks around tier 1 crafting table. Enchantment table unlocked. Brewing Stand unlocked.

Tier 3 - Netherite. Updated recipe to 1 beacon 5 diamond blocks and 1 netherite block and 1 tier 2 crafting table. Bookshelves unlocked.


Landlock Block to claim chunks:

Phase 6 — Breachers

Goal:
Add special raid tools called Breachers that allow unauthorized players to break blocks inside Landlock claims.

Core Rules:
- Breachers are not normal pickaxes.
- Breachers can break any block anywhere, including outside claims.
- Breachers break all blocks very slowly, roughly 15 seconds per block.
- Breachers are the only tools unauthorized players can use to break blocks inside enemy Landlock claims.
- Breachers can break all claimed blocks, including Landlock blocks.
- Breachers lose 1 durability every time they successfully break any block.
- Breachers lose durability whether the block is claimed or unclaimed.
- Breachers lose durability whether the player is authorized or unauthorized.
- If an unauthorized player tries to break a claimed block without a Breacher, the break is denied by Landlock protection.
- Normal tools still work normally outside claims.
- Normal tools still work normally inside claims where the player is authorized.
- Breachers should not be enchantable or repairable unless added intentionally later.

Tiers:

Iron Breacher
- Recipe: 2 iron blocks + 1 diamond block
- Durability: 1 block break
- Purpose: Extremely expensive emergency/surgical breach tool.

Diamond Breacher
- Recipe: 3 diamond blocks
- Durability: 5 block breaks
- Purpose: Mid-tier raid tool for small breaches or weak bases.

Netherite Breacher
- Recipe: 1 netherite block + 4 diamond blocks
- Durability: 20 block breaks
- Purpose: High-tier raid tool for serious raids.

Balance Notes:
- Because Breachers can be crafted in multiples, durability is intentionally low and recipes are intentionally expensive.
- Using a Breacher outside a claim is allowed, but wasteful.
- Every block broken with a Breacher consumes one use, making each breach decision costly.
- A 15-second break time means:
    - Iron Breacher = 15 seconds of breach time
    - Diamond Breacher = 75 seconds of breach time
    - Netherite Breacher = 5 minutes of breach time


Teams:

Team creation in coordination with Landlock Block and claimed chunks.
Teammates can still damage each other.
Only teammates can break blocks within Landlock claims..
Team chat and Team list commands. Team admin/owner with permission to add/remove teammates.

Revamped Loot Tables:
Loot spawns are now capped at Tier 2 workbench levels. (if people find enough enchanted books to anvil them up, so be it. Thats a good alternative way to gear up.)

Loot Locations:
Include dungeons and taverns with updated loot pools to match.
Ensure Major structures spawn including:
Ancient City
Nether Fortress (Increase whither skeleton spawn rate by 2x and increase wither skeleton skull drop rate by 2x)
Nether Bastion
Ocean Monument

World Border:
Make world an island that is always around 2000x2000 blocks for compact/small teams. (total map size 2500x2500 - allows for ocean builds and increased ore spawns in ocean.)
Nether border is divided by 8. So 312x312. Makes potions/nether very hard. Make only two nether entrances somewhere random on map with 32x32 block unclaimable area and remove individual nether portal creation.
No End. (For now)

Respawn:

Beds function as respawn points only.
Beds do not skip night.
Each player may have multiple beds, but each bed has a 60-second cooldown.
Beds cannot be placed inside enemy claims.
Beds cannot be placed within 32 blocks of another bed from the same team/player.
Enemy players can destroy beds if physically reached.
Beds inside your team claim are protected like other blocks.
Beds outside claims are vulnerable.

Anti-Cheese:

No Beds inside other teams claim.
Villager Trading severely nerfed (max loot is tier 1).

Rust in Minecraft Development Phases

Phase 1 — Mod Setup
Goal: Get the Fabric mod working and confirm I can add custom content.
Notes:


Phase 2 — Tiered Crafting System
Goal: Add Tier 1, Tier 2, and Tier 3 crafting tables and begin gating recipes by table tier.
Notes:


Phase 3 — Armor Durability
Goal: Adjust armor durability so armor fits the Rust-style progression and scarcity system.
Notes:


Phase 4 — Team System
Goal: Create teams, team commands, team ownership, team chat, and team permissions.
Notes:


Phase 5 — Landlock Claim System
Goal: Add Landlock block that claim land, protect blocks, manage ownership, and enforce TC limits.
Notes:


Phase 6 — Breacher Tool System
Goal: Add breach tools that allow raiding through claimed blocks while normal tools cannot.
Notes:


Phase 7 — Respawn / Bed System
Goal: Make beds work like Rust sleeping bags: respawn-only, cooldown-based, limited by spacing and claims.
Notes:


Phase 8 — Loot and Villager Balance
Goal: Cap loot and villager progression so players cannot skip the tiered crafting system.
Notes:


Phase 9 — Structures and Exploration
Goal: Add dungeons, taverns, and major structures with balanced loot and guaranteed availability.
Notes:


Phase 10 — World Border and Island Map
Goal: Create a compact island-style map with a limited world border for 1–2 week wipes.
Notes:


Phase 11 — Nether Rules
Goal: Restrict Nether access to fixed entrances, remove player-made portals, and prevent entrance claiming.
Notes:


Phase 12 — End Rules
Goal: Disable the End for now.
Notes:


Phase 13 — Anti-Cheese Rules
Goal: Prevent players from bypassing claim protection using pistons, hoppers, fluids, explosions, portals, or teleporting.
Notes:


Phase 14 — Playtesting and Balance
Goal: Balance the modpack around 30 players, 6–10 teams, scarce items, raiding, and 1–2 week wipes.
Notes:




