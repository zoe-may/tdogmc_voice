package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.Tdogmc_voice;
import cn.tdogmc.tdogmc_voice.client.AudioEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "2";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Tdogmc_voice.MODID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static int id() { return packetId++; }

    public static void register() {
        // 只保留这 4 个包，移除 PlayAudioDataPacket 和 RequestDataC2SPacket
        INSTANCE.messageBuilder(StartStreamS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StartStreamS2CPacket::new).encoder(StartStreamS2CPacket::toBytes)
                .consumerMainThread(StartStreamS2CPacket::handle).add();

        INSTANCE.messageBuilder(StreamDataS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StreamDataS2CPacket::new).encoder(StreamDataS2CPacket::toBytes)
                .consumerMainThread(StreamDataS2CPacket::handle).add();

        INSTANCE.messageBuilder(EndStreamS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(EndStreamS2CPacket::new).encoder(EndStreamS2CPacket::toBytes)
                .consumerMainThread(EndStreamS2CPacket::handle).add();

        INSTANCE.messageBuilder(StopAllStreamsS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(StopAllStreamsS2CPacket::new).encoder(StopAllStreamsS2CPacket::toBytes)
                .consumerMainThread(StopAllStreamsS2CPacket::handle).add();
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}