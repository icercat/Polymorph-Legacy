# Recipe Conflict Scanner (Minecraft 1.7.10)

Small diagnostic companion mod for ATM0 / Polymorph-Legacy.

## What it does
- Scans registered crafting recipes after the server has started.
- Expands vanilla shaped/shapeless and Forge OreDictionary shaped/shapeless recipes into representative crafting matrices.
- Groups matrices that map to two or more registered recipes.
- Writes `config/RecipeConflictScanner/conflicts.txt`.
- Adds `/recipeconflicts` (permission level 2) to rescan on demand.

## Important limitation
Custom `IRecipe` implementations that do not derive from the four common recipe types are listed as unsupported. Conflicts involving only unsupported custom recipe classes may not be found.

The scanner intentionally does not modify crafting behavior.
