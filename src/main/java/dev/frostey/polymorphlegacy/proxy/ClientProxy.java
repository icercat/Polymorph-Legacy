package dev.frostey.polymorphlegacy.proxy;

import dev.frostey.polymorphlegacy.client.ClientGuiHandler;
import net.minecraftforge.common.MinecraftForge;

public final class ClientProxy extends CommonProxy {
    @Override
    public void registerHandlers() {
        super.registerHandlers();
        MinecraftForge.EVENT_BUS.register(ClientGuiHandler.INSTANCE);
    }
}
