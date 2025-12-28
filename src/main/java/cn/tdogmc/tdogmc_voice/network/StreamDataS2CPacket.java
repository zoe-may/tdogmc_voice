package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.AudioEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class StreamDataS2CPacket {

    private final UUID streamId;
    private final byte[] data;

    public StreamDataS2CPacket(UUID streamId, byte[] data) {
        this.streamId = streamId;
        this.data = data;
    }

    public StreamDataS2CPacket(FriendlyByteBuf buf) {
        this.streamId = buf.readUUID();
        this.data = buf.readByteArray();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.streamId);
        buf.writeByteArray(this.data);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            // 【修正】这里是 receiveData，不是 startStream
            AudioEngine.getInstance().receiveData(streamId, data);
        });
        return true;
    }
}