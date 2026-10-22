package com.yansel.efnperfectroll;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.dodge.DodgeSkill;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.entity.eventlistener.DodgeSuccessEvent;
import yesman.epicfight.world.entity.eventlistener.PlayerEventListener;
import yesman.epicfight.world.entity.eventlistener.PlayerEventListener.EventType;
import yesman.epicfight.world.entity.eventlistener.SkillConsumeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A "Perfect Roll" is a roll of EpicFight-Nightfall (EFNDodgeSkill_Roll) that makes Epic Fight fire
 * DODGE_SUCCESS_EVENT, i.e. the dodge indicator spawned by the roll was actually hit by an attack.
 *
 * The stamina of a skill is charged when the skill is cast, before anybody knows whether an attack will come,
 * so it cannot be cancelled up front. Instead:
 *  1. SKILL_CONSUME_EVENT: remember (server side) the consume event of the latest dodge-type skill, and the
 *     stamina the player had right before the consumption.
 *  2. DODGE_SUCCESS_EVENT: if the latest dodge was a Nightfall roll, is recent, and really consumed stamina,
 *     give back the configured share (default 50%) of the amount that roll consumed. The record is removed first, so it can happen only once.
 * Stamina is a SynchedEntityData value of Epic Fight, so the client receives the new value automatically.
 */
@Mod.EventBusSubscriber(modid = EfnPerfectRoll.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PerfectRollHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fully qualified name of Nightfall's roll skill (EpicFight-Nightfall 3.4.0). Compared by name: no hard dependency. */
    static final String ROLL_SKILL_CLASS = "com.hm.efn.skill.dodge.EFNDodgeSkill_Roll";
    /** A dodge indicator lives ~6 ticks; allow some slack for latency between cast and the first hit. */
    static final int MAX_AGE_TICKS = 12;
    /** Re-attach interval (idempotent). Safety net in case the patch object is recreated. */
    private static final int REATTACH_INTERVAL = 100;

    private static final UUID LISTENER_ID = UUID.fromString("5d0c6f0e-7a1b-4c52-9b8e-3a1f2c4d6e81");
    private static final Map<UUID, PendingRoll> PENDING = new ConcurrentHashMap<>();

    private PerfectRollHandler() {
    }

    private record PendingRoll(SkillConsumeEvent event, float staminaBefore, long gameTime) {
    }

    static boolean isRollSkill(Skill skill) {
        for (Class<?> c = skill.getClass(); c != null; c = c.getSuperclass()) {
            if (ROLL_SKILL_CLASS.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void attach(ServerPlayer player) {
        ServerPlayerPatch patch = EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
        if (patch == null) {
            return;
        }
        PlayerEventListener listener = patch.getEventListener();
        // addEventListener removes a previous listener with the same UUID first, so calling this again is safe.
        listener.addEventListener(EventType.SKILL_CONSUME_EVENT, LISTENER_ID, event -> onSkillConsume(patch, event));
        listener.addEventListener(EventType.DODGE_SUCCESS_EVENT, LISTENER_ID, event -> onDodgeSuccess(patch, event));
    }

    private static void onSkillConsume(ServerPlayerPatch patch, SkillConsumeEvent event) {
        Skill skill = event.getSkill();
        if (!(skill instanceof DodgeSkill)) {
            return; // block, parry, attacks and every other skill are not touched
        }
        UUID id = patch.getOriginal().getUUID();
        if (isRollSkill(skill)) {
            PENDING.put(id, new PendingRoll(event, patch.getStamina(), patch.getOriginal().level().getGameTime()));
        } else {
            PENDING.remove(id); // a different dodge (e.g. step) replaced the roll: nothing to refund for it
        }
    }

    private static void onDodgeSuccess(ServerPlayerPatch patch, DodgeSuccessEvent dodge) {
        UUID id = patch.getOriginal().getUUID();
        PendingRoll roll = PENDING.remove(id); // remove first => at most one refund per roll
        if (roll == null) {
            return;
        }
        long age = patch.getOriginal().level().getGameTime() - roll.gameTime();
        if (age < 0 || age > MAX_AGE_TICKS) {
            return;
        }
        SkillConsumeEvent consume = roll.event();
        // Read the FINAL state of the consume event (other listeners may have changed it after us).
        if (consume.isCanceled() || consume.getResourceType() != Skill.Resource.STAMINA) {
            return;
        }
        float amount = consume.getAmount();
        if (amount <= 0.0F || roll.staminaBefore() < amount) {
            return; // the stamina check failed, so nothing was consumed
        }
        double percent = PerfectRollConfig.REFUND_PERCENT.get();
        float refund = (float) (amount * percent / 100.0D);
        if (refund <= 0.0F) {
            return;
        }
        float newStamina = Math.min(patch.getMaxStamina(), patch.getStamina() + refund);
        patch.setStamina(newStamina);
        LOGGER.info("[efnperfectroll] Perfect Roll: refunded {} of {} stamina ({}%) to {} (now {})",
                refund, amount, percent, patch.getOriginal().getGameProfile().getName(), newStamina);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ServerPlayer player) {
            attach(player);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            attach(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            attach(player);
        }
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            attach(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.side.isServer()
                && event.player instanceof ServerPlayer player
                && player.tickCount % REATTACH_INTERVAL == 0) {
            attach(player);
        }
    }
}
