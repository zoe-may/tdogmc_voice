package cn.tdogmc.tdogmc_voice;

import cn.tdogmc.tdogmc_voice.config.ModConfig;
import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.PlayAudioDataPacket;
import cn.tdogmc.tdogmc_voice.network.StopAllStreamsS2CPacket;
import cn.tdogmc.tdogmc_voice.stream.ServerStreamManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
import net.minecraftforge.eventbus.api.IEventBus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod(Tdogmc_voice.MODID)
public class Tdogmc_voice {

    public static final String MODID = "tdogmc_voice";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float DEFAULT_AUDIO_RANGE = 64.0f;
    private static final float DEFAULT_AUDIO_VOLUME = 1.0f;

    public Tdogmc_voice() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        PacketHandler.register();
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SPEC, "tdogmc_voice-server.toml");
    }

    private static final Path SOUNDS_DIRECTORY = Path.of("sounds");
    private static final SuggestionProvider<CommandSourceStack> SOUND_FILE_SUGGESTIONS = (context, builder) -> {
        if (!Files.exists(SOUNDS_DIRECTORY)) { try { Files.createDirectories(SOUNDS_DIRECTORY); } catch (IOException e) { return builder.buildFuture(); } } try (Stream<Path> stream = Files.walk(SOUNDS_DIRECTORY)) { List<String> soundFiles = stream .filter(Files::isRegularFile) .filter(path -> { String fileName = path.getFileName().toString(); return fileName.endsWith(".wav") || fileName.endsWith(".ogg"); }) .map(path -> SOUNDS_DIRECTORY.relativize(path).toString().replace('\\', '/')) .collect(Collectors.toList()); return SharedSuggestionProvider.suggest(soundFiles, builder); } catch (IOException e) { LOGGER.error("无法读取 sounds 文件夹以提供建议", e); return builder.buildFuture(); }
    };

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Tdogmc_voice client setup event fired.");
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        registerCommandWithOptionalArgs(dispatcher, "playaudio3d", Commands.argument("position", Vec3Argument.vec3()), this::executePlayAudio3dCommand);
        registerCommandWithOptionalArgs(dispatcher, "playstream", Commands.argument("position", Vec3Argument.vec3()), this::executePlayStreamCommand);
        registerCommandWithOptionalArgs(dispatcher, "playstream_entity", Commands.argument("target", EntityArgument.entity()), this::executePlayStreamEntityCommand);

        dispatcher.register(Commands.literal("stopstreams")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    if (source.getEntity() instanceof ServerPlayer player) {
                        PacketHandler.sendToPlayer(new StopAllStreamsS2CPacket(), player);
                        if (ModConfig.DEBUG_MODE.get()) { source.sendSuccess(() -> Component.literal("正在停止你客户端上的所有音频流。"), true); }
                        return 1;
                    } else {
                        source.sendFailure(Component.literal("从控制台执行此指令需要指定一个目标玩家 (例如 /stopstreams @a)"));
                        return 0;
                    }
                })
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
                            for (ServerPlayer player : targets) {
                                PacketHandler.sendToPlayer(new StopAllStreamsS2CPacket(), player);
                            }
                            if (ModConfig.DEBUG_MODE.get()) { source.sendSuccess(() -> Component.literal("已向 " + targets.size() + " 名玩家发送停止音频流的指令。"), true); }
                            return targets.size();
                        })
                )
        );
    }

    private <T> void registerCommandWithOptionalArgs(CommandDispatcher<CommandSourceStack> dispatcher, String commandName, RequiredArgumentBuilder<CommandSourceStack, T> firstArgument, CommandExecutor executor) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(commandName)
                .requires(source -> source.hasPermission(2));

        RequiredArgumentBuilder<CommandSourceStack, String> soundFileArg = Commands.argument("soundFile", StringArgumentType.string())
                .suggests(SOUND_FILE_SUGGESTIONS);

        // --- 核心修正：构建一个可以互相链接的指令树 ---

        // 1. 定义最终执行的节点
        var rangeValueNode = Commands.argument("range", FloatArgumentType.floatArg(0)).executes(executor::execute);
        var volumeValueNode = Commands.argument("volume", FloatArgumentType.floatArg(0)).executes(executor::execute);

        // 2. 让它们可以互相链接
        // 在 `-r <range>` 之后，可以跟 `-v <volume>`
        rangeValueNode.then(
                Commands.literal("-v").then(
                        Commands.argument("volume", FloatArgumentType.floatArg(0)).executes(executor::execute)
                )
        );
        // 在 `-v <volume>` 之后，可以跟 `-r <range>`
        volumeValueNode.then(
                Commands.literal("-r").then(
                        Commands.argument("range", FloatArgumentType.floatArg(0)).executes(executor::execute)
                )
        );

        // 3. 将这些分支挂载到 soundFile 参数下
        soundFileArg.then(Commands.literal("-r").then(rangeValueNode));
        soundFileArg.then(Commands.literal("-v").then(volumeValueNode));

        // 4. 不要忘记最基础的情况：只有 soundFile 参数
        soundFileArg.executes(executor::execute);

        // 5. 组装最终指令
        command.then(firstArgument.then(soundFileArg));
        dispatcher.register(command);
    }

    private int executePlayAudio3dCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        float range = getFloatArgument(context, "range", DEFAULT_AUDIO_RANGE);
        float volume = getFloatArgument(context, "volume", DEFAULT_AUDIO_VOLUME);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        String soundFile = StringArgumentType.getString(context, "soundFile");

        return executePlayAudio3dLogic(context.getSource(), position, soundFile, range, volume);
    }

    private int executePlayStreamCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        float range = getFloatArgument(context, "range", DEFAULT_AUDIO_RANGE);
        float volume = getFloatArgument(context, "volume", DEFAULT_AUDIO_VOLUME);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        String soundFile = StringArgumentType.getString(context, "soundFile");

        ServerStreamManager.startStream(context.getSource().getLevel(), position, soundFile, range, volume);
        if (ModConfig.DEBUG_MODE.get()) {
            context.getSource().sendSuccess(() -> Component.literal("正在发起音频流: " + soundFile), true);
        }
        return 1;
    }

    private int executePlayStreamEntityCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        float range = getFloatArgument(context, "range", DEFAULT_AUDIO_RANGE);
        float volume = getFloatArgument(context, "volume", DEFAULT_AUDIO_VOLUME);
        Entity target = EntityArgument.getEntity(context, "target");
        String soundFile = StringArgumentType.getString(context, "soundFile");

        ServerStreamManager.startStream(context.getSource().getLevel(), target, soundFile, range, volume);
        if (ModConfig.DEBUG_MODE.get()) {
            context.getSource().sendSuccess(() -> Component.literal("正在让 " + target.getName().getString() + " 播放音频流"), true);
        }
        return 1;
    }

    private int executePlayAudio3dLogic(CommandSourceStack source, Vec3 position, String soundFileName, float range, float volume) {
        String normalizedSoundPath = soundFileName.replace('\\', '/');
        if (normalizedSoundPath.contains("..") || normalizedSoundPath.startsWith("/")) {
            source.sendFailure(Component.literal("错误：文件路径无效！"));
            return 0;
        }
        try {
            Path audioPath = SOUNDS_DIRECTORY.resolve(normalizedSoundPath);
            if (!audioPath.toAbsolutePath().normalize().startsWith(SOUNDS_DIRECTORY.toAbsolutePath().normalize())) {
                source.sendFailure(Component.literal("错误：检测到非法的路径遍历尝试！"));
                return 0;
            }
            if (!Files.exists(audioPath) || !Files.isRegularFile(audioPath)) {
                source.sendFailure(Component.literal("错误: 文件 " + normalizedSoundPath + " 未找到！"));
                return 0;
            }

            byte[] audioData = Files.readAllBytes(audioPath);
            PlayAudioDataPacket packet = new PlayAudioDataPacket(audioData, position.x, position.y, position.z, range, volume);

            ServerLevel level = source.getLevel();
            for (ServerPlayer player : level.players()) {
                if (player.position().distanceToSqr(position) < range * range) {
                    PacketHandler.sendToPlayer(packet, player);
                }
            }

            if (ModConfig.DEBUG_MODE.get()) {
                source.sendSuccess(() -> Component.literal(String.format("播放 %s 于 %.1f,%.1f,%.1f (范围:%.1f, 音量:%.1f)", soundFileName, position.x, position.y, position.z, range, volume)), true);
            }
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("读取音频文件时发生错误！"));
            LOGGER.error("读取音频文件 {} 时出错:", soundFileName, e);
            return 0;
        }
    }

    private float getFloatArgument(CommandContext<CommandSourceStack> context, String name, float defaultValue) {
        try {
            return FloatArgumentType.getFloat(context, name);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    @FunctionalInterface
    private interface CommandExecutor {
        int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }
}