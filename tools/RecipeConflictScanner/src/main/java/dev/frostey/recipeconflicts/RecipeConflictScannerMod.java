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
import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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
    private static ScanJob activeScan;
    private static boolean autoScan;
    private static int tickBudgetMillis;
    private static int maxMatrices;
    private static int startupDelay = -1;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        reportDirectory = new File(event.getModConfigurationDirectory(), "RecipeConflictScanner");
        if (!reportDirectory.exists()) {
            reportDirectory.mkdirs();
        }
        Configuration config = new Configuration(new File(reportDirectory, "scanner.cfg"));
        config.load();
        autoScan = config.getBoolean("autoScanOnStartup", "scan", false,
            "Opt in to a delayed incremental startup scan. Default false keeps startup free of recipe scans.");
        tickBudgetMillis = config.getInt("tickBudgetMillis", "scan", 2, 1, 10,
            "Soft budget per server tick. A third-party recipe call cannot be interrupted safely.");
        maxMatrices = config.getInt("maxUniqueMatrices", "scan", 25000, 100, 1000000,
            "Bound sample memory. Hitting this limit marks the report as incomplete.");
        config.save();
        FMLCommonHandler.instance().bus().register(new TickHandler());
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new RecipeConflictsCommand());
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        activeScan = null;
        startupDelay = autoScan ? 100 : -1;
        LOG.info("Recipe scanner ready. Startup scan {}. Use /recipeconflicts start, status or cancel.",
            autoScan ? "scheduled after 100 ticks" : "disabled (default)");
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (activeScan != null) activeScan.cancel();
        activeScan = null;
        startupDelay = -1;
    }

    public static final class TickHandler {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (startupDelay > 0 && --startupDelay == 0 && activeScan == null) {
                activeScan = new ScanJob(FMLCommonHandler.instance().getMinecraftServerInstance());
            }
            ScanJob job = activeScan;
            if (job == null) return;
            try {
                if (job.advance()) {
                    ScanResult result = job.result();
                    boolean written = writeReport(result);
                    LOG.info("Scanned {} recipes: {} conflict groups. Report {}: {}",
                        result.totalRecipes, result.conflicts.size(), written ? "written" : "FAILED",
                        new File(reportDirectory, "conflicts.txt").getAbsolutePath());
                    activeScan = null;
                }
            } catch (RuntimeException error) {
                job.cancel();
                LOG.error("Recipe scan aborted; previous report retained", error);
                activeScan = null;
            }
        }
    }

    private static final class RecipeConflictsCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "recipeconflicts";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/recipeconflicts [start|status|cancel]";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            String action = args.length == 0 ? "start" : args[0];
            String message;
            if ("cancel".equalsIgnoreCase(action)) {
                message = activeScan == null ? "No scan is running." : "Scan cancelled; previous report retained.";
                if (activeScan != null) activeScan.cancel();
                activeScan = null;
                startupDelay = -1;
            } else if ("status".equalsIgnoreCase(action)) {
                message = activeScan == null ? "No scan is running. Report: config/RecipeConflictScanner/conflicts.txt"
                    : activeScan.status();
            } else if ("start".equalsIgnoreCase(action)) {
                startupDelay = -1;
                if (activeScan == null) {
                    activeScan = new ScanJob(FMLCommonHandler.instance().getMinecraftServerInstance());
                    message = "Incremental scan started. Use /recipeconflicts status or cancel.";
                } else message = "Scan already running. " + activeScan.status();
            } else message = getCommandUsage(sender);
            sender.addChatMessage(new ChatComponentText("[RecipeConflictScanner] " + message));
        }
    }

    // Snapshot filtering runs on a worker. Live recipe calls stay on server ticks.
    private static final class ScanJob {
        private final long started = System.currentTimeMillis();
        private long lastProgress = started;
        private final List<IRecipe> recipes = new ArrayList<IRecipe>();
        private final Map<IRecipe, Integer> recipeIndices = new IdentityHashMap<IRecipe, Integer>();
        private final Map<String, ItemStack[]> uniqueSamples = new LinkedHashMap<String, ItemStack[]>();
        private final List<ItemStack[]> samples = new ArrayList<ItemStack[]>();
        private final List<long[]> sampleTokens = new ArrayList<long[]>();
        private final SnapshotMatcher.Rule[] rules;
        private SnapshotMatcher worker;
        private Thread workerThread;
        private int[] candidates;
        private final Set<String> unsupported = new LinkedHashSet<String>();
        private final Set<String> warnings = new LinkedHashSet<String>();
        private final Set<IRecipe> disabled = Collections.newSetFromMap(new IdentityHashMap<IRecipe, Boolean>());
        private final Map<String, ConflictGroup> groups = new LinkedHashMap<String, ConflictGroup>();
        private final Set<IRecipe> conflicting = Collections.newSetFromMap(new IdentityHashMap<IRecipe, Boolean>());
        private final List<IRecipe> matches = new ArrayList<IRecipe>();
        private final World world;
        private int generatorIndex;
        private boolean generated;
        private int sampleIndex;
        private int recipeIndex;
        private int matchErrors;
        private long checks;

        private ScanJob(MinecraftServer server) {
            for (Object object : new ArrayList<Object>(CraftingManager.getInstance().getRecipeList())) {
                if (object instanceof IRecipe) {
                    recipeIndices.put((IRecipe) object, Integer.valueOf(recipes.size()));
                    recipes.add((IRecipe) object);
                }
            }
            world = server != null && server.worldServers != null && server.worldServers.length > 0
                ? server.worldServers[0] : null;
            rules = new SnapshotMatcher.Rule[recipes.size()];
            LOG.info("Incremental recipe scan started: {} recipes, {} ms/tick soft budget", recipes.size(), tickBudgetMillis);
        }

        private String status() {
            return !generated ? "Generating samples: " + generatorIndex + "/" + recipes.size()
                + " recipes, " + samples.size() + " matrices."
                : "Matching: " + sampleIndex + "/" + samples.size() + " matrices, recipe "
                    + recipeIndex + "/" + (candidates == null ? 0 : candidates.length) + ", " + checks
                    + " live checks, worker " + worker.matrices + "/" + samples.size() + " matrices, "
                    + worker.checks + " background checks, " + groups.size() + " groups.";
        }

        private void cancel() {
            if (workerThread != null) workerThread.interrupt();
        }

        private boolean advance() {
            long deadline = System.nanoTime() + tickBudgetMillis * 1000000L;
            int work = 0;
            int generators = 0;
            do {
                if (!generated) {
                    if (generatorIndex < recipes.size()) {
                        IRecipe recipe = recipes.get(generatorIndex++);
                        try {
                            rules[generatorIndex - 1] = snapshotRule(recipe);
                            List<ItemStack[]> grids = samplesFor(recipe);
                            if (grids.isEmpty()) unsupported.add(recipe.getClass().getName());
                            for (ItemStack[] grid : grids) {
                                String key = signature(grid);
                                if (uniqueSamples.containsKey(key)) continue;
                                if (samples.size() >= maxMatrices) {
                                    warnings.add("INCOMPLETE: unique matrix limit reached (" + maxMatrices + ").");
                                    generatorIndex = recipes.size();
                                    break;
                                }
                                uniqueSamples.put(key, grid);
                                samples.add(grid);
                                long[] tokens = new long[9];
                                for (int slot = 0; slot < 9; slot++) tokens[slot] = snapshotToken(grid[slot]);
                                sampleTokens.add(tokens);
                            }
                        } catch (RuntimeException | LinkageError error) {
                            unsupported.add(recipe.getClass().getName());
                            warnings.add("Sample generation failed for #" + (generatorIndex - 1) + " " + recipe.getClass().getName());
                            LOG.warn("Sample generation failed for " + recipe.getClass().getName(), error);
                        }
                        // Bound both the wall time and the number of recipe snapshots per tick.
                        if (++generators >= 4) break;
                        continue;
                    }
                    generated = true;
                    uniqueSamples.clear();
                    worker = new SnapshotMatcher(rules, sampleTokens);
                    sampleTokens.clear();
                    workerThread = new Thread(worker, "RecipeConflictScanner-worker");
                    workerThread.setDaemon(true);
                    workerThread.start();
                    LOG.info("Generated {} matrices; worker thread filtering {} recipe pairs", samples.size(),
                        (long) samples.size() * recipes.size());
                }
                if (sampleIndex >= samples.size()) return true;
                if (worker.failure != null) throw new IllegalStateException("Snapshot worker failed", worker.failure);
                if (candidates == null) {
                    candidates = worker.poll();
                    if (candidates == null) {
                        if (worker.done) {
                            // The final put may have raced the first poll.
                            candidates = worker.poll();
                            if (candidates == null) throw new IllegalStateException("Snapshot worker stopped before all matrices were delivered");
                        } else break; // Never wait for the worker on the server thread.
                    }
                }
                if (recipeIndex >= candidates.length) {
                    if (matches.size() > 1) {
                        StringBuilder key = new StringBuilder();
                        for (IRecipe match : matches) key.append(recipeIndices.get(match)).append(',');
                        if (!groups.containsKey(key.toString())) {
                            groups.put(key.toString(), new ConflictGroup(samples.get(sampleIndex), matches, recipeIndices));
                            conflicting.addAll(matches);
                        }
                    }
                    sampleIndex++;
                    recipeIndex = 0;
                    candidates = null;
                    matches.clear();
                    continue;
                }
                int registeredIndex = candidates[recipeIndex++];
                IRecipe recipe = recipes.get(registeredIndex);
                if (!disabled.contains(recipe)) {
                    long before = System.nanoTime();
                    try {
                        // Isolate a misbehaving recipe that mutates its test inventory.
                        if (recipe.matches(toMatrix(samples.get(sampleIndex)), world)) matches.add(recipe);
                    } catch (RuntimeException | LinkageError error) {
                        matchErrors++;
                        disabled.add(recipe);
                        warnings.add("INCOMPLETE: matches() failed; skipped #" + registeredIndex + " " + recipe.getClass().getName());
                        LOG.warn("Skipping failing recipe #" + registeredIndex + " " + recipe.getClass().getName(), error);
                    }
                    checks++;
                    if (System.nanoTime() - before > 50000000L && disabled.add(recipe)) {
                        warnings.add("INCOMPLETE: matches() exceeded 50ms; skipped remaining checks for #"
                            + registeredIndex + " " + recipe.getClass().getName());
                        LOG.warn("Slow matches() call (>50ms), skipping recipe #{} {}", registeredIndex, recipe.getClass().getName());
                    }
                }
                work++;
            } while (work < 2000 && System.nanoTime() < deadline);
            if (System.currentTimeMillis() - lastProgress >= 5000L) {
                LOG.info("Recipe scan progress: {}", status());
                lastProgress = System.currentTimeMillis();
            }
            return false;
        }

        private ScanResult result() {
            List<ConflictGroup> conflicts = new ArrayList<ConflictGroup>(groups.values());
            Collections.sort(conflicts, new Comparator<ConflictGroup>() {
                @Override
                public int compare(ConflictGroup a, ConflictGroup b) { return b.recipes.size() - a.recipes.size(); }
            });
            return new ScanResult(recipes.size(), samples.size(), conflicts, conflicting.size(), unsupported,
                matchErrors, System.currentTimeMillis() - started, warnings);
        }
    }

    private static long snapshotToken(ItemStack stack) {
        return stack == null ? SnapshotMatcher.EMPTY
            : SnapshotMatcher.token(Item.getIdFromItem(stack.getItem()), stack.getItemDamage());
    }

    private static SnapshotMatcher.Rule snapshotRule(IRecipe recipe) {
        Object[] input;
        if (recipe.getClass() == ShapedRecipes.class) input = ((ShapedRecipes) recipe).recipeItems;
        else if (recipe.getClass() == ShapelessRecipes.class) input = ((ShapelessRecipes) recipe).recipeItems.toArray();
        else if (recipe.getClass() == ShapedOreRecipe.class) input = ((ShapedOreRecipe) recipe).getInput();
        else if (recipe.getClass() == ShapelessOreRecipe.class) input = ((ShapelessOreRecipe) recipe).getInput().toArray();
        else return null; // Includes subclasses with overridden matches().
        int count = 0;
        Set<Long> tokens = new HashSet<Long>();
        for (Object ingredient : input) {
            if (ingredient == null) continue;
            count++;
            if (ingredient instanceof ItemStack) tokens.add(Long.valueOf(snapshotToken((ItemStack) ingredient)));
            else if (ingredient instanceof List) {
                for (Object alternative : (List<?>) ingredient) {
                    if (!(alternative instanceof ItemStack)) return null;
                    tokens.add(Long.valueOf(snapshotToken((ItemStack) alternative)));
                }
            } else return null;
        }
        return new SnapshotMatcher.Rule(count, tokens);
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
        if (input == null || width < 1 || width > 3 || height < 1 || height > 3
            || input.length != width * height) return Collections.emptyList();
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
        if (input == null || input.length < 1 || input.length > 9) return Collections.emptyList();
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
            writer.write("Sampling limits: 24 alternatives/slot, 512 matrices/recipe; not exhaustive.\n");
            for (String warning : result.warnings) writer.write(warning + "\n");
            writer.write("\n");

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
        private final Set<String> warnings;

        private ScanResult(
            int totalRecipes,
            int generatedSamples,
            List<ConflictGroup> conflicts,
            int conflictingRecipeCount,
            Set<String> unsupportedRecipeClasses,
            int matchErrors,
            long elapsedMillis,
            Set<String> warnings) {

            this.totalRecipes = totalRecipes;
            this.generatedSamples = generatedSamples;
            this.conflicts = Collections.unmodifiableList(conflicts);
            this.conflictingRecipeCount = conflictingRecipeCount;
            this.unsupportedRecipeClasses =
                Collections.unmodifiableSet(new LinkedHashSet<String>(unsupportedRecipeClasses));
            this.matchErrors = matchErrors;
            this.elapsedMillis = elapsedMillis;
            this.warnings = new LinkedHashSet<String>(warnings);
        }
    }
}
