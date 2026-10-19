# ATM0 Polymorph Legacy — 1.7.10 prototype

A Forge 1.7.10 prototype that replaces the old cycle-only recipe-conflict UX with a Polymorph-style chooser.

## V0.1 behavior

- Detects all `IRecipe`s whose `matches(...)` returns true for the current `InventoryCrafting`.
- Shows a small numbered button next to the normal `SlotCrafting` only when 2+ recipes conflict.
- Clicking it opens a list of candidate output items.
- Clicking an output sends only `(windowId, recipeIndex)` to the server.
- The server re-validates the current container, matrix and recipe before changing the output slot.
- The selected recipe is maintained server-side while ingredient item identities/NBT stay the same; stack counts are deliberately ignored so repeated crafts keep the selection.
- Container discovery first scans Slots for `InventoryCrafting`, then falls back to private-field reflection. This is intended to cover vanilla, Tinkers' Crafting Station, Natura tables and many other conventional 1.7.10 crafting containers without hard dependencies.

## Current prototype limitations

1. The popup exposes the first 16 conflicting outputs. More than 16 is rare, but paging/scrolling is TODO.
2. Non-default shift-click/bulk crafting is not specially intercepted yet. Normal click crafting is the target for V0.1. A later build should hook/reimplement bulk transfer so a selected recipe cannot briefly fall back to recipe 0 inside the same server transaction.
3. Exotic crafting systems that do not use both `InventoryCrafting` and `SlotCrafting` need adapters.
4. Furnace/smelting conflicts and NEI recipe-request integration are not implemented yet.

## Build

This project uses the modern GTNH 1.7.10 Gradle convention (RetroFuturaGradle) with Forge `10.13.4.1614` and Java 8 target bytecode.

The source tree includes Gradle configuration, but this prototype archive intentionally does not contain a binary `gradle-wrapper.jar`. Copy/generate a Gradle 8.11.1 wrapper, then run:

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

The output jar should be under `build/libs/`.

## Install test

Remove/disable `NoMoreRecipeConflict-1.7.10-0.3.2.jar` while testing to avoid two mods fighting over the same result slot/UI.

Put the built `ATM0-Polymorph-Legacy-0.1.6-exp6-ae1.jar` in both client and server `mods/` folders.

## Next target

For V0.2:

- server-safe shift-click/bulk crafting
- better popup placement / paging
- hover tooltips
- selected-result persistence keyed by conflict set rather than only current matrix
- NEI integration


## AE2 experimental support

Version `0.1.6-exp6-ae1` adds an experimental adapter for GTNH AE2 crafting terminals.

- Detects AE2 `SlotCraftingMatrix` / `SlotCraftingTerm` by runtime class hierarchy without a hard AE2 dependency.
- Mirrors the 3x3 AE crafting grid into a temporary `InventoryCrafting` for conflict discovery.
- Keeps the normal global proxy recipe restricted to conventional crafting matrices.
- For AE2 actual crafting, temporarily primes `appeng.util.Platform.lastUsedRecipe` with an AE-only proxy that delegates `matches` and `getCraftingResult` to the selected real recipe.
- Re-applies the selected output to the AE result slot server-side so the terminal preview matches the recipe that AE2 will craft.
- Ambiguous simultaneous AE selections return no override rather than deliberately choosing another player's recipe.

This is an experimental compatibility layer for the GTNH AE2 rv3 line. If a future AE2 build removes or renames `Platform.lastUsedRecipe`, the compatibility layer will need a direct adapter/transformer.
