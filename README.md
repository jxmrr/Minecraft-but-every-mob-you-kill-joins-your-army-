# Minecraft, But Every Mob You Kill Joins Your Army

Minecraft **26.2 Fabric** multiplayer mod for a three-player YouTube challenge.

## Rules
- Killing a normal or modded `Mob` with a player-attributed attack awards **one army spawn egg**. Existing mutant entities are identified by their exact registry type.
- Place your army egg to spawn a named, persistent mob: **purple**, **yellow**, or **red** player name plus `'s Skeleton` (or that mob's real type).
- Your army is **PASSIVE by default and follows its leader on foot** (no teleporting). When farther than about 7 blocks it follows; within about 3.5 blocks it stops. If its owner takes damage from a wild mob or enemy team, the passive army automatically defends its leader for 20 seconds after the latest hit, engaging that wild aggressor or the attacking player's entire team. Afterwards it resumes following. Manual Target Mode takes priority over defense.
- Right-click an enemy player with an **empty main hand** to activate manual Target Mode for that player's entire team (the player **and every deployed mob they own**). Right-click the same player again to turn it off, or click a different player to switch enemy teams.
- For distant targeting, aim at the other player and right-click with an empty main hand, up to **8 chunks (128 blocks)** away, with an unobstructed view.
- Target mode affects ONLY the selected player's team. The third team and your own team are safe from intentional army attacks. Passive animals gain a fallback attack while targeting.
- Army mobs never teleport towards enemies, and spawning eggs stay where the egg was placed. Armies pursue only eligible loaded targets within 8 chunks in the same dimension.
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
