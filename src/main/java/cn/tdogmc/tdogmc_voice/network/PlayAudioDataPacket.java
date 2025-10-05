package cn.tdogmc.tdogmc_voice.network;

// 导入我们即将创建的 OpenAL 播放器
import cn.tdogmc.tdogmc_voice.client.OpenALPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlayAudioDataPacket {

    private final byte[] audioData;
    private final double x, y, z; // 新增：声音的位置坐标

    // 构造函数1 (服务端使用)
    public PlayAudioDataPacket(byte[] audioData, double x, double y, double z) {
        this.audioData = audioData;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // 构造函数2 (客户端接收时使用)
    public PlayAudioDataPacket(FriendlyByteBuf buf) {
        this.audioData = buf.readByteArray();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
    }

    // 编码方法
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeByteArray(this.audioData);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
    }

    // 处理器
    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            // 这是客户端代码！
            // 调用新的 OpenAL 播放器，并传入坐标
            OpenALPlayer.play(this.x, this.y, this.z, this.audioData);
        });
        return true;
    }
}