# Recipe Conflict Scanner (Minecraft 1.7.10)

Small diagnostic companion mod for ATM0 / Polymorph-Legacy.

## Install (server only)
Put `RecipeConflictScanner-0.1.0-exp2.jar` in the dedicated Forge 1.7.10 server's
`mods` directory. Clients do not need this mod. Polymorph-Legacy is not required.
Uses Forge 10.13.4.1614 and Java 8 or a compatible GTNH runtime.
The mod declares `serverSideOnly = true` and `acceptableRemoteVersions = "*"`;
it registers no items, blocks, network channels, or client handlers.

## Build
From this directory, run `gradle --no-daemon clean build` with Gradle 8.11.1,
Java 21 for Gradle and a Java 8 toolchain. The GTNH settings convention is pinned
in `settings.gradle`. Install the normal reobfuscated jar from `build/libs`,
not the `-dev` or `-sources` jar.
The repository workflow `.github/workflows/recipe-conflict-scanner.yml` builds
this independent tool and uploads its jars as an Actions artifact.

## What it does
- Scans registered crafting recipes after the server has started.
- Expands vanilla shaped/shapeless and Forge OreDictionary shaped/shapeless recipes into representative crafting matrices.
- Groups matrices that map to two or more registered recipes.
- Writes `config/RecipeConflictScanner/conflicts.txt`.
- Adds `/recipeconflicts` (permission level 2) to rescan on demand.

## Important limitation
Custom `IRecipe` implementations that do not derive from the four common recipe types are listed as unsupported. Conflicts involving only unsupported custom recipe classes may not be found.

Sampling is bounded to 24 alternatives per ingredient and 512 matrices per recipe.
This is a diagnostic sample, not an exhaustive proof that all recipes are conflict-free.
Scanning runs synchronously at startup or on command and may take time in large packs.

The scanner intentionally does not modify crafting behavior.
