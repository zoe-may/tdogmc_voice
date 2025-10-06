package cn.tdogmc.tdogmc_voice.stream;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
import cn.tdogmc.tdogmc_voice.network.EndStreamS2CPacket;
import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.StartStreamS2CPacket;
import cn.tdogmc.tdogmc_voice.network.StreamDataS2CPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerStreamManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path SOUNDS_DIRECTORY = Path.of("sounds");
    private static final int CHUNK_SIZE = 16 * 1024;

    private static final Map<UUID, InputStream> ACTIVE_STREAMS = new ConcurrentHashMap<>();

    public static void startStream(ServerLevel level, Vec3 position, String soundFileName, float range, float volume) {
        Path audioPath = SOUNDS_DIRECTORY.resolve(soundFileName.replace('\\', '/'));
        if (!Files.isRegularFile(audioPath)) return;
        try {
            UUID streamId = UUID.randomUUID();
            InputStream inputStream = Files.newInputStream(audioPath);
            ACTIVE_STREAMS.put(streamId, inputStream);

            StartStreamS2CPacket startPacket = new StartStreamS2CPacket(streamId, position.x, position.y, position.z, range, volume);

            for (ServerPlayer player : level.players()) {
                if (player.position().distanceToSqr(position) < range * range) {
                    PacketHandler.sendToPlayer(startPacket, player);
                }
            }
            if (ModConfig.LOG_BASIC_INFO.get()) {
                LOGGER.info("已发起新的 OGG 音频流 '{}', ID: {}", soundFileName, streamId);
            }
        } catch (IOException e) {
            LOGGER.error("发起 OGG 音频流失败: {}", audioPath, e);
        }
    }

    public static void startStream(ServerLevel level, Entity entity, String soundFileName, float range, float volume) {
        Path audioPath = SOUNDS_DIRECTORY.resolve(soundFileName.replace('\\', '/'));
        if (!Files.isRegularFile(audioPath)) return;
        try {
            UUID streamId = UUID.randomUUID();
            InputStream inputStream = Files.newInputStream(audioPath);
            ACTIVE_STREAMS.put(streamId, inputStream);

            StartStreamS2CPacket startPacket = new StartStreamS2CPacket(streamId, entity.getUUID(), range, volume);

            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                PacketHandler.sendToPlayer(startPacket, player);
            }

            if (ModConfig.LOG_BASIC_INFO.get()) {
                LOGGER.info("已发起跟随实体 '{}' 的 OGG 音频流 '{}', ID: {}", entity.getName().getString(), soundFileName, streamId);
            }
        } catch (IOException e) {
            LOGGER.error("发起实体跟随音频流失败: {}", audioPath, e);
        }
    }

    public static void handleDataRequest(UUID streamId, ServerPlayer player) {
        InputStream instance = ACTIVE_STREAMS.get(streamId);
        if (instance == null) {
            return;
        }

        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead = instance.read(buffer);

            if (bytesRead > 0) {
                byte[] chunkToSend;
                if (bytesRead < CHUNK_SIZE) {
                    chunkToSend = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkToSend, 0, bytesRead);
                } else {
                    chunkToSend = buffer;
                }
                PacketHandler.sendToPlayer(new StreamDataS2CPacket(streamId, chunkToSend), player);
            }

            if (bytesRead == -1) {
                PacketHandler.sendToPlayer(new EndStreamS2CPacket(streamId), player);
                instance.close();
                ACTIVE_STREAMS.remove(streamId);
                if (ModConfig.LOG_BASIC_INFO.get()) {
                    LOGGER.info("OGG 音频流 {} 已完成并关闭。", streamId);
                }
            }
        } catch (IOException e) {
            LOGGER.error(">>> [BLACKBOX] Exception during handleDataRequest", e);
            try { instance.close(); } catch (IOException ignored) {}
            ACTIVE_STREAMS.remove(streamId);
        }
    }
}