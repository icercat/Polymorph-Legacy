# Recipe Conflict Scanner (Minecraft 1.7.10)

Small diagnostic companion mod for ATM0 / Polymorph-Legacy.

## Install (server only)
Replace exp2 with `RecipeConflictScanner-0.1.0-exp3.jar` in the dedicated Forge 1.7.10 server's
`mods` directory. Clients do not need this mod. Polymorph-Legacy is not required.
Uses Forge 10.13.4.1614 and Java 8 or a compatible GTNH runtime.
The mod declares `acceptableRemoteVersions = "*"` and performs scanning and command
registration only in server lifecycle events. Forge 1.7.10 has no `serverSideOnly`
member in `@Mod`. It registers no items, blocks, network channels, or client handlers.

## Build
From this directory, run `gradle --no-daemon clean build` with Gradle 8.11.1,
Java 21 for Gradle and a Java 8 toolchain. The GTNH settings convention is pinned
in `settings.gradle`. Install the normal reobfuscated jar from `build/libs`,
not the `-dev` or `-sources` jar.
The repository workflow `.github/workflows/recipe-conflict-scanner.yml` builds
this independent tool and uploads its jars as an Actions artifact.

## What it does
- Startup scanning is **disabled by default**. exp2 ran an entire scan inside the
  server startup event and could stall large packs. exp3 captures recipe data in
  small batches, uses a dedicated `RecipeConflictScanner-worker` thread to filter
  candidates, and verifies those candidates in small batches on server ticks.
  The startup event returns immediately.
- Expands vanilla shaped/shapeless and Forge OreDictionary shaped/shapeless recipes into representative crafting matrices.
- Groups matrices that map to two or more registered recipes.
- Writes `config/RecipeConflictScanner/conflicts.txt`.
- `/recipeconflicts` or `/recipeconflicts start` begins a scan (permission level 2).
- `/recipeconflicts status` shows generation/matching progress.
- `/recipeconflicts cancel` cancels between recipe calls and retains the previous report.
- Console progress is logged every five seconds; completion logs the report path.

Configuration: `config/RecipeConflictScanner/scanner.cfg` (restart to reload).
`autoScanOnStartup=false`, `tickBudgetMillis=2`, `maxUniqueMatrices=25000`.
Opting into automatic scanning delays it by 100 server ticks.

## Important limitation
Custom `IRecipe` implementations that do not derive from the four common recipe types are listed as unsupported. Conflicts involving only unsupported custom recipe classes may not be found.

Sampling is bounded to 24 alternatives per ingredient and 512 matrices per recipe.
This is a diagnostic sample, not an exhaustive proof that all recipes are conflict-free.
The worker receives only immutable numeric item/metadata snapshots. It never
touches a World, IRecipe, ItemStack, live registry, or OreDictionary list. A bounded
queue hands candidates back without blocking the server thread. Filtering applies
only to the four exact standard recipe classes; subclasses and custom recipes
always receive real matches() calls. Candidate filtering is conservative (ingredient
count and allowed item/metadata); real matches() still confirms every reported hit.
Cancellation and server shutdown interrupt the daemon worker, including queue waits.

Live matches() calls stay on the server thread because recipes may read the world. The per-tick
budget is soft: an individual third-party `matches()` call cannot be safely interrupted.
Calls taking over 50 ms or throwing an exception are skipped on subsequent matrices;
the report explicitly marks those omissions as INCOMPLETE. The matrix cap is also
reported as INCOMPLETE when hit. Invalid shapes and shapeless recipes larger than
the 3x3 grid are excluded from sample generation to bound permutation work.

The scanner intentionally does not modify crafting behavior.
