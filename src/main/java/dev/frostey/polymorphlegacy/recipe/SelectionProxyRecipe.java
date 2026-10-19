package dev.frostey.polymorphlegacy.recipe;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import dev.frostey.polymorphlegacy.server.SelectionManager;

public final class SelectionProxyRecipe implements IRecipe {
    public static final SelectionProxyRecipe INSTANCE = new SelectionProxyRecipe();

    private SelectionProxyRecipe() {}

    @Override
    public boolean matches(InventoryCrafting matrix, World world) {
        return SelectionManager.INSTANCE.getSelectedRecipe(matrix, world) != null;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting matrix) {
        IRecipe selected = SelectionManager.INSTANCE.getSelectedRecipe(matrix, null);
        ItemStack result = RecipeResolver.safeResult(selected, matrix);
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
