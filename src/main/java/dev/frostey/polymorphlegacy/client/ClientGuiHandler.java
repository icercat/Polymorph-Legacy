package dev.frostey.polymorphlegacy.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.relauncher.ReflectionHelper;
import dev.frostey.polymorphlegacy.PolymorphLegacy;
import dev.frostey.polymorphlegacy.network.SelectRecipeMessage;
import dev.frostey.polymorphlegacy.recipe.CraftingAccess;
import dev.frostey.polymorphlegacy.recipe.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.client.event.GuiScreenEvent;

/**
 * Polymorph-like chooser injected into any GuiContainer exposing a normal
 * InventoryCrafting + crafting result pair, including the AE2 adapter.
 */
public final class ClientGuiHandler {
    public static final ClientGuiHandler INSTANCE = new ClientGuiHandler();

    private static final int ROOT_ID = 17710;
    private static final int CHOICE_ID_BASE = 17720;
    private static final int MAX_CHOICES = 16;
    private static final int CHOICE_WIDTH = 122;
    private static final int CHOICE_HEIGHT = 20;
    private static final int CHOICES_PER_COLUMN = 8;

    private GuiContainer activeGui;
    private ToggleButton toggleButton;
    private final List<RecipeChoiceButton> choiceButtons = new ArrayList<RecipeChoiceButton>();
    private boolean menuOpen;
    private int selectedIndex;
    private int lastFingerprint;
    private Container snapshotContainer;
    private Snapshot snapshot = Snapshot.EMPTY;

