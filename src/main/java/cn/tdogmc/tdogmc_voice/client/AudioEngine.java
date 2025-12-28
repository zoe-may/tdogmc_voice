package cn.tdogmc.tdogmc_voice.client;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
import cn.tdogmc.tdogmc_voice.network.StartStreamS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AudioEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioEngine.class);
    private static final AudioEngine INSTANCE = new AudioEngine();
    private static final int MAX_SOURCES = 32;

    private final Queue<Integer> sourcePool = new ConcurrentLinkedQueue<>();
    private final Map<UUID, AudioStream> streams = new ConcurrentHashMap<>();

    private boolean isInitialized = false;

    public static AudioEngine getInstance() { return INSTANCE; }

    public void init() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onSoundEngineLoad(SoundEngineLoadEvent event) {
        LOGGER.info("Sound Engine loaded/reloaded. Resetting AudioEngine.");
        stopAll();
        sourcePool.clear();
        isInitialized = false;
        tryInitSourcePool();
    }

    private boolean tryInitSourcePool() {
        if (isInitialized) return true;
        try {
            if (AL.getCapabilities() == null) return false;
        } catch (Exception e) {
            return false;
        }

        sourcePool.clear();
        try {
            for (int i = 0; i < MAX_SOURCES; i++) {
                int source = AL10.alGenSources();
                if (AL10.alGetError() != AL10.AL_NO_ERROR) break;
                sourcePool.add(source);
            }
        } catch (Exception e) {
            LOGGER.error("Error generating OpenAL sources", e);
            return false;
        }

        if (!sourcePool.isEmpty()) {
            isInitialized = true;
            LOGGER.info("AudioEngine initialized successfully with {} hardware sources.", sourcePool.size());
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && isInitialized) {
            if (Minecraft.getInstance().level == null) return;

            Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Iterator<Map.Entry<UUID, AudioStream>> it = streams.entrySet().iterator();

            while (it.hasNext()) {
                AudioStream stream = it.next().getValue();
                if (stream.isDone()) {
                    stream.dispose();
                    it.remove();
                } else {
                    stream.tick(camPos);
                }
            }
        }
    }

    public void startStream(UUID id, double x, double y, double z, float range, float volume) {
        if (!isInitialized && !tryInitSourcePool()) return;
        Minecraft.getInstance().execute(() -> {
            if (streams.containsKey(id)) streams.remove(id).dispose();
            LOGGER.info("Client starting stream {} at {},{},{}", id, x, y, z);
            streams.put(id, new AudioStream(id, new Vec3(x, y, z), null, range, volume));
        });
    }

    public void startStream(UUID id, UUID entityId, float range, float volume) {
        if (!isInitialized && !tryInitSourcePool()) return;
        Minecraft.getInstance().execute(() -> {
            if (streams.containsKey(id)) streams.remove(id).dispose();
            LOGGER.info("Client starting stream {} following entity {}", id, entityId);
            streams.put(id, new AudioStream(id, null, entityId, range, volume));
        });
    }

    public void receiveData(UUID id, byte[] data) {
        AudioStream stream = streams.get(id);
        if (stream != null) stream.pushData(data);
    }

    public void endStream(UUID id) {
        AudioStream stream = streams.get(id);
        if (stream != null) stream.markFinished();
    }

    public void stopAll() {
        streams.values().forEach(AudioStream::dispose);
        streams.clear();
    }

    private class AudioStream {
        private final UUID id;
        private Vec3 staticPos;
        private final UUID entityId;
        private final float range;
        private final float volume;

        private ByteBuffer vorbisBuffer;
        private int writeCursor = 0;
        private final Queue<byte[]> incomingQueue = new ConcurrentLinkedQueue<>();

        private int sourceId = -1;
        private final int[] buffers = new int[3];
        private long vorbisHandle = MemoryUtil.NULL;
        private STBVorbisInfo info;

        private int totalSamplesDecoded = 0;

        private boolean inputFinished = false;
        private boolean disposed = false;
        private boolean hasStartedPlaying = false;

        public AudioStream(UUID id, Vec3 pos, UUID entityId, float range, float volume) {
            this.id = id;
            this.staticPos = pos;
            this.entityId = entityId;
            this.range = range;
            this.volume = volume;
            this.vorbisBuffer = MemoryUtil.memAlloc(1024 * 1024);
        }

        public void pushData(byte[] data) {
            incomingQueue.offer(data);
        }

        public void markFinished() {
            this.inputFinished = true;
        }

        public boolean isDone() {
            if (disposed) return true;
            if (inputFinished && incomingQueue.isEmpty() && sourceId != -1) {
                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                return state == AL10.AL_STOPPED && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) == 0;
            }
            return false;
        }

        public void tick(Vec3 listenerPos) {
            if (disposed) return;

            boolean memoryReallocated = false;
            while (!incomingQueue.isEmpty()) {
                byte[] data = incomingQueue.poll();

                if (writeCursor == 0 && data.length > 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
                    LOGGER.warn("Stream {} appears to be WAV format. Skipped.", id);
                    dispose();
                    return;
                }

                if (vorbisBuffer.remaining() < data.length) {
                    if (vorbisHandle != MemoryUtil.NULL) {
                        STBVorbis.stb_vorbis_close(vorbisHandle);
                        vorbisHandle = MemoryUtil.NULL;
                    }

                    int newCap = Math.max(vorbisBuffer.capacity() * 2, writeCursor + data.length + 64 * 1024);
                    ByteBuffer newBuf = MemoryUtil.memRealloc(vorbisBuffer, newCap);
                    if (newBuf == null) {
                        LOGGER.error("Failed to realloc memory for stream {}", id);
                        dispose();
                        return;
                    }
                    vorbisBuffer = newBuf;
                    memoryReallocated = true;
                }
                vorbisBuffer.position(writeCursor);
                vorbisBuffer.put(data);
                writeCursor += data.length;
            }

            if (writeCursor > 0 && (vorbisHandle == MemoryUtil.NULL || memoryReallocated)) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer error = stack.mallocInt(1);
                    ByteBuffer slice = vorbisBuffer.duplicate();
                    slice.position(0);
                    slice.limit(writeCursor);

                    long handle = STBVorbis.stb_vorbis_open_memory(slice, error, null);

                    if (handle != MemoryUtil.NULL) {
                        vorbisHandle = handle;
                        if (info == null) {
                            info = STBVorbisInfo.malloc();
                            STBVorbis.stb_vorbis_get_info(vorbisHandle, info);
                        }
                        if (memoryReallocated && totalSamplesDecoded > 0) {
                            STBVorbis.stb_vorbis_seek(vorbisHandle, totalSamplesDecoded);
                        }
                    }
                }
            }

            if (sourceId == -1 && vorbisHandle != MemoryUtil.NULL && !sourcePool.isEmpty()) {
                Integer s = sourcePool.poll();
                if (s != null) {
                    sourceId = s;
                    buffers[0] = AL10.alGenBuffers();
                    buffers[1] = AL10.alGenBuffers();
                    buffers[2] = AL10.alGenBuffers();
                    AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
                    AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
                    AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                    AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 16.0f);
                    AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, range);
                    LOGGER.info("Stream {} assigned OpenAL source {}", id, sourceId);
                }
            }

            if (sourceId != -1) {
                Vec3 pos = staticPos;
                if (entityId != null && Minecraft.getInstance().level != null) {
                    for (Entity en : Minecraft.getInstance().level.entitiesForRendering()) {
                        if (en.getUUID().equals(entityId)) {
                            pos = en.position();
                            break;
                        }
                    }
                }
                if (pos != null) {
                    AL10.alSource3f(sourceId, AL10.AL_POSITION, (float)pos.x, (float)pos.y, (float)pos.z);
                }

                streamAudio();
            }
        }

        private void streamAudio() {
            if (vorbisHandle == MemoryUtil.NULL) return;

            int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buf = AL10.alSourceUnqueueBuffers(sourceId);
                fillBuffer(buf);
            }

            if (!hasStartedPlaying) {
                boolean allFilled = true;
                for (int buf : buffers) {
                    if (!fillBuffer(buf)) {
                        allFilled = false;
                        break;
                    }
                }
                if (AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0) {
                    AL10.alSourcePlay(sourceId);
                    hasStartedPlaying = true;
                }
            } else {
                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED && !inputFinished) {
                    AL10.alSourcePlay(sourceId);
                }
            }
        }

        // 【关键修复】使用 malloc 分配堆外内存，不再使用 Stack 分配大数据，防止 Stack Overflow
        private boolean fillBuffer(int bufferId) {
            int samples = 4096;
            // 计算需要的字节数 (samples * channels * sizeof(short))
            int sizeBytes = samples * info.channels() * 2;

            // 使用 memAlloc 直接在堆外分配
            ShortBuffer pcm = MemoryUtil.memAllocShort(samples * info.channels());

            try {
                int count = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, info.channels(), pcm);

                if (count > 0) {
                    totalSamplesDecoded += count;
                    // alBufferData 会复制数据，所以这里的 pcm 马上就可以释放
                    AL10.alBufferData(bufferId, info.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, pcm, info.sample_rate());
                    AL10.alSourceQueueBuffers(sourceId, bufferId);
                    return true;
                }
                return false;
            } finally {
                // 必须在 finally 中释放，确保不泄露
                if (pcm != null) {
                    MemoryUtil.memFree(pcm);
                }
            }
        }

        public void dispose() {
            if (disposed) return;
            disposed = true;

            if (sourceId != -1) {
                AL10.alSourceStop(sourceId);
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                AL10.alDeleteBuffers(buffers);
                sourcePool.add(sourceId);
                sourceId = -1;
            }

            if (info != null) {
                info.free();
                info = null;
            }
            if (vorbisHandle != MemoryUtil.NULL) {
                STBVorbis.stb_vorbis_close(vorbisHandle);
                vorbisHandle = MemoryUtil.NULL;
            }
            if (vorbisBuffer != null) {
                MemoryUtil.memFree(vorbisBuffer);
                vorbisBuffer = null;
            }
            LOGGER.info("Stream {} disposed", id);
        }
    }
}