package dev.frostey.polymorphlegacy.recipe;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;

/**
 * Finds the normal 1.7.10 crafting matrix/result pair without hard depending on
 * a specific crafting-table mod.
 */
public final class CraftingAccess {
    private static final Map<Class<?>, Field> MATRIX_FIELDS = new ConcurrentHashMap<Class<?>, Field>();
    private static final Map<Class<?>, Boolean> NO_MATRIX_FIELD = new ConcurrentHashMap<Class<?>, Boolean>();

    private CraftingAccess() {}

    public static InventoryCrafting findMatrix(Container container) {
        if (container == null || isSpecialContainer(container)) {
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

    public static SlotCrafting findResultSlot(Container container) {
        if (container == null || isSpecialContainer(container)) {
            return null;
        }
        for (Object object : container.inventorySlots) {
            if (object instanceof SlotCrafting) {
                return (SlotCrafting) object;
            }
        }
        return null;
    }

    public static boolean isSpecialContainer(Container container) {
        if (container == null) {
            return false;
        }
        String name = container.getClass().getName().toLowerCase();
        return name.startsWith("appeng.")
                || name.contains("craftingterminal")
                || name.contains("craftingterm");
    }

    private static InventoryCrafting readMatrix(Field field, Container container) {
        try {
            Object value = field.get(container);
            return value instanceof InventoryCrafting ? (InventoryCrafting) value : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }
}
