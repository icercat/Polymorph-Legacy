package dev.frostey.polymorphlegacy.server;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import dev.frostey.polymorphlegacy.recipe.CraftingAccess;
import dev.frostey.polymorphlegacy.recipe.RecipeResolver;

public final class SelectionManager {
    public static final SelectionManager INSTANCE = new SelectionManager();

    private final Map<EntityPlayerMP, Selection> selections =
            new WeakHashMap<EntityPlayerMP, Selection>();

    private final Map<EntityPlayerMP, PendingSelection> pendingSelections =
            new WeakHashMap<EntityPlayerMP, PendingSelection>();

    private SelectionManager() {}

    public synchronized void enqueue(EntityPlayerMP player, int windowId, int recipeIndex) {
        if (player != null && recipeIndex >= 0) {
            pendingSelections.put(player, new PendingSelection(windowId, recipeIndex));
        }
    }

    public void drainPending(EntityPlayerMP player) {
        PendingSelection pending;
        synchronized (this) {
            pending = pendingSelections.remove(player);
        }
        if (pending != null) {
            select(player, pending.windowId, pending.recipeIndex);
        }
    }

    private void select(EntityPlayerMP player, int windowId, int recipeIndex) {
        if (player == null || recipeIndex < 0) {
            return;
        }

        Container container = player.openContainer;
        if (container == null || container.windowId != windowId) {
            return;
        }

        InventoryCrafting matrix = CraftingAccess.findMatrix(container);
        SlotCrafting output = CraftingAccess.findResultSlot(container);
        if (matrix == null || output == null) {
            selections.remove(player);
            return;
        }

        IRecipe recipe = RecipeResolver.findMatch(matrix, player.worldObj, recipeIndex);
        if (recipe == null) {
            return;
        }

        selections.put(player, new Selection(windowId, recipeIndex, RecipeResolver.fingerprint(matrix), recipe));
        apply(player);
    }

    public void apply(EntityPlayerMP player) {
        Selection selection = selections.get(player);
        if (selection == null || player == null) {
            return;
        }

        Container container = player.openContainer;
        if (container == null || container.windowId != selection.windowId) {
            selections.remove(player);
            return;
        }

        InventoryCrafting matrix = CraftingAccess.findMatrix(container);
        SlotCrafting output = CraftingAccess.findResultSlot(container);
        if (matrix == null || output == null) {
            selections.remove(player);
            return;
        }

        int fingerprint = RecipeResolver.fingerprint(matrix);
        if (fingerprint != selection.fingerprint) {
            selections.remove(player);
            return;
        }

        IRecipe recipe = selection.recipe;
        if (recipe == null || !RecipeResolver.safeMatches(recipe, matrix, player.worldObj)) {
            selections.remove(player);
            return;
        }

        ItemStack expected = RecipeResolver.safeResult(recipe, matrix);
        if (expected == null) {
            selections.remove(player);
            return;
        }

        if (!ItemStack.areItemStacksEqual(output.getStack(), expected)) {
            output.putStack(expected.copy());
            container.detectAndSendChanges();
        }
    }

    private static final class PendingSelection {
        final int windowId;
        final int recipeIndex;

        PendingSelection(int windowId, int recipeIndex) {
            this.windowId = windowId;
            this.recipeIndex = recipeIndex;
        }
    }

    private static final class Selection {
        final int windowId;
        final int recipeIndex;
        final int fingerprint;
        final IRecipe recipe;

        Selection(int windowId, int recipeIndex, int fingerprint, IRecipe recipe) {
            this.windowId = windowId;
            this.recipeIndex = recipeIndex;
            this.fingerprint = fingerprint;
            this.recipe = recipe;
        }
    }
}
