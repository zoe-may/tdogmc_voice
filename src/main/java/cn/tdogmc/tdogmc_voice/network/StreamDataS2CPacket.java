package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.ClientStreamManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * [S2C] 服务端向客户端发送一个音频数据块。
 */
public class StreamDataS2CPacket {

    private final UUID streamId;
    private final byte[] data;

    public StreamDataS2CPacket(UUID streamId, byte[] data) {
        this.streamId = streamId;
        this.data = data;
    }

    public StreamDataS2CPacket(FriendlyByteBuf buf) {
        this.streamId = buf.readUUID();
        this.data = buf.readByteArray(); // readByteArray 适用于小数据块
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.streamId);
        buf.writeByteArray(this.data);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            // 调用客户端流管理器来处理这个数据块
            ClientStreamManager.handleDataChunk(streamId, data);
        });
        return true;
    }
}