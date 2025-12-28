package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.ClientStreamManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public class StopAllStreamsS2CPacket {
    public StopAllStreamsS2CPacket() {} // 空构造
    public StopAllStreamsS2CPacket(FriendlyByteBuf buf) {} // 空构造
    public void toBytes(FriendlyByteBuf buf) {} // 无需写入

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(ClientStreamManager::stopAllStreams);
        return true;
    }
}