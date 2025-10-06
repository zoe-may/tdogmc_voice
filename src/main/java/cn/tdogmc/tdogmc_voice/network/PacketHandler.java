package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.Tdogmc_voice;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {

    private static final String PROTOCOL_VERSION = "2"; // 建议升级协议版本号
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Tdogmc_voice.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int packetId = 0;
    private static int id() {
        return packetId++;
    }

    public static void register() {
        // 保留我们旧的数据包，以防万一
        INSTANCE.messageBuilder(PlayAudioDataPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(PlayAudioDataPacket::new)
                .encoder(PlayAudioDataPacket::toBytes)
                .consumerMainThread(PlayAudioDataPacket::handle)
                .add();

        // --- 注册新的流式数据包 ---

        // 1. S2C: Start Stream
        INSTANCE.messageBuilder(StartStreamS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StartStreamS2CPacket::new)
                .encoder(StartStreamS2CPacket::toBytes)
                .consumerMainThread(StartStreamS2CPacket::handle)
                .add();

        // 2. S2C: Stream Data
        INSTANCE.messageBuilder(StreamDataS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StreamDataS2CPacket::new)
                .encoder(StreamDataS2CPacket::toBytes)
                .consumerMainThread(StreamDataS2CPacket::handle)
                .add();

        // 3. S2C: End Stream
        INSTANCE.messageBuilder(EndStreamS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(EndStreamS2CPacket::new)
                .encoder(EndStreamS2CPacket::toBytes)
                .consumerMainThread(EndStreamS2CPacket::handle)
                .add();

        // 4. C2S: Request Data
        INSTANCE.messageBuilder(RequestDataC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER) // 注意方向是 TO_SERVER
                .decoder(RequestDataC2SPacket::new)
                .encoder(RequestDataC2SPacket::toBytes)
                .consumerMainThread(RequestDataC2SPacket::handle)
                .add();
        // 在 PacketHandler.register() 中
        INSTANCE.messageBuilder(StopAllStreamsS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StopAllStreamsS2CPacket::new)
                .encoder(StopAllStreamsS2CPacket::toBytes)
                .consumerMainThread(StopAllStreamsS2CPacket::handle)
                .add();
    }

    // 辅助方法可以保持不变，但我们可能需要新的广播方法
    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    // 新增一个发送给服务端的辅助方法
    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }
}