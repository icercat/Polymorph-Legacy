package dev.frostey.recipeconflicts;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = RecipeConflictScannerMod.MODID,
    name = RecipeConflictScannerMod.NAME,
    version = RecipeConflictScannerMod.VERSION,
    acceptableRemoteVersions = "*")
public final class RecipeConflictScannerMod {

    public static final String MODID = "recipeconflictscanner";
    public static final String NAME = "Recipe Conflict Scanner";
    public static final String VERSION = Tags.VERSION;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Logger LOG = LogManager.getLogger(NAME);
    private static final int MAX_ALTERNATIVES_PER_SLOT = 24;
    private static final int MAX_SAMPLES_PER_RECIPE = 512;

    private static File reportDirectory;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        reportDirectory = new File(event.getModConfigurationDirectory(), "RecipeConflictScanner");
        if (!reportDirectory.exists()) {
            reportDirectory.mkdirs();
        }
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new RecipeConflictsCommand());
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        ScanResult result = scan(FMLCommonHandler.instance().getMinecraftServerInstance());
        if (writeReport(result)) {
            LOG.info("Scanned {} recipes: {} conflict groups. Report: {}",
                result.totalRecipes, result.conflicts.size(),
                new File(reportDirectory, "conflicts.txt").getAbsolutePath());
        } else {
            LOG.error("Could not write recipe conflict report");
        }
    }

    private static final class RecipeConflictsCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "recipeconflicts";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/recipeconflicts - rescan crafting recipe conflicts";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            sender.addChatMessage(new ChatComponentText("[RecipeConflictScanner] Scanning..."));

            ScanResult result = scan(FMLCommonHandler.instance().getMinecraftServerInstance());
            boolean written = writeReport(result);

            sender.addChatMessage(new ChatComponentText(
                "[RecipeConflictScanner] " + result.conflicts.size() + " conflict groups, "
                    + result.conflictingRecipeCount + " recipes involved. Report "
                    + (written ? "written." : "FAILED.")));
        }
    }

    private static ScanResult scan(MinecraftServer server) {
        long started = System.currentTimeMillis();

        List<?> rawRecipes = CraftingManager.getInstance().getRecipeList();
        List<IRecipe> recipes = new ArrayList<IRecipe>();

        for (Object object : rawRecipes) {
            if (object instanceof IRecipe) {
                recipes.add((IRecipe) object);
            }
        }

        final Map<IRecipe, Integer> recipeIndices = new IdentityHashMap<IRecipe, Integer>();
        for (int i = 0; i < recipes.size(); i++) {
            recipeIndices.put(recipes.get(i), Integer.valueOf(i));
        }

        Map<String, ItemStack[]> samples = new LinkedHashMap<String, ItemStack[]>();
        Set<String> unsupported = new LinkedHashSet<String>();

        for (IRecipe recipe : recipes) {
            List<ItemStack[]> generated = samplesFor(recipe);

            if (generated.isEmpty()) {
                unsupported.add(recipe.getClass().getName());
                continue;
            }

            for (ItemStack[] sample : generated) {
                samples.put(signature(sample), copyGrid(sample));
            }
        }

        World world = null;
        if (server != null && server.worldServers != null && server.worldServers.length > 0) {
            world = server.worldServers[0];
        }

        Map<String, ConflictGroup> groups = new LinkedHashMap<String, ConflictGroup>();
        Set<IRecipe> conflictingRecipes = Collections.newSetFromMap(new IdentityHashMap<IRecipe, Boolean>());
        int matchErrors = 0;

        for (ItemStack[] sample : samples.values()) {
            InventoryCrafting matrix = toMatrix(sample);
            List<IRecipe> matches = new ArrayList<IRecipe>();

            for (IRecipe recipe : recipes) {
                try {
                    if (recipe.matches(matrix, world)) {
                        matches.add(recipe);
                    }
                } catch (Throwable ignored) {
                    matchErrors++;
                }
            }

            if (matches.size() < 2) {
                continue;
            }

            Collections.sort(matches, new Comparator<IRecipe>() {
                @Override
                public int compare(IRecipe a, IRecipe b) {
                    return recipeIndices.get(a).intValue() - recipeIndices.get(b).intValue();
                }
            });

            StringBuilder groupKeyBuilder = new StringBuilder();

            for (IRecipe recipe : matches) {
                if (groupKeyBuilder.length() > 0) {
                    groupKeyBuilder.append(',');
                }
                groupKeyBuilder.append(recipeIndices.get(recipe));
            }

            String groupKey = groupKeyBuilder.toString();

            if (!groups.containsKey(groupKey)) {
                ConflictGroup group = new ConflictGroup(sample, matches, recipeIndices);
                groups.put(groupKey, group);
                conflictingRecipes.addAll(matches);
            }
        }

        List<ConflictGroup> conflicts = new ArrayList<ConflictGroup>(groups.values());

        Collections.sort(conflicts, new Comparator<ConflictGroup>() {
            @Override
            public int compare(ConflictGroup a, ConflictGroup b) {
                return b.recipes.size() - a.recipes.size();
            }
        });

        return new ScanResult(
            recipes.size(),
            samples.size(),
            conflicts,
            conflictingRecipes.size(),
            unsupported,
            matchErrors,
            System.currentTimeMillis() - started);
    }

    private static InventoryCrafting toMatrix(ItemStack[] sample) {
        InventoryCrafting matrix = new InventoryCrafting(new DummyContainer(), 3, 3);

        for (int i = 0; i < 9; i++) {
            matrix.setInventorySlotContents(i, copy(sample[i]));
        }

        return matrix;
    }

    private static final class DummyContainer extends Container {

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return false;
        }
    }

    private static List<ItemStack[]> samplesFor(IRecipe recipe) {
        if (recipe instanceof ShapedRecipes) {
            ShapedRecipes shaped = (ShapedRecipes) recipe;
            return shapedSamples(shaped.recipeItems, shaped.recipeWidth, shaped.recipeHeight);
        }

        if (recipe instanceof ShapelessRecipes) {
            ShapelessRecipes shapeless = (ShapelessRecipes) recipe;
            return shapelessSamples(shapeless.recipeItems.toArray());
        }

        if (recipe instanceof ShapedOreRecipe) {
            ShapedOreRecipe shaped = (ShapedOreRecipe) recipe;

            try {
                // Forge 1.7.10 exposes getInput(), but has no getWidth()/getHeight().
                Field width = ShapedOreRecipe.class.getDeclaredField("width");
                Field height = ShapedOreRecipe.class.getDeclaredField("height");
                width.setAccessible(true);
                height.setAccessible(true);
                return shapedSamples(shaped.getInput(), width.getInt(shaped), height.getInt(shaped));
            } catch (Throwable ignored) {
                return guessedShapedOreSamples(shaped.getInput());
            }
        }

        if (recipe instanceof ShapelessOreRecipe) {
            return shapelessSamples(((ShapelessOreRecipe) recipe).getInput().toArray());
        }

        return Collections.emptyList();
    }

    private static List<ItemStack[]> guessedShapedOreSamples(Object[] input) {
        List<ItemStack[]> result = new ArrayList<ItemStack[]>();
        int length = input.length;

        for (int width = 1; width <= 3; width++) {
            if (length % width != 0) {
                continue;
            }

            int height = length / width;

            if (height < 1 || height > 3) {
                continue;
            }

            result.addAll(shapedSamples(input, width, height));

            if (result.size() >= MAX_SAMPLES_PER_RECIPE) {
                break;
            }
        }

        return dedupe(result);
    }

    private static List<ItemStack[]> shapedSamples(Object[] input, int width, int height) {
        List<List<ItemStack>> choices = choicesFor(input);
        List<ItemStack[]> combinations = cartesian(choices, MAX_SAMPLES_PER_RECIPE);
        List<ItemStack[]> result = new ArrayList<ItemStack[]>();

        for (ItemStack[] values : combinations) {
            ItemStack[] grid = new ItemStack[9];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int source = x + y * width;

                    if (source < values.length) {
                        grid[x + y * 3] = copy(values[source]);
                    }
                }
            }

            result.add(grid);

            if (width > 1) {
                ItemStack[] mirrored = new ItemStack[9];

                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int source = (width - 1 - x) + y * width;

                        if (source < values.length) {
                            mirrored[x + y * 3] = copy(values[source]);
                        }
                    }
                }

                result.add(mirrored);
            }

            if (result.size() >= MAX_SAMPLES_PER_RECIPE) {
                break;
            }
        }

        return dedupe(result);
    }

    private static List<ItemStack[]> shapelessSamples(Object[] input) {
        List<List<ItemStack>> choices = choicesFor(input);
        List<ItemStack[]> combinations = cartesian(choices, Math.max(1, MAX_SAMPLES_PER_RECIPE / 4));
        List<ItemStack[]> result = new ArrayList<ItemStack[]>();

        for (ItemStack[] combination : combinations) {
            permuteIntoGrids(combination, 0, result, new HashSet<String>());

            if (result.size() >= MAX_SAMPLES_PER_RECIPE) {
                break;
            }
        }

        return dedupe(result);
    }

    private static void permuteIntoGrids(
        ItemStack[] items,
        int at,
        List<ItemStack[]> result,
        Set<String> seen) {

        if (result.size() >= MAX_SAMPLES_PER_RECIPE) {
            return;
        }

        if (at >= items.length) {
            ItemStack[] grid = new ItemStack[9];

            for (int i = 0; i < items.length && i < 9; i++) {
                grid[i] = copy(items[i]);
            }

            String key = signature(grid);

            if (seen.add(key)) {
                result.add(grid);
            }

            return;
        }

        Set<String> usedHere = new HashSet<String>();

        for (int i = at; i < items.length; i++) {
            String key = stackKey(items[i]);

            if (!usedHere.add(key)) {
                continue;
            }

            ItemStack temporary = items[at];
            items[at] = items[i];
            items[i] = temporary;

            permuteIntoGrids(items, at + 1, result, seen);

            temporary = items[at];
            items[at] = items[i];
            items[i] = temporary;

            if (result.size() >= MAX_SAMPLES_PER_RECIPE) {
                return;
            }
        }
    }

    private static List<List<ItemStack>> choicesFor(Object[] input) {
        List<List<ItemStack>> choices = new ArrayList<List<ItemStack>>();

        for (Object ingredient : input) {
            List<ItemStack> slotChoices = new ArrayList<ItemStack>();

            if (ingredient == null) {
                slotChoices.add(null);
            } else if (ingredient instanceof ItemStack) {
                slotChoices.add(copy((ItemStack) ingredient));
            } else if (ingredient instanceof Item) {
                slotChoices.add(new ItemStack((Item) ingredient));
            } else if (ingredient instanceof List) {
                for (Object alternative : (List<?>) ingredient) {
                    if (alternative instanceof ItemStack) {
                        slotChoices.add(copy((ItemStack) alternative));

                        if (slotChoices.size() >= MAX_ALTERNATIVES_PER_SLOT) {
                            break;
                        }
                    }
                }
            }

            if (slotChoices.isEmpty()) {
                slotChoices.add(null);
            }

            choices.add(slotChoices);
        }

        return choices;
    }

    private static List<ItemStack[]> cartesian(List<List<ItemStack>> choices, int limit) {
        List<ItemStack[]> result = new ArrayList<ItemStack[]>();
        buildCartesian(choices, 0, new ItemStack[choices.size()], result, limit);
        return result;
    }

    private static void buildCartesian(
        List<List<ItemStack>> choices,
        int index,
        ItemStack[] current,
        List<ItemStack[]> result,
        int limit) {

        if (result.size() >= limit) {
            return;
        }

        if (index >= choices.size()) {
            ItemStack[] snapshot = new ItemStack[current.length];

            for (int i = 0; i < current.length; i++) {
                snapshot[i] = copy(current[i]);
            }

            result.add(snapshot);
            return;
        }

        for (ItemStack option : choices.get(index)) {
            current[index] = option;
            buildCartesian(choices, index + 1, current, result, limit);

            if (result.size() >= limit) {
                return;
            }
        }
    }

    private static List<ItemStack[]> dedupe(List<ItemStack[]> input) {
        Map<String, ItemStack[]> deduped = new LinkedHashMap<String, ItemStack[]>();

        for (ItemStack[] grid : input) {
            deduped.put(signature(grid), grid);
        }

        return new ArrayList<ItemStack[]>(deduped.values());
    }

    private static String signature(ItemStack[] grid) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                result.append('|');
            }

            result.append(stackKey(grid[i]));
        }

        return result.toString();
    }

    private static String stackKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "-";
        }

        Object registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        String name = registryName == null
            ? String.valueOf(Item.getIdFromItem(stack.getItem()))
            : registryName.toString();

        return name + '@' + stack.getItemDamage()
            + (stack.hasTagCompound() ? ("#" + stack.getTagCompound().toString()) : "");
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }

    private static ItemStack[] copyGrid(ItemStack[] grid) {
        ItemStack[] copy = new ItemStack[9];

        for (int i = 0; i < 9; i++) {
            copy[i] = copy(grid[i]);
        }

        return copy;
    }

    private static boolean writeReport(ScanResult result) {
        if (reportDirectory == null) {
            return false;
        }

        if (!reportDirectory.exists() && !reportDirectory.mkdirs()) {
            return false;
        }

        File target = new File(reportDirectory, "conflicts.txt");
        Writer writer = null;

        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), UTF8));

            writer.write("Recipe Conflict Scanner " + VERSION + "\n");
            writer.write("Minecraft 1.7.10 / Forge\n\n");
            writer.write("Total registered IRecipe: " + result.totalRecipes + "\n");
            writer.write("Generated test matrices: " + result.generatedSamples + "\n");
            writer.write("Conflict groups: " + result.conflicts.size() + "\n");
            writer.write("Recipes involved: " + result.conflictingRecipeCount + "\n");
            writer.write("IRecipe.matches() errors caught: " + result.matchErrors + "\n");
            writer.write("Scan time: " + result.elapsedMillis + " ms\n\n");

            int conflictNumber = 1;

            for (ConflictGroup group : result.conflicts) {
                writer.write("============================================================\n");
                writer.write("CONFLICT #" + conflictNumber++ + " (" + group.recipes.size() + " recipes)\n");
                writer.write("Sample crafting matrix:\n");

                writeGrid(writer, group.sampleGrid);

                writer.write("\nMatching registered recipes:\n");

                for (int i = 0; i < group.recipes.size(); i++) {
                    IRecipe recipe = group.recipes.get(i);

                    writer.write("  [#" + group.recipeIndices.get(i) + "] "
                        + recipe.getClass().getName() + "\n");
                    writer.write("       output: " + describe(recipe.getRecipeOutput()) + "\n");
                }

                writer.write("\n");
            }

            if (!result.unsupportedRecipeClasses.isEmpty()) {
                writer.write("============================================================\n");
                writer.write("UNSUPPORTED IRecipe CLASSES\n");
                writer.write(
                    "These classes could not be used as sample generators. They are still tested by matches() "
                        + "against all generated matrices, but conflicts that can only be triggered by unsupported "
                        + "recipe classes may be missing.\n");

                for (String className : result.unsupportedRecipeClasses) {
                    writer.write("  " + className + "\n");
                }
            }

            writer.flush();
            return true;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void writeGrid(Writer writer, ItemStack[] grid) throws Exception {
        for (int y = 0; y < 3; y++) {
            writer.write("  ");

            for (int x = 0; x < 3; x++) {
                if (x > 0) {
                    writer.write(" | ");
                }

                writer.write(describe(grid[x + y * 3]));
            }

            writer.write("\n");
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "<empty>";
        }

        Object registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        String registry = registryName == null
            ? ("item#" + Item.getIdFromItem(stack.getItem()))
            : registryName.toString();

        String displayName;

        try {
            displayName = stack.getDisplayName();
        } catch (Throwable ignored) {
            displayName = "?";
        }

        return registry + "@" + stack.getItemDamage()
            + " x" + stack.stackSize + " [" + displayName + "]";
    }

    private static final class ConflictGroup {

        private final ItemStack[] sampleGrid;
        private final List<IRecipe> recipes;
        private final List<Integer> recipeIndices;

        private ConflictGroup(
            ItemStack[] sampleGrid,
            List<IRecipe> recipes,
            Map<IRecipe, Integer> indices) {

            this.sampleGrid = copyGrid(sampleGrid);
            this.recipes = Collections.unmodifiableList(new ArrayList<IRecipe>(recipes));

            List<Integer> ids = new ArrayList<Integer>();

            for (IRecipe recipe : recipes) {
                ids.add(indices.get(recipe));
            }

            this.recipeIndices = Collections.unmodifiableList(ids);
        }
    }

    private static final class ScanResult {

        private final int totalRecipes;
        private final int generatedSamples;
        private final List<ConflictGroup> conflicts;
        private final int conflictingRecipeCount;
        private final Set<String> unsupportedRecipeClasses;
        private final int matchErrors;
        private final long elapsedMillis;

        private ScanResult(
            int totalRecipes,
            int generatedSamples,
            List<ConflictGroup> conflicts,
            int conflictingRecipeCount,
            Set<String> unsupportedRecipeClasses,
            int matchErrors,
            long elapsedMillis) {

            this.totalRecipes = totalRecipes;
            this.generatedSamples = generatedSamples;
            this.conflicts = Collections.unmodifiableList(conflicts);
            this.conflictingRecipeCount = conflictingRecipeCount;
            this.unsupportedRecipeClasses =
                Collections.unmodifiableSet(new LinkedHashSet<String>(unsupportedRecipeClasses));
            this.matchErrors = matchErrors;
            this.elapsedMillis = elapsedMillis;
        }
    }
}
