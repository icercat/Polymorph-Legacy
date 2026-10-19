package dev.frostey.polymorphlegacy.server;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import dev.frostey.polymorphlegacy.recipe.AE2SelectionProxyRecipe;
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

    private boolean aePlatformChecked;
    private Field aeLastUsedRecipe;

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
        Slot output = CraftingAccess.findActualResultSlot(container);
        if (matrix == null || output == null) {
            clear(player);
            return;
        }

        IRecipe recipe = RecipeResolver.findMatch(matrix, player.worldObj, recipeIndex);
        if (recipe == null) {
            return;
        }

        Selection previous = selections.get(player);
        if (previous != null && previous.matrix != null) {
            matrixSelections.remove(previous.matrix);
        }

        boolean ae2 = CraftingAccess.isAE2CraftingContainer(container);
        Selection selection = new Selection(
                windowId,
                recipeIndex,
                RecipeResolver.fingerprint(matrix),
                ae2 ? null : matrix,
                recipe,
                ae2);
        selections.put(player, selection);
        if (!ae2) {
            matrixSelections.put(matrix, selection);
        } else {
            primeAE2RecipeCache();
        }

        container.onCraftMatrixChanged(matrix);
        if (ae2) {
            applyAE2Output(player, container, matrix, selection);
        }
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

    public synchronized IRecipe getSelectedAE2Recipe(InventoryCrafting matrix, World world) {
        if (matrix == null) {
            return null;
        }

        int fingerprint = RecipeResolver.fingerprint(matrix);
        IRecipe strong = null;
        boolean strongAmbiguous = false;
        IRecipe weak = null;
        boolean weakAmbiguous = false;

        for (Map.Entry<EntityPlayerMP, Selection> entry : selections.entrySet()) {
            EntityPlayerMP player = entry.getKey();
            Selection selection = entry.getValue();
            if (player == null || selection == null || !selection.ae2) {
                continue;
            }
            if (world != null && player.worldObj != world) {
                continue;
            }
            Container open = player.openContainer;
            if (open == null || open.windowId != selection.windowId
                    || !CraftingAccess.isAE2CraftingContainer(open)) {
                continue;
            }
            if (!RecipeResolver.safeMatches(selection.recipe, matrix,
                    world != null ? world : player.worldObj)) {
                continue;
            }

            if (selection.fingerprint == fingerprint) {
                if (strong == null) {
                    strong = selection.recipe;
                } else if (strong != selection.recipe) {
                    strongAmbiguous = true;
                }
            } else {
                if (weak == null) {
                    weak = selection.recipe;
                } else if (weak != selection.recipe) {
                    weakAmbiguous = true;
                }
            }
        }

        if (strong != null && !strongAmbiguous) {
            return strong;
        }
        if (strong == null && weak != null && !weakAmbiguous) {
            return weak;
        }
        return null;
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
        if (matrix == null
                || RecipeResolver.fingerprint(matrix) != selection.fingerprint
                || !RecipeResolver.safeMatches(selection.recipe, matrix, player.worldObj)) {
            clear(player);
            return;
        }

        if (selection.ae2) {
            if (!CraftingAccess.isAE2CraftingContainer(container)) {
                clear(player);
                return;
            }
            primeAE2RecipeCache();
            applyAE2Output(player, container, matrix, selection);
        } else if (matrix != selection.matrix) {
            clear(player);
        }
    }

    private void applyAE2Output(EntityPlayerMP player, Container container,
            InventoryCrafting matrix, Selection selection) {
        Slot output = CraftingAccess.findActualResultSlot(container);
        if (output == null) {
            return;
        }
        ItemStack expected = RecipeResolver.safeResult(selection.recipe, matrix);
        if (expected == null) {
            return;
        }
        if (!ItemStack.areItemStacksEqual(output.getStack(), expected)) {
            output.putStack(expected.copy());
            container.detectAndSendChanges();
        }
    }

    private void primeAE2RecipeCache() {
        try {
            if (!aePlatformChecked) {
                aePlatformChecked = true;
                Class<?> platform = Class.forName("appeng.util.Platform");
                Field field = platform.getDeclaredField("lastUsedRecipe");
                field.setAccessible(true);
                aeLastUsedRecipe = field;
            }
            if (aeLastUsedRecipe != null) {
                aeLastUsedRecipe.set(null, AE2SelectionProxyRecipe.INSTANCE);
            }
        } catch (Throwable ignored) {
            aeLastUsedRecipe = null;
        }
    }

    private void clear(EntityPlayerMP player) {
        Selection removed = selections.remove(player);
        if (removed != null && removed.matrix != null) {
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
        final boolean ae2;

        Selection(int windowId, int recipeIndex, int fingerprint,
                InventoryCrafting matrix, IRecipe recipe, boolean ae2) {
            this.windowId = windowId;
            this.recipeIndex = recipeIndex;
            this.fingerprint = fingerprint;
            this.matrix = matrix;
            this.recipe = recipe;
            this.ae2 = ae2;
        }
    }
}
