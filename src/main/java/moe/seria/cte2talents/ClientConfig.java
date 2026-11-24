package moe.seria.cte2talents;

import net.minecraftforge.common.ForgeConfigSpec;

// Saved as config/cte2talents-client.toml. Forge reloads the file when it changes, so an edit
// takes effect without restarting.
final class ClientConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue SHOW_RECORDED_MESSAGE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SHOW_RECORDED_MESSAGE = builder
                .comment("Show \"cte2gear: recorded N items from ...\" above the hotbar each time a container's gear is recorded.")
                .define("showRecordedMessage", false);
        SPEC = builder.build();
    }
}
