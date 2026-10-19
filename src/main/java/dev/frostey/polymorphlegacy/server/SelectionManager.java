package dev.frostey.polymorphlegacy.server;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import dev.frostey.polymorphlegacy.recipe.CraftingAccess;
import dev.frostey.polymorphlegacy.recipe.RecipeResolver;

public final class SelectionManager {
    public static final SelectionManager INSTANCE = new SelectionManager();

    private final Map<EntityPlayerMP, Selection> selections =
            new WeakHashMap<EntityPlayerMP, Selection>();

    private final Map<InventoryCrafting, Selection> matrixSelections =
            new WeakHashMap<InventoryCrafting, Selection>();

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

    private synchronized void select(EntityPlayerMP player, int windowId, int recipeIndex) {
        if (player == null || recipeIndex < 0) {
            return;
        }

        Container container = player.openContainer;
        if (container == null || container.windowId != windowId) {
            clear(player);
            return;
        }

        InventoryCrafting matrix = CraftingAccess.findMatrix(container);
        SlotCrafting output = CraftingAccess.findResultSlot(container);
        if (matrix == null || output == null) {
            clear(player);
            return;
        }

        IRecipe recipe = RecipeResolver.findMatch(matrix, player.worldObj, recipeIndex);
        if (recipe == null) {
            return;
        }

        Selection previous = selections.get(player);
        if (previous != null) {
            matrixSelections.remove(previous.matrix);
        }

        Selection selection = new Selection(
                windowId,
                recipeIndex,
                RecipeResolver.fingerprint(matrix),
                matrix,
                recipe);
        selections.put(player, selection);
        matrixSelections.put(matrix, selection);

        container.onCraftMatrixChanged(matrix);
        container.detectAndSendChanges();
    }

    public synchronized IRecipe getSelectedRecipe(InventoryCrafting matrix, World world) {
        if (matrix == null) {
            return null;
        }

        Selection selection = matrixSelections.get(matrix);
        if (selection == null) {
            return null;
        }

        if (RecipeResolver.fingerprint(matrix) != selection.fingerprint) {
            matrixSelections.remove(matrix);
            return null;
        }

        if (world != null && !RecipeResolver.safeMatches(selection.recipe, matrix, world)) {
            matrixSelections.remove(matrix);
            return null;
        }

        return selection.recipe;
    }

    public synchronized void apply(EntityPlayerMP player) {
        if (player == null) {
            return;
        }

        Selection selection = selections.get(player);
        if (selection == null) {
            return;
        }

        Container container = player.openContainer;
        if (container == null || container.windowId != selection.windowId) {
            clear(player);
            return;
        }

        InventoryCrafting matrix = CraftingAccess.findMatrix(container);
        if (matrix == null || matrix != selection.matrix
                || RecipeResolver.fingerprint(matrix) != selection.fingerprint
                || !RecipeResolver.safeMatches(selection.recipe, matrix, player.worldObj)) {
            clear(player);
        }
    }

    private void clear(EntityPlayerMP player) {
        Selection removed = selections.remove(player);
        if (removed != null) {
            matrixSelections.remove(removed.matrix);
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
        final InventoryCrafting matrix;
        final IRecipe recipe;

        Selection(int windowId, int recipeIndex, int fingerprint, InventoryCrafting matrix, IRecipe recipe) {
            this.windowId = windowId;
            this.recipeIndex = recipeIndex;
            this.fingerprint = fingerprint;
            this.matrix = matrix;
            this.recipe = recipe;
        }
    }
}
