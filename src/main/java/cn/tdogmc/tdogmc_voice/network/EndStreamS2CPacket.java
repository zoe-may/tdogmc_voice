package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.ClientStreamManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * [S2C] 服务端通知客户端流已结束，不会再有数据传来。
 */
public class EndStreamS2CPacket {

    private final UUID streamId;

    public EndStreamS2CPacket(UUID streamId) {
        this.streamId = streamId;
    }

    public EndStreamS2CPacket(FriendlyByteBuf buf) {
        this.streamId = buf.readUUID();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.streamId);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            // 调用客户端流管理器来处理这个“结束”事件
            ClientStreamManager.endStream(streamId);
        });
        return true;
    }
}