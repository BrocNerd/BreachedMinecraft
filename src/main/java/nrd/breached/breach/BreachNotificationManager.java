package nrd.breached.breach;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.Breached;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.reinforcement.ReinforcementManager;
import nrd.breached.worldgen.BreachedDimensionRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BreachNotificationManager {
    private static final int NORMAL_BREACH_ALARM_COOLDOWN_TICKS = 20 * 20;
    private static final int REINFORCED_BREACH_ALARM_COOLDOWN_TICKS = 20 * 10;
    private static final Map<BreachAlarmKey, Long> LAST_ALARM_WORLD_TIMES = new HashMap<>();
    private static final List<ScheduledAlarm> SCHEDULED_ALARMS = new ArrayList<>();

    private BreachNotificationManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BreachNotificationManager::tickScheduledAlarms);
    }

    public static void tryPlayBreachAlarm(ServerWorld world, BlockPos pos, BlockState breachedState, ServerPlayerEntity player) {
        if (player.isCreative()
                || world.getServer() == null
                || !BreachedDimensionRules.isBreachedIslandWorld(world.getServer())) {
            return;
        }

        Optional<LandlockClaimManager.ClaimAccess> claimAccess = LandlockClaimManager.getClaimAccess(world, pos, player.getUuid());
        if (claimAccess.isPresent() && claimAccess.get().authorized()) {
            return;
        }

        boolean reinforced = ReinforcementManager.getTier(world, pos, breachedState).isPresent();
        if (!reinforced && claimAccess.isEmpty()) {
            return;
        }

        AlarmProfile alarmProfile = reinforced ? AlarmProfile.REINFORCED : AlarmProfile.NORMAL;

        BlockPos alarmAnchor = claimAccess
                .map(LandlockClaimManager.ClaimAccess::claimCenter)
                .orElse(pos)
                .toImmutable();
        BreachAlarmKey alarmKey = new BreachAlarmKey(world.getRegistryKey(), alarmAnchor, alarmProfile);
        long worldTime = world.getTime();
        removeExpiredCooldowns(worldTime);

        Long lastAlarmTime = LAST_ALARM_WORLD_TIMES.get(alarmKey);
        if (lastAlarmTime != null && worldTime - lastAlarmTime < alarmProfile.cooldownTicks) {
            return;
        }

        LAST_ALARM_WORLD_TIMES.put(alarmKey, worldTime);
        playAlarmSequence(world, pos, alarmProfile);
    }

    private static void playAlarmSequence(ServerWorld world, BlockPos pos, AlarmProfile alarmProfile) {
        long worldTime = world.getTime();
        for (AlarmStep step : alarmProfile.steps) {
            if (step.delayTicks == 0) {
                playAlarmStep(world, pos, step);
                continue;
            }

            SCHEDULED_ALARMS.add(new ScheduledAlarm(
                    world.getRegistryKey(),
                    pos.toImmutable(),
                    worldTime + step.delayTicks,
                    step
            ));
        }
    }

    private static void tickScheduledAlarms(MinecraftServer server) {
        if (SCHEDULED_ALARMS.isEmpty()) {
            return;
        }

        Iterator<ScheduledAlarm> iterator = SCHEDULED_ALARMS.iterator();
        while (iterator.hasNext()) {
            ScheduledAlarm alarm = iterator.next();
            ServerWorld world = server.getWorld(alarm.worldKey);
            if (world == null) {
                iterator.remove();
                continue;
            }

            if (world.getTime() < alarm.playAtWorldTime) {
                continue;
            }

            playAlarmStep(world, alarm.pos, alarm.step);
            iterator.remove();
        }
    }

    private static void playAlarmStep(ServerWorld world, BlockPos pos, AlarmStep step) {
        world.playSound(null, pos, step.sound, SoundCategory.BLOCKS, step.volume, step.pitch);
    }

    private static void removeExpiredCooldowns(long worldTime) {
        LAST_ALARM_WORLD_TIMES.entrySet().removeIf(entry -> worldTime - entry.getValue() >= entry.getKey().alarmProfile.cooldownTicks);
    }

    private enum AlarmProfile {
        NORMAL(
                NORMAL_BREACH_ALARM_COOLDOWN_TICKS,
                new AlarmStep(Breached.BREACH_ALARM_SOUND, 0, 1.0F, 1.0F),
                new AlarmStep(Breached.BREACH_ALARM_SOUND, 16, 1.0F, 1.0F),
                new AlarmStep(Breached.BREACH_ALARM_SOUND, 32, 1.0F, 1.0F)
        ),
        REINFORCED(
                REINFORCED_BREACH_ALARM_COOLDOWN_TICKS,
                new AlarmStep(Breached.REINFORCED_BREACH_ALARM_SOUND, 0, 1.0F, 1.0F),
                new AlarmStep(Breached.REINFORCED_BREACH_ALARM_SOUND, 16, 1.0F, 1.0F),
                new AlarmStep(Breached.REINFORCED_BREACH_ALARM_SOUND, 32, 1.0F, 1.0F)
        );

        private final int cooldownTicks;
        private final List<AlarmStep> steps;

        AlarmProfile(int cooldownTicks, AlarmStep... steps) {
            this.cooldownTicks = cooldownTicks;
            this.steps = List.of(steps);
        }
    }

    private record BreachAlarmKey(RegistryKey<World> worldKey, BlockPos pos, AlarmProfile alarmProfile) {
        private BreachAlarmKey {
            pos = pos.toImmutable();
        }
    }

    private record AlarmStep(SoundEvent sound, int delayTicks, float volume, float pitch) {
    }

    private record ScheduledAlarm(RegistryKey<World> worldKey, BlockPos pos, long playAtWorldTime, AlarmStep step) {
        private ScheduledAlarm {
            pos = pos.toImmutable();
        }
    }
}
