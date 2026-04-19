package com.fishguy129.fcw.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Client-side cache for active raids. It lets the HUD, world overlays, and
// renderer all read the same snapshot without talking to each other.
public final class ClientRaidState {
    private static final Map<String, RaidVisual> ACTIVE_RAIDS = new ConcurrentHashMap<>();

    private ClientRaidState() {
    }

    public static void update(ResourceLocation dimensionId, BlockPos corePos, String attackerName, String defenderName, long progressTicks, long requiredTicks,
                              Collection<UUID> attackerIds, Collection<UUID> defenderIds, Collection<Long> defendedChunkLongs,
                              boolean localPlayerAttacker, boolean localPlayerDefender) {
        ACTIVE_RAIDS.put(key(dimensionId, corePos), new RaidVisual(
                dimensionId,
                corePos.immutable(),
                attackerName,
                defenderName,
                progressTicks,
                requiredTicks,
                Set.copyOf(attackerIds),
                Set.copyOf(defenderIds),
                Set.copyOf(defendedChunkLongs),
                localPlayerAttacker,
                localPlayerDefender
        ));
    }

    public static void remove(ResourceLocation dimensionId, BlockPos corePos) {
        ACTIVE_RAIDS.remove(key(dimensionId, corePos));
    }

    public static RaidVisual get(ResourceLocation dimensionId, BlockPos corePos) {
        return ACTIVE_RAIDS.get(key(dimensionId, corePos));
    }

    public static Collection<RaidVisual> all() {
        return Collections.unmodifiableCollection(ACTIVE_RAIDS.values());
    }

    public static RaidVisual localPlayerRaid(ResourceLocation dimensionId) {
        return ACTIVE_RAIDS.values().stream()
                .filter(raid -> raid.dimensionId().equals(dimensionId))
                .filter(raid -> raid.localPlayerAttacker() || raid.localPlayerDefender())
                .findFirst()
                .orElse(null);
    }

    public static void clear() {
        ACTIVE_RAIDS.clear();
    }

    private static String key(ResourceLocation dimensionId, BlockPos corePos) {
        // Dimension plus packed block position is enough to keep raid cores unique.
        return dimensionId + "#" + corePos.asLong();
    }

    public record RaidVisual(ResourceLocation dimensionId, BlockPos corePos, String attackerName, String defenderName, long progressTicks,
                             long requiredTicks, Set<UUID> attackerIds, Set<UUID> defenderIds, Set<Long> defendedChunkLongs,
                             boolean localPlayerAttacker, boolean localPlayerDefender) {
        public long remainingTicks() {
            return Math.max(0L, requiredTicks - progressTicks);
        }

        public float progressFraction() {
            return requiredTicks <= 0L ? 1F : Math.min(1F, (float) progressTicks / (float) requiredTicks);
        }

        public boolean isParticipant(UUID entityId) {
            return attackerIds.contains(entityId) || defenderIds.contains(entityId);
        }

        public boolean isAttacker(UUID entityId) {
            return attackerIds.contains(entityId);
        }

        public boolean isDefender(UUID entityId) {
            return defenderIds.contains(entityId);
        }

        public boolean isInsideDefendedChunks(int chunkX, int chunkZ) {
            return defendedChunkLongs.contains(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
        }

        public String oppositionName() {
            if (localPlayerAttacker) {
                return defenderName;
            }
            if (localPlayerDefender) {
                return attackerName;
            }
            return attackerName + " / " + defenderName;
        }

        public String localRoleLabel() {
            if (localPlayerAttacker) {
                return "ATTACK";
            }
            if (localPlayerDefender) {
                return "DEFEND";
            }
            return "RAID";
        }
    }
}
