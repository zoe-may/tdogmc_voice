package cn.tdogmc.tdogmc_voice.client;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OpenALPlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenALPlayer.class);

    // 使用一个线程安全的列表来存储正在播放的声源ID和其关联的缓冲区ID
    private static final List<AudioSource> PLAYING_SOURCES = new CopyOnWriteArrayList<>();

    private record AudioSource(int sourceId, int bufferId) {}

    // 初始化方法，在客户端启动时调用，用于注册Tick事件监听器
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(OpenALPlayer::onClientTick);
    }

    // 这是我们的核心“轮询”方法，每一游戏Tick都会执行
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        // 我们只在Tick结束时执行，且确保游戏世界存在
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null || PLAYING_SOURCES.isEmpty()) {
            return;
        }

        // 1. 创建一个临时的列表，用于收集所有已播放完毕的声源
        List<AudioSource> sourcesToRemove = null;

        // 2. 遍历正在播放的声源列表
        for (AudioSource audioSource : PLAYING_SOURCES) {
            int state = AL10.alGetSourcei(audioSource.sourceId(), AL10.AL_SOURCE_STATE);

            // 如果声源已经停止播放
            if (state != AL10.AL_PLAYING) {
                // 延迟初始化列表，只有在需要删除时才创建
                if (sourcesToRemove == null) {
                    sourcesToRemove = new java.util.ArrayList<>();
                }
                // 将其加入待删除列表
                sourcesToRemove.add(audioSource);

                // 在主线程安全地清理 OpenAL 资源
                AL10.alDeleteSources(audioSource.sourceId());
                AL10.alDeleteBuffers(audioSource.bufferId());
                if (ModConfig.DEBUG_MODE.get()) { // 添加检查
                    LOGGER.debug("Cleaned up finished audio source: {}", audioSource.sourceId());
                }
            }
        }

        // 3. 在遍历结束后，如果待删除列表不为空，则一次性从主列表中移除所有元素
        if (sourcesToRemove != null) {
            PLAYING_SOURCES.removeAll(sourcesToRemove);
        }
    }

    // 新的 play 方法，现在是“即发即忘”模式
    public static void play(double x, double y, double z, byte[] audioData) {
        // 所有OpenAL操作都应该在主线程执行以确保线程安全
        Minecraft.getInstance().execute(() -> {
            ByteBuffer audioBuffer = MemoryUtil.memAlloc(audioData.length);
            audioBuffer.put(audioData);
            audioBuffer.flip();

            try {
                DecodedAudio decoded = decode(audioBuffer);
                int bufferId = AL10.alGenBuffers();
                AL10.alBufferData(bufferId, decoded.format, decoded.data, decoded.sampleRate);
                MemoryUtil.memFree(decoded.data);

                int sourceId = AL10.alGenSources();
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
                AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 8.0f);
                AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 64.0f);

                AL10.alSourcePlay(sourceId);

                // 播放后，立即将其加入管理列表，然后方法结束！
                PLAYING_SOURCES.add(new AudioSource(sourceId, bufferId));

            } catch (Exception e) {
                LOGGER.error("Error playing OpenAL audio", e);
            } finally {
                MemoryUtil.memFree(audioBuffer);
            }
        });
    }

    // --- 音频解码辅助部分 ---
    private static class DecodedAudio {
        public final int format;
        public final int sampleRate;
        public final ShortBuffer data;

        private DecodedAudio(int format, int sampleRate, ShortBuffer data) {
            this.format = format;
            this.sampleRate = sampleRate;
            this.data = data;
        }
    }

    private static DecodedAudio decode(ByteBuffer audioBuffer) throws IOException {
        // 尝试作为 OGG 解码
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(audioBuffer, channelsBuffer, sampleRateBuffer);
            if (pcm != null) {
                int channels = channelsBuffer.get(0);
                int sampleRate = sampleRateBuffer.get(0);
                int format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                if (ModConfig.DEBUG_MODE.get()) { // 添加检查
                    LOGGER.info("成功解码 OGG 音频: {} channels, {} Hz", channels, sampleRate);
                }
                return new DecodedAudio(format, sampleRate, pcm);
            }
        }

        // 如果 OGG 失败，可以添加 WAV 的解码逻辑，但 OGG 更适合网络传输
        throw new IOException("无法将音频数据解码为 OGG Vorbis 格式。");
    }
}