// 请用此版本完整覆盖 StreamingAudioPlayer.java
package cn.tdogmc.tdogmc_voice.client;

import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.RequestDataC2SPacket;
import com.mojang.logging.LogUtils;
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
import java.util.concurrent.ConcurrentLinkedQueue;

public class StreamingAudioPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUFFER_COUNT = 4;
    private static final int PCM_CHUNK_SIZE_SAMPLES = 8192; // 8k samples per pull

    private final UUID streamId;
    private final int sourceId;
    private final int[] bufferIds;
    private final Queue<Integer> freeBuffers = new LinkedList<>();

    // 用于在内存中重新组装完整的OGG文件
    private final ByteArrayOutputStream oggDataStream = new ByteArrayOutputStream();
    private boolean decoderInitialized = false;

    // STBVorbis 流式解码器句柄
    private long vorbisHandle = MemoryUtil.NULL;
    private STBVorbisInfo vorbisInfo;
    private ByteBuffer vorbisDataBuffer; // 必须持有这个引用，防止被GC

    private boolean streamFileFinished = false; // 服务器是否已发完所有数据
    private boolean isDestroyed = false;

    public StreamingAudioPlayer(UUID streamId, double x, double y, double z) {
        this.streamId = streamId;
        this.sourceId = AL10.alGenSources();
        AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
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

    // 接收并拼接OGG数据块
    public void receiveData(byte[] data) {
        try {
            oggDataStream.write(data);
        } catch (IOException ignored) {}
        requestMoreData(); // 收到一块，就立即请求下一块
    }

    // 当服务器通知文件传输完毕时，初始化解码器
    public void markAsFinished() {
        this.streamFileFinished = true;
        initializeDecoder();
    }

    private void initializeDecoder() {
        if (decoderInitialized) return;

        byte[] allOggData = oggDataStream.toByteArray();
        if (allOggData.length == 0) {
            LOGGER.error("[{}] Cannot initialize decoder with empty OGG data.", streamId);
            destroy();
            return;
        }

        // 将拼接好的完整OGG数据放入持久化的ByteBuffer
        this.vorbisDataBuffer = MemoryUtil.memAlloc(allOggData.length).put(allOggData).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            // 使用 open_memory，因为它能从一个完整的内存块中创建流解码器
            this.vorbisHandle = STBVorbis.stb_vorbis_open_memory(vorbisDataBuffer, error, null);
            if (vorbisHandle == MemoryUtil.NULL) {
                LOGGER.error("[{}] Failed to open OGG memory stream, error: {}", streamId, error.get(0));
                destroy();
                return;
            }
            // 分配持久化内存给info
            this.vorbisInfo = STBVorbisInfo.malloc();
            STBVorbis.stb_vorbis_get_info(vorbisHandle, this.vorbisInfo);
            decoderInitialized = true;
            LOGGER.info("[{}] OGG decoder initialized successfully. Channels: {}, SampleRate: {}", streamId, vorbisInfo.channels(), vorbisInfo.sample_rate());
        }
    }

    public void update() {
        if (isDestroyed) return;

        // 只有解码器初始化后，才能进行播放相关的操作
        if (decoderInitialized) {
            // 释放已播放完的缓冲区
            int processedCount = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processedCount; i++) {
                freeBuffers.offer(AL10.alSourceUnqueueBuffers(sourceId));
            }

            // 填充所有可用的空闲缓冲区
            while (!freeBuffers.isEmpty()) {
                ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(PCM_CHUNK_SIZE_SAMPLES * vorbisInfo.channels());
                // 从解码器句柄中拉取PCM数据
                int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, vorbisInfo.channels(), pcmBuffer);

                if (samplesRead == 0) { // 没有更多PCM数据可读了
                    MemoryUtil.memFree(pcmBuffer);
                    break;
                }

                Integer bufferId = freeBuffers.poll();
                int format = (vorbisInfo.channels() == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

                pcmBuffer.limit(samplesRead * vorbisInfo.channels()); // 确保只上传有效数据
                AL10.alBufferData(bufferId, format, pcmBuffer, vorbisInfo.sample_rate());
                AL10.alSourceQueueBuffers(sourceId, bufferId);
                MemoryUtil.memFree(pcmBuffer);
            }

            // 维持播放状态
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0) {
                AL10.alSourcePlay(sourceId);
            }

            // 当解码器读完，并且所有缓冲区都播放完后销毁
            boolean isStreamDrained = STBVorbis.stb_vorbis_stream_length_in_samples(vorbisHandle) == STBVorbis.stb_vorbis_get_sample_offset(vorbisHandle);
            if (isStreamDrained && state != AL10.AL_PLAYING && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) == 0) {
                destroy();
                ClientStreamManager.removeStream(streamId);
            }
        }
    }

    // requestMoreData 和 destroy 方法保持不变
    private void requestMoreData() { PacketHandler.sendToServer(new RequestDataC2SPacket(streamId)); }
    public void destroy() { /* ... 保持不变 ... */ }
}