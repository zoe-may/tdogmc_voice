package cn.tdogmc.tdogmc_voice;

import cn.tdogmc.tdogmc_voice.config.ModConfig; // 确保导入 ModConfig
import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.PlayAudioDataPacket;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import cn.tdogmc.tdogmc_voice.client.OpenALPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// @Mod 注解告诉 Forge 这是一个模组的主类。值必须和 mods.toml 中的 modId 一致。
@Mod(Tdogmc_voice.MODID)
public class Tdogmc_voice {

    // 定义一个常量来存储我们的 Mod ID，方便复用，避免手滑打错。
    public static final String MODID = "tdogmc_voice";
    // 创建一个日志记录器，方便我们输出信息到控制台进行调试。
    private static final Logger LOGGER = LogUtils.getLogger();

    public Tdogmc_voice() {
        MinecraftForge.EVENT_BUS.register(this);
        PacketHandler.register();

        // 注册 FML 的事件总线，用于监听客户端/服务端启动等事件
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

        // 【这里是你已有的代码，保持不变】
        // 注册我们的配置文件
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SPEC, "tdogmc_voice-server.toml");
    }

    // 定义我们存放音频的文件夹
    private static final Path SOUNDS_DIRECTORY = Path.of("sounds");

    /**
     * 这是我们的自定义建议提供者。
     * 它负责扫描 sounds 目录并提供文件名列表作为 Tab 补全建议。
     */
    private static final SuggestionProvider<CommandSourceStack> SOUND_FILE_SUGGESTIONS = (context, builder) -> {
        // 【这部分代码保持不变】
        if (!Files.exists(SOUNDS_DIRECTORY)) {
            try {
                Files.createDirectories(SOUNDS_DIRECTORY);
            } catch (IOException e) {
                return builder.buildFuture();
            }
        }

        try (Stream<Path> stream = Files.walk(SOUNDS_DIRECTORY)) {
            List<String> soundFiles = stream
                    .filter(Files::isRegularFile) // 过滤，只保留文件
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(".wav") || fileName.endsWith(".ogg"); // 过滤文件类型
                    })
                    // --- 核心改动 ---
                    // 计算文件相对于 sounds 目录的相对路径
                    .map(path -> SOUNDS_DIRECTORY.relativize(path).toString().replace('\\', '/')) // 转换为相对路径字符串，并将Windows的'\'替换为'/'
                    .collect(Collectors.toList());

            return SharedSuggestionProvider.suggest(soundFiles, builder);

        } catch (IOException e) {
            LOGGER.error("无法读取 sounds 文件夹以提供建议", e);
            return builder.buildFuture();
        }
        // --- 修改到这里结束 ---
    };

    private void onClientSetup(final FMLClientSetupEvent event) {
        // 【这部分代码保持不变】
        LOGGER.info("Initializing Client Sound Manager...");
        OpenALPlayer.init();
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("playaudio3d")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("position", Vec3Argument.vec3())
                        .then(Commands.argument("soundFile", StringArgumentType.string())
                                // 【修改点1】将 SuggestionProvider 添加到指令参数
                                .suggests(SOUND_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    Vec3 position = Vec3Argument.getVec3(context, "position");
                                    String soundFileName = StringArgumentType.getString(context, "soundFile");

                                    String normalizedSoundPath = soundFileName.replace('\\', '/');

                                    // 严禁路径中包含 ".." 来返回上级目录
                                    if (normalizedSoundPath.contains("..")) {
                                        source.sendFailure(Component.literal("错误：文件路径不能包含 '..'！"));
                                        return 0;
                                    }
                                    // 严禁路径以 '/' 开头，防止绝对路径攻击
                                    if (normalizedSoundPath.startsWith("/")) {
                                        source.sendFailure(Component.literal("错误：文件路径不能以 '/' 开头！"));
                                        return 0;
                                    }

                                    try {
                                        // --- 修改这里的路径构建 ---
                                        // 直接将 sounds 目录与用户提供的相对路径拼接起来
                                        Path audioPath = SOUNDS_DIRECTORY.resolve(normalizedSoundPath);

                                        // 添加一个额外的安全检查，确保最终解析的路径确实在 sounds 目录之内
                                        if (!audioPath.toAbsolutePath().normalize().startsWith(SOUNDS_DIRECTORY.toAbsolutePath().normalize())) {
                                            source.sendFailure(Component.literal("错误：检测到非法的路径遍历尝试！"));
                                            return 0;
                                        }

                                        if (!Files.exists(audioPath) || !Files.isRegularFile(audioPath)) {
                                            source.sendFailure(Component.literal("错误: 文件 " + normalizedSoundPath + " 未找到或不是一个有效文件！"));
                                            return 0;
                                        }

                                        byte[] audioData = Files.readAllBytes(audioPath);
                                        PlayAudioDataPacket packet = new PlayAudioDataPacket(audioData, position.x, position.y, position.z);

                                        ServerLevel level = source.getLevel();
                                        for (ServerPlayer player : level.players()) {
                                            if (player.position().distanceToSqr(position) < 64 * 64) {
                                                PacketHandler.sendToPlayer(packet, player);
                                            }
                                        }

                                        // --- 【修改点2】在这里添加对 debug 模式的判断 ---
                                        // 只有当 debugMode 开启时，才发送成功反馈消息和打印日志
                                        if (ModConfig.DEBUG_MODE.get()) {
                                            source.sendSuccess(() -> Component.literal("在坐标 " + String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z) + " 播放 " + soundFileName), true);
                                            LOGGER.info("在坐标 ({}, {}, {}) 播放了音频文件 {}", String.format("%.1f", position.x), String.format("%.1f", position.y), String.format("%.1f", position.z), soundFileName);
                                        }

                                        return 1;

                                    } catch (IOException e) {
                                        // 错误信息应该总是显示
                                        source.sendFailure(Component.literal("读取音频文件时发生错误！请检查后台日志。"));
                                        LOGGER.error("读取音频文件 {} 时出错:", soundFileName, e);
                                        return 0;
                                    }
                                })
                        )
                )
        );
    }
}