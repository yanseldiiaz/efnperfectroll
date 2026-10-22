package com.yansel.efnperfectroll;

import net.minecraftforge.fml.common.Mod;

/**
 * EFN Perfect Roll. All logic lives in {@link PerfectRollHandler}; this class only declares the mod.
 * The addon uses NO Mixins: it only subscribes to Epic Fight's public per-player event listener.
 */
@Mod(EfnPerfectRoll.MODID)
public class EfnPerfectRoll {
    public static final String MODID = "efnperfectroll";

    public EfnPerfectRoll() {
    }
}
