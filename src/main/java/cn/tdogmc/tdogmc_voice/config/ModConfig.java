package cn.tdogmc.tdogmc_voice.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;

    static {
        BUILDER.push("General");

        DEBUG_MODE = BUILDER
                .comment("Enable debug mode to show all log messages in console and chat.")
                .define("debugMode", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}