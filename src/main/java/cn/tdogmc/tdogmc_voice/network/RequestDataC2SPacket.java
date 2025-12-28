package cn.tdogmc.tdogmc_voice.network;

// 注意，handle 方法中引用的将是服务端的 StreamManager
import cn.tdogmc.tdogmc_voice.stream.ServerStreamManager; // 我们稍后会创建这个类
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * [C2S] 客户端向服务端请求下一个音频数据块。
 */
public class RequestDataC2SPacket {

    private final UUID streamId;

    public RequestDataC2SPacket(UUID streamId) {
        this.streamId = streamId;
    }

    public RequestDataC2SPacket(FriendlyByteBuf buf) {
        this.streamId = buf.readUUID();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.streamId);
    }

    // 处理器 (在服务端执行)
    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // C2S 包需要我们自己保证在主线程执行
        context.enqueueWork(() -> {
            // 获取发送这个包的玩家
            ServerPlayer player = context.getSender();
            if (player != null) {
                // 调用服务端流管理器来处理这个数据请求
                ServerStreamManager.handleDataRequest(streamId, player);
            }
        });
        return true;
    }
}