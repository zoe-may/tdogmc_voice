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
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();
                    boolean updateNeeded = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() != StandardWatchEventKinds.OVERFLOW) updateNeeded = true;
                    }
                    if (updateNeeded) scanFiles();
                    if (!key.reset()) break;
                }
            } catch (IOException | InterruptedException ignored) {}
        }, "Sound-Watcher-Thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }
}