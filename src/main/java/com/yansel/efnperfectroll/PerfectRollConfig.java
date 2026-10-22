package com.yansel.efnperfectroll;

import net.minecraftforge.common.ForgeConfigSpec;

/** Config file: config/efnperfectroll-common.toml (created on first launch). */
public final class PerfectRollConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue REFUND_PERCENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        REFUND_PERCENT = builder
                .comment("Percentage of the roll's stamina cost that a Perfect Roll gives back.",
                        "100 = full refund (free Perfect Roll), 50 = half, 0 = no refund (mod does nothing).")
                .defineInRange("refundPercent", 50.0D, 0.0D, 100.0D);
        SPEC = builder.build();
    }

    private PerfectRollConfig() {
    }
}
