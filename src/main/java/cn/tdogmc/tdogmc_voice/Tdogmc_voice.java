package cn.tdogmc.tdogmc_voice;

import cn.tdogmc.tdogmc_voice.client.AudioEngine;
import cn.tdogmc.tdogmc_voice.config.ModConfig;
import cn.tdogmc.tdogmc_voice.network.PacketHandler;
import cn.tdogmc.tdogmc_voice.network.StopAllStreamsS2CPacket;
import cn.tdogmc.tdogmc_voice.stream.ServerStreamManager;
import cn.tdogmc.tdogmc_voice.util.SoundFileCache;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Collection;
import java.util.Collections;

@Mod(Tdogmc_voice.MODID)
public class Tdogmc_voice {

    public static final String MODID = "tdogmc_voice";
    private static final Logger LOGGER = LogUtils.getLogger();

    // 默认参数
    private static final float DEF_RANGE = 64.0f;
    private static final float DEF_VOL = 1.0f;
    private static final float DEF_PITCH = 1.0f;

    public Tdogmc_voice() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        PacketHandler.register();
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SPEC, "tdogmc_voice-server.toml");

        ServerStreamManager.init();

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            SoundFileCache.init();
            AudioEngine.getInstance().init();
        });
    }

    private static final SuggestionProvider<CommandSourceStack> SOUND_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(SoundFileCache.getSuggestions(), builder);

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Tdogmc_voice client setup.");
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 构建指令树: /tdvoice
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tdvoice").requires(s -> s.hasPermission(2));

        // Branch 1: Play
        // /tdvoice play <file> <targets> [pos/entity] [vol] [pitch] [range]
        var playNode = Commands.literal("play")
                .then(Commands.argument("file", StringArgumentType.string()).suggests(SOUND_SUGGESTIONS)
                        .then(Commands.argument("targets", EntityArgument.players())

                                // Case A: 默认位置 (执行者位置)
                                .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), null, null, DEF_VOL, DEF_PITCH, DEF_RANGE))

                                // Case B: 定点播放 (坐标)
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), Vec3Argument.getVec3(ctx, "pos"), null, DEF_VOL, DEF_PITCH, DEF_RANGE))
                                        .then(Commands.argument("volume", FloatArgumentType.floatArg(0))
                                                .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), Vec3Argument.getVec3(ctx, "pos"), null, FloatArgumentType.getFloat(ctx, "volume"), DEF_PITCH, DEF_RANGE))
                                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.1f, 2.0f))
                                                        .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), Vec3Argument.getVec3(ctx, "pos"), null, FloatArgumentType.getFloat(ctx, "volume"), FloatArgumentType.getFloat(ctx, "pitch"), DEF_RANGE))
                                                        .then(Commands.argument("range", FloatArgumentType.floatArg(0))
                                                                .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), Vec3Argument.getVec3(ctx, "pos"), null, FloatArgumentType.getFloat(ctx, "volume"), FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "range")))
                                                        )
                                                )
                                        )
                                )

                                // Case C: 跟随实体 (entity)
                                .then(Commands.literal("entity")
                                        .then(Commands.argument("sourceEntity", EntityArgument.entity())
                                                .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), null, EntityArgument.getEntity(ctx, "sourceEntity"), DEF_VOL, DEF_PITCH, DEF_RANGE))
                                                .then(Commands.argument("volume", FloatArgumentType.floatArg(0))
                                                        .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), null, EntityArgument.getEntity(ctx, "sourceEntity"), FloatArgumentType.getFloat(ctx, "volume"), DEF_PITCH, DEF_RANGE))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.1f, 2.0f))
                                                                .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), null, EntityArgument.getEntity(ctx, "sourceEntity"), FloatArgumentType.getFloat(ctx, "volume"), FloatArgumentType.getFloat(ctx, "pitch"), DEF_RANGE))
                                                                .then(Commands.argument("range", FloatArgumentType.floatArg(0))
                                                                        .executes(ctx -> play(ctx, EntityArgument.getPlayers(ctx, "targets"), null, EntityArgument.getEntity(ctx, "sourceEntity"), FloatArgumentType.getFloat(ctx, "volume"), FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "range")))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                );

        // Branch 2: Stop All
        var stopAllNode = Commands.literal("stopall")
                .executes(ctx -> stopAll(ctx, Collections.singleton(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> stopAll(ctx, EntityArgument.getPlayers(ctx, "targets")))
                );

        root.then(playNode);
        root.then(stopAllNode);
        dispatcher.register(root);
    }

    private int play(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, Vec3 pos, Entity entitySource, float vol, float pitch, float range) {
        String fileName = StringArgumentType.getString(ctx, "file");

        // 如果位置为空且实体为空，默认使用执行者位置
        if (pos == null && entitySource == null) {
            pos = ctx.getSource().getPosition();
        }

        if (entitySource != null) {
            ServerStreamManager.playToPlayers(targets, entitySource, fileName, range, vol, pitch);
        } else {
            ServerStreamManager.playToPlayers(targets, pos, fileName, range, vol, pitch);
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Sending audio " + fileName + " to " + targets.size() + " players."), true);
        return targets.size();
    }

    private int stopAll(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        StopAllStreamsS2CPacket packet = new StopAllStreamsS2CPacket();
        for (ServerPlayer player : targets) {
            PacketHandler.sendToPlayer(packet, player);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Sent stop signal to " + targets.size() + " players."), true);
        return targets.size();
    }
}