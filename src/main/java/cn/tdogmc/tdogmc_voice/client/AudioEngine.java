package cn.tdogmc.tdogmc_voice.client;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
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
        LOGGER.info("[AudioEngine] Sound Engine loaded. Resetting.");
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
            LOGGER.info("[AudioEngine] Initialized with {} sources.", sourcePool.size());
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
                stream.tick(camPos);

                if (stream.isDone()) {
                    stream.dispose();
                    it.remove();
                }
            }
        }
    }

    public void startStream(UUID id, double x, double y, double z, float range, float volume, float pitch) {
        if (!isInitialized && !tryInitSourcePool()) return;
        Minecraft.getInstance().execute(() -> {
            if (streams.containsKey(id)) streams.remove(id).dispose();
            LOGGER.info("[Stream {}] START (Static Pos)", id);
            streams.put(id, new AudioStream(id, new Vec3(x, y, z), null, range, volume, pitch));
        });
    }

    public void startStream(UUID id, UUID entityId, float range, float volume, float pitch) {
        if (!isInitialized && !tryInitSourcePool()) return;
        Minecraft.getInstance().execute(() -> {
            if (streams.containsKey(id)) streams.remove(id).dispose();
            LOGGER.info("[Stream {}] START (Follow Entity)", id);
            streams.put(id, new AudioStream(id, null, entityId, range, volume, pitch));
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
        LOGGER.info("[AudioEngine] Stopping all streams.");
        streams.values().forEach(AudioStream::dispose);
        streams.clear();
    }

    private class AudioStream {
        private final UUID id;
        private Vec3 staticPos;
        private final UUID entityId;
        private final float range;
        private final float volume;
        private final float pitch;

        private ByteBuffer vorbisBuffer;
        private int writeCursor = 0;
        private int lastOpenCursor = 0;
        private final Queue<byte[]> incomingQueue = new ConcurrentLinkedQueue<>();

        private int sourceId = -1;
        private final int[] buffers = new int[3];
        private long vorbisHandle = MemoryUtil.NULL;
        private STBVorbisInfo info;

        private int totalSamplesDecoded = 0;
        private boolean inputFinished = false;
        private boolean decoderFinished = false;
        private boolean disposed = false;

        private long bufferAddress = 0;

        public AudioStream(UUID id, Vec3 pos, UUID entityId, float range, float volume, float pitch) {
            this.id = id;
            this.staticPos = pos;
            this.entityId = entityId;
            this.range = range;
            this.volume = volume;
            this.pitch = pitch;
            this.vorbisBuffer = MemoryUtil.memAlloc(4 * 1024 * 1024);
            this.bufferAddress = MemoryUtil.memAddress(vorbisBuffer);
        }

        public void pushData(byte[] data) {
            incomingQueue.offer(data);
        }

        public void markFinished() {
            if (!this.inputFinished) {
                this.inputFinished = true;
                if (ModConfig.LOG_BASIC_INFO.get()) LOGGER.info("[Stream {}] Received EOS signal.", id);
            }
        }

        public boolean isDone() {
            if (disposed) return true;
            if (inputFinished && decoderFinished && sourceId != -1) {
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                if (queued == 0 || state == AL10.AL_STOPPED) {
                    return true;
                }
            }
            return false;
        }

        public void tick(Vec3 listenerPos) {
            if (disposed) return;

            boolean pointerChanged = false;
            boolean hasNewData = false;

            while (!incomingQueue.isEmpty()) {
                byte[] data = incomingQueue.poll();
                if (vorbisBuffer.remaining() < data.length) {
                    int newCap = Math.max(vorbisBuffer.capacity() * 2, writeCursor + data.length + 65536);
                    ByteBuffer newBuf = MemoryUtil.memRealloc(vorbisBuffer, newCap);
                    if (newBuf == null) { dispose(); return; }
                    vorbisBuffer = newBuf;
                    pointerChanged = (MemoryUtil.memAddress(vorbisBuffer) != bufferAddress);
                    bufferAddress = MemoryUtil.memAddress(vorbisBuffer);
                }
                vorbisBuffer.position(writeCursor);
                vorbisBuffer.put(data);
                writeCursor += data.length;
                hasNewData = true;
            }

            boolean needsReopen = (vorbisHandle == MemoryUtil.NULL);
            if (!needsReopen && (pointerChanged || (hasNewData && writeCursor > lastOpenCursor))) {
                needsReopen = true;
            }

            if (needsReopen && writeCursor > 0) {
                reopenDecoder();
            }

            if (sourceId == -1 && vorbisHandle != MemoryUtil.NULL && !sourcePool.isEmpty()) {
                initOpenALSource();
            }

            if (sourceId != -1) {
                updatePosition();
                streamAudio();
            }
        }

        private void reopenDecoder() {
            if (vorbisHandle != MemoryUtil.NULL) {
                STBVorbis.stb_vorbis_close(vorbisHandle);
                vorbisHandle = MemoryUtil.NULL;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer error = stack.mallocInt(1);
                ByteBuffer slice = vorbisBuffer.duplicate();
                slice.position(0).limit(writeCursor);

                long h = STBVorbis.stb_vorbis_open_memory(slice, error, null);
                if (h != MemoryUtil.NULL) {
                    vorbisHandle = h;
                    lastOpenCursor = writeCursor;
                    if (info == null) {
                        info = STBVorbisInfo.malloc();
                        STBVorbis.stb_vorbis_get_info(vorbisHandle, info);
                    }

                    if (totalSamplesDecoded > 0) {
                        STBVorbis.stb_vorbis_seek(vorbisHandle, totalSamplesDecoded);
                    }
                }
            }
        }

        private void initOpenALSource() {
            Integer s = sourcePool.poll();
            if (s != null) {
                sourceId = s;
                buffers[0] = AL10.alGenBuffers();
                buffers[1] = AL10.alGenBuffers();
                buffers[2] = AL10.alGenBuffers();
                AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
                AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
                AL10.alSourcef(sourceId, AL10.AL_PITCH, pitch);
                AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, range);
                AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 16.0f);
            }
        }

        private void updatePosition() {
            Vec3 pos = staticPos;
            if (entityId != null && Minecraft.getInstance().level != null) {
                for (Entity en : Minecraft.getInstance().level.entitiesForRendering()) {
                    if (en.getUUID().equals(entityId)) { pos = en.position(); break; }
                }
            }
            if (pos != null) AL10.alSource3f(sourceId, AL10.AL_POSITION, (float)pos.x, (float)pos.y, (float)pos.z);
        }

        private void streamAudio() {
            if (vorbisHandle == MemoryUtil.NULL || decoderFinished) return;

            int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buf = AL10.alSourceUnqueueBuffers(sourceId);
                if (buf != 0) fillBuffer(buf);
            }

            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            if (queued == 0 && !decoderFinished) {
                fillBuffer(buffers[0]);
                fillBuffer(buffers[1]);
                fillBuffer(buffers[2]);
            }

            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0 && state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                AL10.alSourcePlay(sourceId);
            }
        }

        private void fillBuffer(int bufferId) {
            if (decoderFinished || vorbisHandle == MemoryUtil.NULL) return;

            int samples = 8192;
            // 使用 memAllocShort 分配的内存是未初始化的（Dirty Memory）
            ShortBuffer pcm = MemoryUtil.memAllocShort(samples * info.channels());

            try {
                int count = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, info.channels(), pcm);

                if (count > 0) {
                    totalSamplesDecoded += count;

                    // 【核心修复】设置 limit，只让 OpenAL 读取实际解码的数据量
                    // 如果不加这一行，OpenAL 会读取整个 8192 长度的 Buffer，末尾全是垃圾数据
                    pcm.position(0).limit(count * info.channels());

                    AL10.alBufferData(bufferId, info.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, pcm, info.sample_rate());
                    AL10.alSourceQueueBuffers(sourceId, bufferId);
                } else {
                    if (inputFinished) {
                        decoderFinished = true;
                    }
                }
            } finally {
                MemoryUtil.memFree(pcm);
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
            if (info != null) { info.free(); info = null; }
            if (vorbisHandle != MemoryUtil.NULL) { STBVorbis.stb_vorbis_close(vorbisHandle); vorbisHandle = MemoryUtil.NULL; }
            if (vorbisBuffer != null) { MemoryUtil.memFree(vorbisBuffer); vorbisBuffer = null; }
        }
    }
}