    private ClientGuiHandler() {}

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiContainer)) {
            reset();
            return;
        }

        GuiContainer gui = (GuiContainer) event.gui;
        Container container = gui.inventorySlots;

        if (CraftingAccess.findMatrix(container) == null || CraftingAccess.findResultSlot(container) == null) {
            reset();
            return;
        }

        activeGui = gui;
        menuOpen = false;
        selectedIndex = 0;
        lastFingerprint = 0;
        snapshotContainer = null;
        snapshot = Snapshot.EMPTY;
        choiceButtons.clear();

        toggleButton = new ToggleButton(ROOT_ID, 0, 0);
        event.buttonList.add(toggleButton);

        for (int i = 0; i < MAX_CHOICES; i++) {
            RecipeChoiceButton button = new RecipeChoiceButton(CHOICE_ID_BASE + i, i);
            choiceButtons.add(button);
            event.buttonList.add(button);
        }
    }

    @SubscribeEvent
    public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (event.gui != activeGui || activeGui == null || toggleButton == null) {
            return;
        }

        updateSnapshot();
        updateButtons();
    }

    @SubscribeEvent
    public void onButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.gui != activeGui || activeGui == null) {
            return;
        }

        if (event.button == toggleButton) {
            menuOpen = !menuOpen;
            event.setCanceled(true);
            return;
        }

        if (event.button instanceof RecipeChoiceButton) {
            RecipeChoiceButton choice = (RecipeChoiceButton) event.button;
            if (choice.recipeIndex < snapshot.results.size()) {
                selectedIndex = choice.recipeIndex;
                IMessage message =
                        new SelectRecipeMessage(activeGui.inventorySlots.windowId, selectedIndex);
                PolymorphLegacy.NETWORK.sendToServer(message);
                menuOpen = false;
            }
            event.setCanceled(true);
        }
    }

    private void updateSnapshot() {
        Minecraft mc = Minecraft.getMinecraft();
        Container container = activeGui.inventorySlots;
        InventoryCrafting matrix = CraftingAccess.findMatrix(container);
        Slot resultSlot = CraftingAccess.findResultSlot(container);
        if (matrix == null || resultSlot == null || mc.theWorld == null) {
            snapshot = Snapshot.EMPTY;
            return;
        }

        int fingerprint = RecipeResolver.fingerprint(matrix);
        boolean matrixChanged = fingerprint != lastFingerprint || snapshotContainer != container;
        if (!matrixChanged) {
            return;
        }

        selectedIndex = 0;
        menuOpen = false;
        lastFingerprint = fingerprint;
        snapshotContainer = container;

        List<IRecipe> matches = RecipeResolver.findMatches(matrix, mc.theWorld);
        if (matches.size() < 2) {
            snapshot = new Snapshot(resultSlot, Collections.<ItemStack>emptyList());
            menuOpen = false;
            return;
        }

        List<ItemStack> results = new ArrayList<ItemStack>(matches.size());
        for (IRecipe recipe : matches) {
            ItemStack result = RecipeResolver.safeResult(recipe, matrix);
            if (result != null) {
                results.add(result.copy());
            }
        }
        snapshot = new Snapshot(resultSlot, results);
        if (selectedIndex >= results.size()) {
            selectedIndex = 0;
        }
    }

    private void updateButtons() {
        boolean conflict = snapshot.resultSlot != null && snapshot.results.size() > 1;
        toggleButton.visible = conflict;
        toggleButton.enabled = conflict;
        toggleButton.displayString = conflict ? Integer.toString(snapshot.results.size()) : "";

        for (RecipeChoiceButton button : choiceButtons) {
            button.visible = false;
            button.stack = null;
        }

        if (!conflict) {
            return;
        }

        int guiLeft = getGuiLeft(activeGui);
        int guiTop = getGuiTop(activeGui);
        int resultX = guiLeft + snapshot.resultSlot.xDisplayPosition;
        int resultY = guiTop + snapshot.resultSlot.yDisplayPosition;

        toggleButton.xPosition = resultX + 21;
        toggleButton.yPosition = resultY - 1;

        if (!menuOpen) {
            return;
        }

        int visible = Math.min(snapshot.results.size(), MAX_CHOICES);
        int columns = (visible + CHOICES_PER_COLUMN - 1) / CHOICES_PER_COLUMN;
        int totalWidth = columns * (CHOICE_WIDTH + 2);
        int startX = toggleButton.xPosition + 18 + 2;
        if (startX + totalWidth > activeGui.width) {
            startX = resultX - totalWidth - 5;
        }
        int startY = resultY - 1;

        for (int i = 0; i < visible; i++) {
            RecipeChoiceButton button = choiceButtons.get(i);
            int column = i / CHOICES_PER_COLUMN;
            int row = i % CHOICES_PER_COLUMN;
            button.xPosition = startX + column * (CHOICE_WIDTH + 2);
            button.yPosition = startY + row * (CHOICE_HEIGHT + 1);
            button.visible = true;
            button.enabled = true;
            button.stack = snapshot.results.get(i);
            button.selected = i == selectedIndex;
        }
    }

    private static int getGuiLeft(GuiContainer gui) {
        return ReflectionHelper.getPrivateValue(
                GuiContainer.class, gui, "guiLeft", "field_147003_i");
    }

    private static int getGuiTop(GuiContainer gui) {
        return ReflectionHelper.getPrivateValue(
                GuiContainer.class, gui, "guiTop", "field_147009_r");
    }

    private void reset() {
        activeGui = null;
        toggleButton = null;
        choiceButtons.clear();
        menuOpen = false;
        selectedIndex = 0;
        lastFingerprint = 0;
        snapshotContainer = null;
        snapshot = Snapshot.EMPTY;
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(null, Collections.<ItemStack>emptyList());
        final Slot resultSlot;
        final List<ItemStack> results;

        Snapshot(Slot resultSlot, List<ItemStack> results) {
            this.resultSlot = resultSlot;
            this.results = results;
        }
    }

    private static final class ToggleButton extends GuiButton {
        ToggleButton(int id, int x, int y) {
            super(id, x, y, 18, 18, "");
        }
    }

    private static final class RecipeChoiceButton extends GuiButton {
        private static final RenderItem ITEM_RENDER = new RenderItem();

        final int recipeIndex;
        ItemStack stack;
        boolean selected;

        RecipeChoiceButton(int id, int recipeIndex) {
            super(id, 0, 0, CHOICE_WIDTH, CHOICE_HEIGHT, "");
            this.recipeIndex = recipeIndex;
            this.visible = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible || stack == null) {
                return;
            }

            String original = displayString;
            displayString = selected ? ">" : "";
            super.drawButton(mc, mouseX, mouseY);
            displayString = original;

            GL11.glPushMatrix();
            RenderHelper.enableGUIStandardItemLighting();
            ITEM_RENDER.zLevel = 200.0F;
            ITEM_RENDER.renderItemAndEffectIntoGUI(
                    mc.fontRenderer, mc.getTextureManager(), stack, xPosition + 3, yPosition + 2);
            ITEM_RENDER.zLevel = 0.0F;
            RenderHelper.disableStandardItemLighting();
            GL11.glPopMatrix();

            String name = stack.getDisplayName();
            if (selected) {
                name = "> " + name;
            }
            name = mc.fontRenderer.trimStringToWidth(name, CHOICE_WIDTH - 24);
            int color = enabled ? 0xE0E0E0 : 0xA0A0A0;
            mc.fontRenderer.drawStringWithShadow(name, xPosition + 23, yPosition + 6, color);
        }
    }
}
