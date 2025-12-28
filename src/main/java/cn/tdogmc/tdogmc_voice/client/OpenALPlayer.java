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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OpenALPlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenALPlayer.class);
    private static final List<AudioSource> PLAYING_SOURCES = new CopyOnWriteArrayList<>();

    private record AudioSource(int sourceId, int bufferId) {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(OpenALPlayer::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null || PLAYING_SOURCES.isEmpty()) {
            return;
        }
        List<AudioSource> sourcesToRemove = null;
        for (AudioSource audioSource : PLAYING_SOURCES) {
            int state = AL10.alGetSourcei(audioSource.sourceId(), AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                if (sourcesToRemove == null) {
                    sourcesToRemove = new java.util.ArrayList<>();
                }
                sourcesToRemove.add(audioSource);
                AL10.alDeleteSources(audioSource.sourceId());
                AL10.alDeleteBuffers(audioSource.bufferId());
                if (ModConfig.DEBUG_MODE.get()) {
                    LOGGER.debug("Cleaned up finished audio source: {}", audioSource.sourceId());
                }
            }
        }
        if (sourcesToRemove != null) {
            PLAYING_SOURCES.removeAll(sourcesToRemove);
        }
    }

    public static void play(double x, double y, double z, byte[] audioData, float range, float volume) {
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
                AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, range);
                // 设置音量 (Gain)
                AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);

                AL10.alSourcePlay(sourceId);
                PLAYING_SOURCES.add(new AudioSource(sourceId, bufferId));

            } catch (Exception e) {
                LOGGER.error("Error playing OpenAL audio", e);
            } finally {
                MemoryUtil.memFree(audioBuffer);
            }
        });
    }

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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(audioBuffer, channelsBuffer, sampleRateBuffer);
            if (pcm != null) {
                int channels = channelsBuffer.get(0);
                int sampleRate = sampleRateBuffer.get(0);
                int format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                return new DecodedAudio(format, sampleRate, pcm);
            }
        }
        throw new IOException("无法将音频数据解码为 OGG Vorbis 格式。");
    }
}