package dev.frostey.polymorphlegacy.proxy;

import cpw.mods.fml.common.FMLCommonHandler;
import dev.frostey.polymorphlegacy.server.ServerSelectionTicker;

public class CommonProxy {
    public void registerHandlers() {
        FMLCommonHandler.instance().bus().register(ServerSelectionTicker.INSTANCE);
    }
}
