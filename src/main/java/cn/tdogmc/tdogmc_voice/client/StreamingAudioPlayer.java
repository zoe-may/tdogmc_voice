package cn.tdogmc.tdogmc_voice.client;

import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.RequestDataC2SPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamingAudioPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUFFER_COUNT = 4;
    private static final int PCM_CHUNK_SIZE_SAMPLES = 8192;

    private final UUID streamId;
    private final int sourceId;
    private final int[] bufferIds;
    private final Queue<Integer> freeBuffers = new LinkedList<>();
    private final UUID followedEntityId;

    // --- 核心状态变量 ---
    private final ByteArrayOutputStream oggDataStream = new ByteArrayOutputStream();
    private ByteBuffer vorbisDataBuffer;
    private long vorbisHandle = MemoryUtil.NULL;
    private STBVorbisInfo vorbisInfo;
    private volatile boolean isDestroyed = false;

    private final AtomicBoolean streamFileFinished = new AtomicBoolean(false);
    private final AtomicBoolean hasNewData = new AtomicBoolean(false);
    private long lastSampleOffset = 0;

    // 构造函数1: 静态位置
    public StreamingAudioPlayer(UUID streamId, double x, double y, double z) {
        this(streamId, null, x, y, z, true);
    }

    // 构造函数2: 实体跟随
    public StreamingAudioPlayer(UUID streamId, UUID entityToFollow) {
        this(streamId, entityToFollow, 0, 0, 0, false);
    }

    private StreamingAudioPlayer(UUID streamId, UUID entityToFollow, double x, double y, double z, boolean setInitialPosition) {
        this.streamId = streamId;
        this.followedEntityId = entityToFollow;

        this.sourceId = AL10.alGenSources();
        if (setInitialPosition) {
            AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        }
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
        AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 16.0f);
        AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 64.0f);

        this.bufferIds = new int[BUFFER_COUNT];
        for (int i = 0; i < BUFFER_COUNT; i++) {
            bufferIds[i] = AL10.alGenBuffers();
            freeBuffers.add(bufferIds[i]);
        }
        requestMoreData();
    }

    public void receiveData(byte[] data) {
        if (isDestroyed) return;
        try {
            oggDataStream.write(data);
            hasNewData.set(true);
        } catch (IOException ignored) {}
        requestMoreData();
    }

    public void markAsFinished() {
        streamFileFinished.set(true);
    }

    public void update() {
        if (isDestroyed) return;

        updateEntityPosition();

        // 检查是否有新数据到达，并据此刷新解码器
        if (hasNewData.getAndSet(false)) {
            refreshDecoder();
        }

        if (vorbisHandle != MemoryUtil.NULL) {
            // 回收已播放完的缓冲区
            int processedCount = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processedCount; i++) {
                freeBuffers.offer(AL10.alSourceUnqueueBuffers(sourceId));
            }

            // 填充所有可用的缓冲区
            fillBuffers();
        }

        // 检查并设置播放状态
        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0) {
            AL10.alSourcePlay(sourceId);
        }

        // 检查流是否已完全结束
        checkStreamEnd();
    }

    private void refreshDecoder() {
        if (vorbisHandle != MemoryUtil.NULL) {
            lastSampleOffset = STBVorbis.stb_vorbis_get_sample_offset(vorbisHandle);
            STBVorbis.stb_vorbis_close(vorbisHandle);
            vorbisHandle = MemoryUtil.NULL;
            if (vorbisInfo != null) {
                vorbisInfo.free();
                vorbisInfo = null;
            }
            if (vorbisDataBuffer != null) {
                MemoryUtil.memFree(vorbisDataBuffer);
                vorbisDataBuffer = null;
            }
        }

        byte[] currentOggData = oggDataStream.toByteArray();
        if (currentOggData.length == 0) return;

        vorbisDataBuffer = MemoryUtil.memAlloc(currentOggData.length).put(currentOggData).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            vorbisHandle = STBVorbis.stb_vorbis_open_memory(vorbisDataBuffer, error, null);

            if (vorbisHandle != MemoryUtil.NULL) {
                vorbisInfo = STBVorbisInfo.malloc();
                STBVorbis.stb_vorbis_get_info(vorbisHandle, vorbisInfo);
                STBVorbis.stb_vorbis_seek(vorbisHandle, (int) lastSampleOffset); // 跳转回上次播放的位置
            } else {
                LOGGER.warn("[{}] Failed to open OGG memory stream with {} bytes. Error: {}", streamId, currentOggData.length, error.get(0));
            }
        }
    }

    private void fillBuffers() {
        while (!freeBuffers.isEmpty()) {
            ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(PCM_CHUNK_SIZE_SAMPLES * vorbisInfo.channels());
            int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, vorbisInfo.channels(), pcmBuffer);

            if (samplesRead == 0) {
                MemoryUtil.memFree(pcmBuffer);
                break; // 没有更多样本可读了
            }

            Integer bufferId = freeBuffers.poll();
            if (bufferId == null) {
                MemoryUtil.memFree(pcmBuffer);
                break;
            }

            pcmBuffer.limit(samplesRead * vorbisInfo.channels());
            int format = (vorbisInfo.channels() == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            AL10.alBufferData(bufferId, format, pcmBuffer, vorbisInfo.sample_rate());
            AL10.alSourceQueueBuffers(sourceId, bufferId);
            MemoryUtil.memFree(pcmBuffer);
        }
    }

    private void updateEntityPosition() {
        if (followedEntityId != null) {
            Entity entity = findEntityByUUID(followedEntityId);
            if (entity != null) {
                Vec3 pos = entity.position();
                AL10.alSource3f(sourceId, AL10.AL_POSITION, (float)pos.x, (float)entity.getEyeY(), (float)pos.z);
            } else {
                destroy();
                ClientStreamManager.removeStream(streamId);
            }
        }
    }

    private void checkStreamEnd() {
        if (streamFileFinished.get() && vorbisHandle != MemoryUtil.NULL) {
            long currentOffset = STBVorbis.stb_vorbis_get_sample_offset(vorbisHandle);
            long totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(vorbisHandle);

            // 当所有数据都已解码，并且OpenAL缓冲区也播放完毕时
            if (currentOffset >= totalSamples && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) == 0) {
                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING) {
                    LOGGER.info("[{}] Stream finished playing.", streamId);
                    destroy();
                    ClientStreamManager.removeStream(streamId);
                }
            }
        }
    }

    private void requestMoreData() {
        if (!streamFileFinished.get()) {
            PacketHandler.sendToServer(new RequestDataC2SPacket(streamId));
        }
    }

    private Entity findEntityByUUID(UUID uuid) {
        if (Minecraft.getInstance().level == null) return null;
        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    public void destroy() {
        if (isDestroyed) return;
        isDestroyed = true;

        Minecraft.getInstance().execute(() -> {
            AL10.alSourceStop(sourceId);

            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            if (queued > 0) {
                IntBuffer buffers = MemoryUtil.memAllocInt(queued);
                AL10.alSourceUnqueueBuffers(sourceId, buffers);
                MemoryUtil.memFree(buffers);
            }

            AL10.alDeleteSources(sourceId);
            for (int bufferId : bufferIds) {
                AL10.alDeleteBuffers(bufferId);
            }

            if (vorbisHandle != MemoryUtil.NULL) {
                STBVorbis.stb_vorbis_close(vorbisHandle);
                vorbisHandle = MemoryUtil.NULL;
            }
            if (vorbisInfo != null) {
                vorbisInfo.free();
            }
            if (vorbisDataBuffer != null) {
                MemoryUtil.memFree(vorbisDataBuffer);
            }
            LOGGER.info("[{}] Player destroyed.", streamId);
        });
    }
}