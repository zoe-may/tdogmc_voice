package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.AudioEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public class StopAllStreamsS2CPacket {
    public StopAllStreamsS2CPacket() {}
    public StopAllStreamsS2CPacket(FriendlyByteBuf buf) {}
    public void toBytes(FriendlyByteBuf buf) {}

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> AudioEngine.getInstance().stopAll());
        return true;
    }
}