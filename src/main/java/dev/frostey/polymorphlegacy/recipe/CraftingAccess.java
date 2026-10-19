package dev.frostey.polymorphlegacy.recipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;

/**
 * Finds conventional crafting matrices and also mirrors AE2 crafting terminals
 * into a temporary InventoryCrafting without taking a hard dependency on AE2.
 */
public final class CraftingAccess {
    private static final Map<Class<?>, Field> MATRIX_FIELDS = new ConcurrentHashMap<Class<?>, Field>();
    private static final Map<Class<?>, Boolean> NO_MATRIX_FIELD = new ConcurrentHashMap<Class<?>, Boolean>();

    private static final String AE_MATRIX_SLOT = "appeng.container.slot.SlotCraftingMatrix";
    private static final String AE_RESULT_SLOT = "appeng.container.slot.SlotCraftingTerm";

    private CraftingAccess() {}

    public static InventoryCrafting findMatrix(Container container) {
        if (container == null) {
            return null;
        }

        for (Object object : container.inventorySlots) {
            if (object instanceof Slot) {
                Slot slot = (Slot) object;
                if (slot.inventory instanceof InventoryCrafting) {
                    return (InventoryCrafting) slot.inventory;
                }
            }
        }

        InventoryCrafting ae = buildAE2Matrix(container);
        if (ae != null) {
            return ae;
        }

        Class<?> type = container.getClass();
        Field cached = MATRIX_FIELDS.get(type);
        if (cached != null) {
            return readMatrix(cached, container);
        }
        if (Boolean.TRUE.equals(NO_MATRIX_FIELD.get(type))) {
            return null;
        }

        Class<?> cursor = type;
        while (cursor != null && Container.class.isAssignableFrom(cursor)) {
            for (Field field : cursor.getDeclaredFields()) {
                if (InventoryCrafting.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    InventoryCrafting matrix = readMatrix(field, container);
                    if (matrix != null) {
                        MATRIX_FIELDS.put(type, field);
                        return matrix;
                    }
                }
            }
            cursor = cursor.getSuperclass();
        }

        NO_MATRIX_FIELD.put(type, Boolean.TRUE);
        return null;
    }

    public static Slot findResultSlot(Container container) {
        if (container == null) {
            return null;
        }
        for (Object object : container.inventorySlots) {
            if (object instanceof SlotCrafting) {
                return (Slot) object;
            }
        }

        return findAE2ResultSlot(container);
    }

    public static Slot findActualResultSlot(Container container) {
        if (container == null) {
            return null;
        }
        for (Object object : container.inventorySlots) {
            if (object instanceof SlotCrafting) {
                return (Slot) object;
            }
        }
        return findAE2ResultSlot(container);
    }

    public static boolean isAE2CraftingContainer(Container container) {
        return container != null && findAE2ResultSlot(container) != null && countAE2MatrixSlots(container) == 9;
    }

    public static boolean isSpecialContainer(Container container) {
        return false;
    }

    private static InventoryCrafting buildAE2Matrix(Container container) {
        List<Slot> slots = new ArrayList<Slot>();
        for (Object object : container.inventorySlots) {
            if (object instanceof Slot && isClassOrSuperNamed(object.getClass(), AE_MATRIX_SLOT)) {
                slots.add((Slot) object);
            }
        }
        if (slots.size() != 9) {
            return null;
        }

        Collections.sort(slots, new Comparator<Slot>() {
            @Override
            public int compare(Slot a, Slot b) {
                if (a.yDisplayPosition != b.yDisplayPosition) {
                    return a.yDisplayPosition < b.yDisplayPosition ? -1 : 1;
                }
                if (a.xDisplayPosition == b.xDisplayPosition) {
                    return 0;
                }
                return a.xDisplayPosition < b.xDisplayPosition ? -1 : 1;
            }
        });

        InventoryCrafting mirror = new InventoryCrafting(DummyContainer.INSTANCE, 3, 3);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = slots.get(i).getStack();
            mirror.setInventorySlotContents(i, stack == null ? null : stack.copy());
        }
        return mirror;
    }

    private static int countAE2MatrixSlots(Container container) {
        int count = 0;
        for (Object object : container.inventorySlots) {
            if (object instanceof Slot && isClassOrSuperNamed(object.getClass(), AE_MATRIX_SLOT)) {
                count++;
            }
        }
        return count;
    }

    private static Slot findAE2ResultSlot(Container container) {
        for (Object object : container.inventorySlots) {
            if (object instanceof Slot && isClassOrSuperNamed(object.getClass(), AE_RESULT_SLOT)) {
                return (Slot) object;
            }
        }
        return null;
    }

    private static boolean isClassOrSuperNamed(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            if (name.equals(cursor.getName())) {
                return true;
            }
            cursor = cursor.getSuperclass();
        }
        return false;
    }

    private static InventoryCrafting readMatrix(Field field, Container container) {
        try {
            Object value = field.get(container);
            return value instanceof InventoryCrafting ? (InventoryCrafting) value : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static final class DummyContainer extends Container {
        static final DummyContainer INSTANCE = new DummyContainer();

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return false;
        }
    }
}
