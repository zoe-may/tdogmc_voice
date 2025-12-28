package cn.tdogmc.tdogmc_voice.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;
    public static final ForgeConfigSpec.BooleanValue LOG_BASIC_INFO; // 新增的配置开关

    static {
        BUILDER.push("General");

        DEBUG_MODE = BUILDER
                .comment("Enable debug mode for verbose, technical logging. Useful for developers or reporting bugs.")
                .define("debugMode", false); // 默认设置为 false

        // 定义新的配置项
        LOG_BASIC_INFO = BUILDER
                .comment("Enable basic logging for standard operations, like when a stream starts or stops.")
                .define("logBasicInfo", true); // 默认设置为 true

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}