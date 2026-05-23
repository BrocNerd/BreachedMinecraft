# Breached Mod Instructions

This is a Fabric Minecraft mod with mod ID `breached`.

The main Java package is `nrd.breachd`.

Long-term concept: convert Minecraft progression and the game loop to feel more like Rust while keeping most Minecraft mechanics intact. See `PROJECT_PLAN.md` for the design plan.

## Coding rules

- Make small, focused changes.
- Do not refactor unrelated files.
- Do not implement future phases unless specifically asked.
- Prefer simple working implementations before advanced architecture.
- After every task, list every changed file and explain what changed.
- If unsure about the Minecraft/Fabric version API, inspect the existing Gradle files and source code before editing.
- For Phase 1, only add simple custom content and verify the mod pipeline works.