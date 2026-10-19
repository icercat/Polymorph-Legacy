package dev.frostey.polymorphlegacy.recipe;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import dev.frostey.polymorphlegacy.server.SelectionManager;

/**
 * AE2-only proxy. This is intentionally NOT registered in CraftingManager.
 * It is injected only into AE2's private lastUsedRecipe cache so ordinary
 * crafting lookups cannot be affected by an AE terminal selection.
 */
public final class AE2SelectionProxyRecipe implements IRecipe {
    public static final AE2SelectionProxyRecipe INSTANCE = new AE2SelectionProxyRecipe();

    private final ThreadLocal<IRecipe> resolved = new ThreadLocal<IRecipe>();

    private AE2SelectionProxyRecipe() {}

    @Override
    public boolean matches(InventoryCrafting matrix, World world) {
        IRecipe recipe = SelectionManager.INSTANCE.getSelectedAE2Recipe(matrix, world);
        if (recipe == null) {
            resolved.remove();
            return false;
        }
        resolved.set(recipe);
        return true;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting matrix) {
        IRecipe recipe = resolved.get();
        if (recipe == null) {
            recipe = SelectionManager.INSTANCE.getSelectedAE2Recipe(matrix, null);
        }
        resolved.remove();
        ItemStack result = RecipeResolver.safeResult(recipe, matrix);
        return result == null ? null : result.copy();
    }

    @Override
    public int getRecipeSize() {
        return 9;
    }

    @Override
    public ItemStack getRecipeOutput() {
        return null;
    }
}
