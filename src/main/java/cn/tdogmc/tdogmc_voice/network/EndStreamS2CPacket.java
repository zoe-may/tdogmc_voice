package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.AudioEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

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
            // 调用 AudioEngine 结束流
            AudioEngine.getInstance().endStream(streamId);
        });
        return true;
    }
}