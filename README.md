# Minecraft, But Every Mob You Kill Joins Your Army

Minecraft **26.2 Fabric** multiplayer mod for a three-player YouTube challenge.

## Rules
- Killing a normal or modded `Mob` with a player-attributed attack awards **one army spawn egg**. Existing mutant entities are identified by their exact registry type.
- Place your army egg to spawn a named, persistent mob: **purple**, **yellow**, or **red** player name plus `'s Skeleton` (or that mob's real type).
- Your mobs attack enemy players **and mobs owned by other players**, not your own army or owner. Passive animals can fight using fallback damage.
- Targeting has no *search radius* among already-loaded army mobs and online players in the same dimension. Distant targets can trigger teleport-assisted pursuit.
- **Emerald sabotage:** sneak + right-click an enemy army mob with emerald. 50% successful conversion, one emerald per attempt, 30-second cooldown. Both the attacker and affected owner receive **private** messages reporting the attempted mob and result.
- **PvP kill sabotage:** 100% chance to steal one random marked army egg if the defeated player has one; victim and killer receive **private** messages saying precisely which egg was stolen. The stolen egg belongs to the killer.

## Optional Mutant Monsters compatibility
Mutant Monsters is **not packaged or required**. Install a compatible **26.2 Fabric** build of Mutant Monsters and its own required dependencies alongside this mod. Modded mob eggs preserve the real registered entity type, including mutants, rather than spawning their placeholder carrier egg. Compatibility is best-effort: modded attacks and custom AI might need additional testing.

## Build and download JAR
1. Open **Actions** on this GitHub repository.
2. Open **Build Fabric 26.2 Mod** and choose the latest successful run.
3. Download **mob-army-26.2-jar** from **Artifacts**.
4. Extract its normal `.jar` and install alongside Fabric API.

The workflow uses Java 25, Gradle 9.7.1 and Fabric Loom.

**Limitations:** Unloaded chunks do not tick Minecraft mob AI. Cross-dimension chasing is not implemented. Entity types not derived from `Mob` are not supported. The source is not yet confirmed compile-tested or multiplayer play-tested.
