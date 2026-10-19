package dev.frostey.polymorphlegacy.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public final class SelectRecipeMessage implements IMessage {
    public int windowId;
    public int recipeIndex;

    public SelectRecipeMessage() {}

    public SelectRecipeMessage(int windowId, int recipeIndex) {
        this.windowId = windowId;
        this.recipeIndex = recipeIndex;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        recipeIndex = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeInt(recipeIndex);
    }
}
