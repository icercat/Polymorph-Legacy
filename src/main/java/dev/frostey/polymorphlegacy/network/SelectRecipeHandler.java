package dev.frostey.polymorphlegacy.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.entity.player.EntityPlayerMP;
import dev.frostey.polymorphlegacy.server.SelectionManager;

public final class SelectRecipeHandler
        implements IMessageHandler<SelectRecipeMessage, IMessage> {

    @Override
    public IMessage onMessage(SelectRecipeMessage message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        SelectionManager.INSTANCE.enqueue(player, message.windowId, message.recipeIndex);
        return null;
    }
}
