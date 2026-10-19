package dev.frostey.polymorphlegacy.server;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

public final class ServerSelectionTicker {
    public static final ServerSelectionTicker INSTANCE = new ServerSelectionTicker();

    private ServerSelectionTicker() {}

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side == Side.SERVER && event.phase == TickEvent.Phase.END
                && event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            SelectionManager.INSTANCE.drainPending(player);
            SelectionManager.INSTANCE.apply(player);
        }
    }
}
