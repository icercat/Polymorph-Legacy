package dev.frostey.polymorphlegacy.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

public final class RecipeResolver {
    private RecipeResolver() {}

    public static List<IRecipe> findMatches(InventoryCrafting matrix, World world) {
        if (matrix == null || world == null) {
            return Collections.emptyList();
        }

        List<IRecipe> matches = new ArrayList<IRecipe>();
        for (Object object : CraftingManager.getInstance().getRecipeList()) {
            if (!(object instanceof IRecipe)) {
                continue;
            }
            IRecipe recipe = (IRecipe) object;
            if (safeMatches(recipe, matrix, world)) {
                ItemStack result = safeResult(recipe, matrix);
                if (result != null) {
                    matches.add(recipe);
                }
            }
        }
        return matches;
    }

    public static IRecipe findMatch(InventoryCrafting matrix, World world, int index) {
        if (index < 0 || matrix == null || world == null) {
            return null;
        }
        int current = 0;
        for (Object object : CraftingManager.getInstance().getRecipeList()) {
            if (!(object instanceof IRecipe)) {
                continue;
            }
            IRecipe recipe = (IRecipe) object;
            if (safeMatches(recipe, matrix, world) && safeResult(recipe, matrix) != null) {
                if (current == index) {
                    return recipe;
                }
                current++;
            }
        }
        return null;
    }

    public static ItemStack safeResult(IRecipe recipe, InventoryCrafting matrix) {
        if (recipe == null || matrix == null) {
            return null;
        }
        try {
            return recipe.getCraftingResult(matrix);
        } catch (RuntimeException ignored) {
            return null;
        } catch (LinkageError ignored) {
            return null;
        }
    }

    public static boolean safeMatches(IRecipe recipe, InventoryCrafting matrix, World world) {
        if (recipe == null || matrix == null || world == null) {
            return false;
        }
        try {
            return recipe.matches(matrix, world);
        } catch (RuntimeException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    public static int fingerprint(InventoryCrafting matrix) {
        if (matrix == null) {
            return 0;
        }

        int hash = 1;
        for (int i = 0; i < matrix.getSizeInventory(); i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            hash = 31 * hash + i;
            if (stack == null) {
                hash = 31 * hash;
                continue;
            }
            hash = 31 * hash + Item.getIdFromItem(stack.getItem());
            hash = 31 * hash + stack.getItemDamage();
            hash = 31 * hash + (stack.getTagCompound() == null ? 0 : stack.getTagCompound().hashCode());
        }
        return hash;
    }
}
