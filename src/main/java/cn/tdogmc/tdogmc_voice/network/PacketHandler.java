package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.Tdogmc_voice;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {

    private static final String PROTOCOL_VERSION = "1";
    // 创建一个 SimpleChannel 实例。这是我们模组的专属网络通道。
    // ResourceLocation 必须是唯一的，通常使用 modid 和一个路径。
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

    // 注册我们所有的数据包
    public static void register() {
        // 注册 PlayAudioDataPacket
        INSTANCE.messageBuilder(PlayAudioDataPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(PlayAudioDataPacket::new) // 解码器：引用构造函数2
                .encoder(PlayAudioDataPacket::toBytes) // 编码器：引用 toBytes 方法
                .consumerMainThread(PlayAudioDataPacket::handle) // 处理器：引用 handle 方法
                .add();

        // 如果未来有更多的数据包，继续在这里添加...
    }

    // 一个方便的辅助方法，用于向特定玩家发送数据包
    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}