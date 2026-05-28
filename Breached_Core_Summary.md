# Breached Core Summary

This file explains the stable design philosophy of Breached.

Codex should use this file to understand the overall purpose of the mod, but exact current behavior should come from Breached_Roadmap.md.

Breached is a Rust-inspired Minecraft survival/PvP/raiding mod designed around a tight competitive gameplay loop: players loot, fight, breach, upgrade, and build stronger bases to protect what they have earned.

The mod creates a smaller, more controlled Minecraft world where progression is driven by custom loot, protected bases, limited world access, and intentional points of conflict. Instead of relying on normal Minecraft’s open-ended progression, Breached pushes players toward exploration, PvP, structure control, and base breaching as the main path to advancement.

The core gameplay loop is: Loot → PvP → Breach → Upgrade → Build Stronger Base → Repeat

Players collect resources and higher-tier loot from world structures, fight other players for control and advantage, breach enemy bases using custom breaching tools, and use their gains to progress into stronger gear, crafting tiers, and better defenses.

Core Design Pillars
1. Competitive Survival in a Small World
   Breached is designed for smaller servers where player interaction matters. The world border, structure placement, dimension access, and loot distribution are all intended to keep players close enough that exploration, conflict, and breaching naturally happen.

2. Structure-Based Progression
   Custom structures are central to progression. They provide loot, danger, map objectives, and natural hotspots for PvP. Major structures should spawn consistently and be spaced in a way that creates meaningful exploration and conflict without feeling random or unfair.

3. Tiered Loot and Crafting Progression
   Loot is organized into tiers so players gradually progress from basic gear to stronger equipment. Higher-tier crafting and gear should require players to explore, fight, breach, and control valuable resources rather than simply mining safely underground.

4. Landlock Base Protection & Reinforced Blocks Base Building
   Players protect bases using Landlock Blocks, which claim a limited 3D volume. These blocks make bases defensible but not permanently safe. Claims are meant to create breach targets while preventing casual griefing and random destruction. Reinforced blocks add important progression based base protection for strategic base building.

5. Pickaxe-Based Breaching
   Breaching is centered around custom pickaxe-like tools called Breachers. Breachers are the singular method of breaking into protected bases. This keeps breaching simple, understandable, and tied directly to the name and theme of the mod: Breached.

6. Controlled Dimension Access
   The Nether and End should not be freely accessible through normal, unrestricted player behavior. Instead, access to major dimensions should be controlled through intentional systems such as naturally generated portal structures, restricted entry points, and eventually limited windows of time when certain dimensions can be entered. This turns dimension access into a shared objective and prevents players from bypassing the intended world structure.

7. Respawn and Recovery
   Beds function as respawn points rather than night-skipping tools. The goal is to support PvP and raiding while still giving players a way to recover after death or breaches, so losing a fight or getting breached does not feel like the end of the game.

9. Short-Term Server Seasons
   Breached is intended to work especially well for shorter competitive seasons. The game should reward active players without letting early grinders become impossible to catch. The best version of the mod keeps players engaged over a limited timeframe without making casual players feel permanently behind.

Primary Aim of Breached
The goal of Breached is to turn Minecraft into a focused survival-raiding experience where every major system supports the same loop, while maintining it's identity as minecraft: players venture out for loot, encounter other players, fight or escape, breach enemy bases, upgrade their own position, and prepare for the next conflict.

Every design element should be judged by whether it strengthens that loop. If a feature does not improve looting, PvP, breaching, progression, recovery, or base-building, it should be simplified, postponed, or removed. If a feature makes minecraft stop feeling like minecraft, it should be simplified, postponed, or removed.