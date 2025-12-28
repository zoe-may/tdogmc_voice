package cn.tdogmc.tdogmc_voice.stream;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
import cn.tdogmc.tdogmc_voice.network.EndStreamS2CPacket;
import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.StartStreamS2CPacket;
import cn.tdogmc.tdogmc_voice.network.StreamDataS2CPacket;
import cn.tdogmc.tdogmc_voice.util.SoundFileCache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerStreamManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_SIZE = 8192;
    private static final int PACKETS_PER_TICK = 4;

    private static final Map<UUID, StreamSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    public static void init() {
        MinecraftForge.EVENT_BUS.register(ServerStreamManager.class);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_SESSIONS.isEmpty()) return;

        Iterator<Map.Entry<UUID, StreamSession>> it = ACTIVE_SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            StreamSession session = it.next().getValue();
            if (!session.tick()) {
                session.close();
                it.remove();
            }
        }
    }

    /**
     * 播放给指定列表的玩家（定点音源）
     */
    public static void playToPlayers(Collection<ServerPlayer> recipients, Vec3 position, String filename, float range, float volume, float pitch) {
        startStreamInternal(recipients, position, null, filename, range, volume, pitch);
    }

    /**
     * 播放给指定列表的玩家（实体跟随音源）
     */
    public static void playToPlayers(Collection<ServerPlayer> recipients, Entity entitySource, String filename, float range, float volume, float pitch) {
        startStreamInternal(recipients, null, entitySource, filename, range, volume, pitch);
    }

    private static void startStreamInternal(Collection<ServerPlayer> recipients, Vec3 pos, Entity entity, String filename, float range, float volume, float pitch) {
        if (recipients.isEmpty()) return;

        Path path = SoundFileCache.getPath(filename);
        if (path == null) {
            LOGGER.warn("File not found in cache: {}", filename);
            return;
        }

        try {
            UUID streamId = UUID.randomUUID();
            InputStream input = Files.newInputStream(path);

            // 构建 Start Packet
            StartStreamS2CPacket startPacket;
            if (entity != null) {
                startPacket = new StartStreamS2CPacket(streamId, entity.getUUID(), range, volume, pitch);
            } else {
                startPacket = new StartStreamS2CPacket(streamId, pos.x, pos.y, pos.z, range, volume, pitch);
            }

            // 广播 Start Packet 给指定接收者
            for (ServerPlayer player : recipients) {
                PacketHandler.sendToPlayer(startPacket, player);
            }

            // 创建会话
            // 注意：这里我们做了一个优化，StreamSession 持有的是 recipients 的副本，防止外部修改
            StreamSession session = new StreamSession(streamId, input, new ArrayList<>(recipients));
            ACTIVE_SESSIONS.put(streamId, session);

            if (ModConfig.LOG_BASIC_INFO.get()) {
                LOGGER.info("Started audio stream {} for {} players", filename, recipients.size());
            }

        } catch (IOException e) {
            LOGGER.error("Failed to start stream", e);
        }
    }

    private static class StreamSession {
        private final UUID id;
        private final InputStream input;
        private final List<ServerPlayer> recipients;
        private final byte[] buffer = new byte[CHUNK_SIZE];

        public StreamSession(UUID id, InputStream input, List<ServerPlayer> recipients) {
            this.id = id;
            this.input = input;
            this.recipients = recipients;
        }

        public boolean tick() {
            try {
                recipients.removeIf(ServerPlayer::isRemoved);
                if (recipients.isEmpty()) return false;

                for (int i = 0; i < PACKETS_PER_TICK; i++) {
                    int read = input.read(buffer);
                    if (read == -1) {
                        finish();
                        return false;
                    }

                    byte[] dataToSend = (read == CHUNK_SIZE) ? buffer : Arrays.copyOf(buffer, read);
                    StreamDataS2CPacket packet = new StreamDataS2CPacket(id, dataToSend);

                    for (ServerPlayer player : recipients) {
                        PacketHandler.sendToPlayer(packet, player);
                    }

                    if (read < CHUNK_SIZE) {
                        finish();
                        return false;
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private void finish() {
            EndStreamS2CPacket packet = new EndStreamS2CPacket(id);
            for (ServerPlayer player : recipients) PacketHandler.sendToPlayer(packet, player);
        }

        public void close() {
            try { input.close(); } catch (IOException ignored) {}
        }
    }
}