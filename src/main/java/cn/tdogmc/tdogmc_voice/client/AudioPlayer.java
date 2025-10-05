package cn.tdogmc.tdogmc_voice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 客户端音频播放器工具类
 * 负责将字节数组形式的音频数据播放出来
 */
public class AudioPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioPlayer.class);

    public static void play(byte[] audioData) {
        // 音频播放是一个耗时操作（阻塞操作），如果直接在主线程（Render thread）播放，
        // 会导致游戏画面卡死直到音乐播放完毕。
        // 因此，我们必须创建一个新的线程来专门负责播放音频。
        new Thread(() -> {
            try {
                // 1. 将字节数组包装成一个输入流
                ByteArrayInputStream inputStream = new ByteArrayInputStream(audioData);

                // 2. 从输入流中获取音频输入流 (AudioInputStream)
                // AudioSystem 会自动检测音频格式 (如 .ogg, .wav)
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(inputStream);

                // 3. 获取音频格式信息
                AudioFormat format = audioStream.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                // 4. 获取一个用于播放的音频输出线 (SourceDataLine)
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start(); // 开始播放

                LOGGER.info("开始播放音频...");

                // 5. 将音频数据从流中写入输出线进行播放
                byte[] buffer = new byte[4096];
                int bytesRead = 0;
                while ((bytesRead = audioStream.read(buffer)) != -1) {
                    line.write(buffer, 0, bytesRead);
                }

                // 6. 等待所有数据播放完毕后，关闭资源
                line.drain();
                line.close();
                audioStream.close();

                LOGGER.info("音频播放完毕。");

            } catch (UnsupportedAudioFileException e) {
                LOGGER.error("不支持的音频文件格式！请确保是标准的 .ogg 或 .wav 文件。", e);
            } catch (IOException e) {
                LOGGER.error("音频播放时发生IO异常。", e);
            } catch (LineUnavailableException e) {
                LOGGER.error("音频输出线不可用，可能没有可用的音频设备。", e);
            }
        }).start(); // 立即启动这个新线程
    }
}