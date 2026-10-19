package dev.frostey.polymorphlegacy;

import java.util.List;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import dev.frostey.polymorphlegacy.network.SelectRecipeHandler;
import dev.frostey.polymorphlegacy.network.SelectRecipeMessage;
import dev.frostey.polymorphlegacy.proxy.CommonProxy;
import dev.frostey.polymorphlegacy.recipe.SelectionProxyRecipe;
import net.minecraft.item.crafting.CraftingManager;

@Mod(
        modid = PolymorphLegacy.MOD_ID,
        name = PolymorphLegacy.MOD_NAME,
        version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]"
)
public final class PolymorphLegacy {
    public static final String MOD_ID = "atm0polymorph";
    public static final String MOD_NAME = "ATM0 Polymorph Legacy";
    public static final String NETWORK_CHANNEL = "atm0poly";

    public static final SimpleNetworkWrapper NETWORK =
            NetworkRegistry.INSTANCE.newSimpleChannel(NETWORK_CHANNEL);

    @SidedProxy(
            clientSide = "dev.frostey.polymorphlegacy.proxy.ClientProxy",
            serverSide = "dev.frostey.polymorphlegacy.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        NETWORK.registerMessage(SelectRecipeHandler.class, SelectRecipeMessage.class, 0, Side.SERVER);
        proxy.registerHandlers();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        ensureSelectionProxyFirst();
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        // Forge 1.7.10 RecipeSorter runs during Forge's LoadComplete handler,
        // after mod postInit. Re-assert priority after that sort has finished.
        ensureSelectionProxyFirst();
    }

    @SuppressWarnings("unchecked")
    private static void ensureSelectionProxyFirst() {
        List<Object> recipes = CraftingManager.getInstance().getRecipeList();
        recipes.remove(SelectionProxyRecipe.INSTANCE);
        recipes.add(0, SelectionProxyRecipe.INSTANCE);
    }
}
