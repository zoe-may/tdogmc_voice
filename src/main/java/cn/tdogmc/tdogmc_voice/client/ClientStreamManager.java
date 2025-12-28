package cn.tdogmc.tdogmc_voice.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientStreamManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, StreamingAudioPlayer> ACTIVE_STREAMS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (Minecraft.getInstance().level == null || ACTIVE_STREAMS.isEmpty()) return;
            for (Map.Entry<UUID, StreamingAudioPlayer> entry : ACTIVE_STREAMS.entrySet()) {
                if (entry.getValue() != null) entry.getValue().update();
            }
        }
    }

    public static void startStream(UUID streamId, double x, double y, double z, float range, float volume) {
        Minecraft.getInstance().execute(() -> {
            if (ACTIVE_STREAMS.containsKey(streamId)) {
                ACTIVE_STREAMS.remove(streamId).destroy();
            }
            StreamingAudioPlayer newPlayer = new StreamingAudioPlayer(streamId, x, y, z, range, volume);
            ACTIVE_STREAMS.put(streamId, newPlayer);
        });
    }

    public static void startStream(UUID streamId, UUID entityToFollow, float range, float volume) {
        Minecraft.getInstance().execute(() -> {
            if (ACTIVE_STREAMS.containsKey(streamId)) {
                ACTIVE_STREAMS.remove(streamId).destroy();
            }
            StreamingAudioPlayer newPlayer = new StreamingAudioPlayer(streamId, entityToFollow, range, volume);
            ACTIVE_STREAMS.put(streamId, newPlayer);
        });
    }

    public static void handleDataChunk(UUID streamId, byte[] data) {
        StreamingAudioPlayer player = ACTIVE_STREAMS.get(streamId);
        if (player != null) player.receiveData(data);
    }

    public static void endStream(UUID streamId) {
        StreamingAudioPlayer player = ACTIVE_STREAMS.get(streamId);
        if (player != null) player.markAsFinished();
    }

    public static void removeStream(UUID streamId) {
        ACTIVE_STREAMS.remove(streamId);
    }

    public static void stopAllStreams() {
        LOGGER.info("Received command to stop all streams. Cleaning up {} players.", ACTIVE_STREAMS.size());
        ACTIVE_STREAMS.forEach((uuid, player) -> player.destroy());
        ACTIVE_STREAMS.clear();
    }
}