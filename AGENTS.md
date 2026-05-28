# AGENTS.md

This is a Fabric Minecraft mod with mod ID breached.

The main Java package is nrd.breached.

Project Context Files

Before making design or implementation decisions:

Breached_Core_Summary.md
Breached_Roadmap.md

Use them in this priority order:

Breached_Core_Summary.md explains the stable design philosophy and overall purpose of the mod.
Breached_Roadmap.md is the current source of truth for exact intended behavior, implementation status, priorities, and open issues.

If Breached_Roadmap.md conflicts with Breached_Core_Summary.md, follow Breached_Roadmap.md for exact behavior, but preserve the design philosophy from Breached_Core_Summary.md where possible.

Current Development Priority

The current priority is to balance and complete core systems that support the main gameplay loop before deeper polish or future expansion.

Core systems should generally be prioritized in this order:

Landlock Blocks
Breachers / Reinforcement
Structures and Loot Balancing
Beds / Respawn
World Border / Dimensions
Teams and quality-of-life systems
Polish, visual feedback, and future expansion

Do not implement future phases unless specifically asked.

Coding Rules
Make small, focused changes.
Do not refactor unrelated files.
Do not implement future phases unless specifically asked.
Prefer simple working implementations before advanced architecture.
Preserve existing working behavior unless the requested change specifically replaces it.
If a requested change conflicts with the current roadmap, ask for clarification before implementing.
After every task, list every changed file and explain what changed.
If unsure about the Minecraft/Fabric version API, inspect the existing Gradle files and source code before editing.
If unsure how a system currently works, inspect the relevant source files before making changes.
Avoid assumptions based only on file names; verify behavior in code.
Design Rules

Every major change should support the Breached gameplay loop:

Loot → PvP → Raid → Upgrade → Build Stronger Base → Repeat

Features should be simplified, postponed, or removed if they do not clearly improve at least one of the following:

Looting
PvP
Raiding
Progression
Recovery after death or raids
Base-building
Server-season pacing

Features should also be simplified, postponed, or removed if they make the mod stop feeling like Minecraft.

Implementation Style

Prefer clear, maintainable code over clever abstractions.

When adding new systems:

Start with the simplest working version.
Keep server-authoritative game logic on the server.
Avoid unnecessary client syncing.
Avoid ticking or scanning large world areas unless absolutely necessary.
Be especially careful with worldgen, structure placement, and recurring server tick logic because these can create major lag.
For performance-sensitive systems, prefer event-driven or targeted checks over broad repeated scans.
Reporting Format After Each Task

After completing a task, report:

Summary of what changed.
Every changed file.
Important implementation details.
Any known limitations or follow-up work.
Whether the implementation matches Breached_Roadmap.md.