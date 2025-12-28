package cn.tdogmc.tdogmc_voice.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public class SoundFileCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path SOUNDS_DIR = Path.of("sounds");
    private static final List<String> CACHED_FILES = new CopyOnWriteArrayList<>();
    private static Thread watcherThread;

    public static void init() {
        if (!Files.exists(SOUNDS_DIR)) {
            try {
                Files.createDirectories(SOUNDS_DIR);
            } catch (IOException e) {
                LOGGER.error("Failed to create sounds directory", e);
                return;
            }
        }
        scanFiles();
        startWatcher();
    }

    public static List<String> getSuggestions() {
        return Collections.unmodifiableList(CACHED_FILES);
    }

    public static Path getPath(String filename) {
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return null; // 防止路径遍历
        }
        Path path = SOUNDS_DIR.resolve(filename);
        return Files.exists(path) ? path : null;
    }

    private static void scanFiles() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SOUNDS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".wav") || name.endsWith(".ogg");
                    })
                    .forEach(path -> found.add(SOUNDS_DIR.relativize(path).toString().replace('\\', '/')));

            CACHED_FILES.clear();
            CACHED_FILES.addAll(found);
        } catch (IOException e) {
            LOGGER.error("Failed to scan sound files", e);
        }
    }

    private static void startWatcher() {
        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                SOUNDS_DIR.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

                long lastChangeTime = 0;
                boolean pendingUpdate = false;

                while (!Thread.currentThread().isInterrupted()) {
                    // 使用带超时的 poll，每 500ms 醒来检查一次是否需要更新
                    WatchKey key = watchService.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);

                    if (key != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() != StandardWatchEventKinds.OVERFLOW) {
                                lastChangeTime = System.currentTimeMillis();
                                pendingUpdate = true;
                            }
                        }
                        if (!key.reset()) break;
                    }

                    // 防抖动逻辑：只有当有更新挂起，且距离上次变化超过 500ms 时才执行扫描
                    if (pendingUpdate && (System.currentTimeMillis() - lastChangeTime > 500)) {
                        scanFiles();
                        pendingUpdate = false;
                        LOGGER.info("Sound cache updated.");
                    }
                }
            } catch (IOException | InterruptedException ignored) {}
        }, "Sound-Watcher-Thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }
}