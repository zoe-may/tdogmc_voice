package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.OpenALPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlayAudioDataPacket {

    private final byte[] audioData;
    private final double x, y, z;
    private final float range;
    private final float volume; // 新增：音量

    public PlayAudioDataPacket(byte[] audioData, double x, double y, double z, float range, float volume) {
        this.audioData = audioData;
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
        this.volume = volume;
    }

    public PlayAudioDataPacket(FriendlyByteBuf buf) {
        this.audioData = buf.readByteArray();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.range = buf.readFloat();
        this.volume = buf.readFloat(); // 读取音量
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeByteArray(this.audioData);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.range);
        buf.writeFloat(this.volume); // 写入音量
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            // 传递音量和范围
            OpenALPlayer.play(this.x, this.y, this.z, this.audioData, this.range, this.volume);
        });
        return true;
    }
}