package com.fishguy129.fcw.claims;

import com.fishguy129.fcw.data.FCWSavedData;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Claim layout helper. It keeps the shape math out of CoreManager
public final class ClaimExpansionPlanner {
    private ClaimExpansionPlanner() {
    }

    public static ExpansionPlan plan(FCWSavedData.CoreRecord coreRecord, Collection<? extends ClaimedChunk> existingClaims,
                                     int targetClaimCount, int maxRange, Predicate<ChunkDimPos> blockedPredicate,
                                     boolean smartExpansion, boolean requireConnectedClaims) {
        ChunkPos origin = new ChunkPos(coreRecord.pos());
        Set<ChunkDimPos> currentClaims = existingClaims.stream().map(ClaimedChunk::getPos).collect(HashSet::new, Set::add, Set::addAll);
        if (smartExpansion) {
            return adaptivePlan(coreRecord, origin, currentClaims, targetClaimCount, maxRange, blockedPredicate, requireConnectedClaims);
        }
        return deterministicPlan(coreRecord, origin, currentClaims, targetClaimCount, maxRange);
    }

    public static ExpansionPlan planRestoration(FCWSavedData.CoreRecord coreRecord, Collection<? extends ClaimedChunk> existingClaims,
                                                Collection<ChunkDimPos> savedFootprint, int targetClaimCount, int maxRange,
                                                Predicate<ChunkDimPos> blockedPredicate, boolean requireConnectedClaims) {
        ChunkPos origin = new ChunkPos(coreRecord.pos());
        Set<ChunkDimPos> currentClaims = existingClaims.stream().map(ClaimedChunk::getPos).collect(HashSet::new, Set::add, Set::addAll);
        LinkedHashSet<ChunkDimPos> preferred = new LinkedHashSet<>();
        for (ChunkDimPos pos : savedFootprint) {
            if (pos.dimension().equals(coreRecord.dimension())) {
                preferred.add(pos);
            }
        }

        if (preferred.isEmpty()) {
            return adaptivePlan(coreRecord, origin, currentClaims, targetClaimCount, maxRange, blockedPredicate, requireConnectedClaims);
        }

        LinkedHashSet<ChunkDimPos> desiredClaims = new LinkedHashSet<>();
        for (ChunkDimPos pos : preferred) {
            if (currentClaims.contains(pos) || !blockedPredicate.test(pos)) {
                desiredClaims.add(pos);
            }
        }

        if (desiredClaims.isEmpty()) {
            ChunkDimPos originPos = new ChunkDimPos(coreRecord.dimension(), origin.x, origin.z);
            if (blockedPredicate.test(originPos)) {
                return new ExpansionPlan(targetClaimCount, List.of(), List.of(), "message.fcw.claims.no_available_space");
            }
            desiredClaims.add(originPos);
        }

        Set<ChunkDimPos> anchoredClaims = new HashSet<>(desiredClaims);
        return expandAdaptively(coreRecord, origin, anchoredClaims, desiredClaims, targetClaimCount, maxRange, blockedPredicate, requireConnectedClaims);
    }

    private static ExpansionPlan deterministicPlan(FCWSavedData.CoreRecord coreRecord, ChunkPos origin, Set<ChunkDimPos> currentClaims,
                                                   int targetClaimCount, int maxRange) {
        List<ChunkDimPos> deterministicOrder = new ArrayList<>();
        // Build the old (you won't see it but at first I had made this different) 
        // square-ring order first so the result is stable and predictable.
        for (int ring = 0; ring <= maxRange && deterministicOrder.size() < targetClaimCount; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    deterministicOrder.add(new ChunkDimPos(coreRecord.dimension(), origin.x + dx, origin.z + dz));
                    if (deterministicOrder.size() >= targetClaimCount) {
                        break;
                    }
                }
            }
        }

        Set<ChunkDimPos> desiredSet = new HashSet<>(deterministicOrder);
        for (ChunkDimPos current : currentClaims) {
            if (!desiredSet.contains(current)) {
                return new ExpansionPlan(targetClaimCount, deterministicOrder, List.of(), "message.fcw.claims.invalid_existing_shape");
            }
        }

        List<ChunkDimPos> additions = deterministicOrder.stream().filter(pos -> !currentClaims.contains(pos)).toList();
        return new ExpansionPlan(targetClaimCount, deterministicOrder, additions, null);
    }

    private static ExpansionPlan adaptivePlan(FCWSavedData.CoreRecord coreRecord, ChunkPos origin, Set<ChunkDimPos> currentClaims,
                                              int targetClaimCount, int maxRange, Predicate<ChunkDimPos> blockedPredicate,
                                              boolean requireConnectedClaims) {
        if (targetClaimCount <= 0) {
            return new ExpansionPlan(targetClaimCount, List.of(), List.of(), null);
        }

        LinkedHashSet<ChunkDimPos> desiredClaims = new LinkedHashSet<>(currentClaims);
        ChunkDimPos originPos = new ChunkDimPos(coreRecord.dimension(), origin.x, origin.z);
        if (desiredClaims.isEmpty()) {
            if (blockedPredicate.test(originPos)) {
                return new ExpansionPlan(targetClaimCount, List.of(), List.of(), "message.fcw.claims.no_available_space");
            }
            desiredClaims.add(originPos);
        }

        return expandAdaptively(coreRecord, origin, currentClaims, desiredClaims, targetClaimCount, maxRange, blockedPredicate, requireConnectedClaims);
    }

    private static ExpansionPlan expandAdaptively(FCWSavedData.CoreRecord coreRecord, ChunkPos origin, Set<ChunkDimPos> currentClaims,
                                                  LinkedHashSet<ChunkDimPos> desiredClaims, int targetClaimCount, int maxRange,
                                                  Predicate<ChunkDimPos> blockedPredicate, boolean requireConnectedClaims) {
        desiredClaims = trimToTarget(desiredClaims, targetClaimCount);

        if (desiredClaims.size() >= targetClaimCount) {
            List<ChunkDimPos> additions = desiredClaims.stream().filter(pos -> !currentClaims.contains(pos)).toList();
            return new ExpansionPlan(targetClaimCount, List.copyOf(desiredClaims), additions, null);
        }

        if (!requireConnectedClaims) {
            return fillByNearestAvailable(coreRecord, origin, currentClaims, desiredClaims, targetClaimCount, maxRange, blockedPredicate);
        }

        ArrayDeque<ChunkDimPos> queue = new ArrayDeque<>(desiredClaims);
        Set<ChunkDimPos> visited = new HashSet<>(desiredClaims);
        // Breadth-first growth keeps the shape from shooting off in one direction
        // when it has to route around blocked chunks.
        while (!queue.isEmpty() && desiredClaims.size() < targetClaimCount) {
            ChunkDimPos current = queue.removeFirst();
            for (ChunkDimPos neighbor : neighbors(coreRecord, origin, current, maxRange)) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                if (currentClaims.contains(neighbor)) {
                    desiredClaims.add(neighbor);
                    queue.addLast(neighbor);
                    continue;
                }
                if (blockedPredicate.test(neighbor)) {
                    continue;
                }
                desiredClaims.add(neighbor);
                queue.addLast(neighbor);
                if (desiredClaims.size() >= targetClaimCount) {
                    break;
                }
            }
        }

        if (desiredClaims.size() < targetClaimCount) {
            return new ExpansionPlan(targetClaimCount, List.copyOf(desiredClaims), List.of(), "message.fcw.claims.no_available_space");
        }

        List<ChunkDimPos> additions = desiredClaims.stream().filter(pos -> !currentClaims.contains(pos)).toList();
        return new ExpansionPlan(targetClaimCount, List.copyOf(desiredClaims), additions, null);
    }

    private static ExpansionPlan fillByNearestAvailable(FCWSavedData.CoreRecord coreRecord, ChunkPos origin, Set<ChunkDimPos> currentClaims,
                                                        LinkedHashSet<ChunkDimPos> desiredClaims, int targetClaimCount, int maxRange,
                                                        Predicate<ChunkDimPos> blockedPredicate) {
        desiredClaims = trimToTarget(desiredClaims, targetClaimCount);
        // This branch gives up on strict connectivity and just grabs the nearest safe chunks.
        for (int ring = 0; ring <= maxRange && desiredClaims.size() < targetClaimCount; ring++) {
            for (int dz = -ring; dz <= ring && desiredClaims.size() < targetClaimCount; dz++) {
                for (int dx = -ring; dx <= ring && desiredClaims.size() < targetClaimCount; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    ChunkDimPos candidate = new ChunkDimPos(coreRecord.dimension(), origin.x + dx, origin.z + dz);
                    if (desiredClaims.contains(candidate) || blockedPredicate.test(candidate)) {
                        continue;
                    }
                    desiredClaims.add(candidate);
                }
            }
        }

        if (desiredClaims.size() < targetClaimCount) {
            return new ExpansionPlan(targetClaimCount, List.copyOf(desiredClaims), List.of(), "message.fcw.claims.no_available_space");
        }

        List<ChunkDimPos> additions = desiredClaims.stream().filter(pos -> !currentClaims.contains(pos)).toList();
        return new ExpansionPlan(targetClaimCount, List.copyOf(desiredClaims), additions, null);
    }

    private static List<ChunkDimPos> neighbors(FCWSavedData.CoreRecord coreRecord, ChunkPos origin, ChunkDimPos current, int maxRange) {
        int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        List<ChunkDimPos> neighbors = new ArrayList<>(4);
        for (int[] offset : offsets) {
            int x = current.x() + offset[0];
            int z = current.z() + offset[1];
            if (Math.max(Math.abs(x - origin.x), Math.abs(z - origin.z)) > maxRange) {
                continue;
            }
            neighbors.add(new ChunkDimPos(coreRecord.dimension(), x, z));
        }
        return neighbors;
    }

    private static LinkedHashSet<ChunkDimPos> trimToTarget(LinkedHashSet<ChunkDimPos> desiredClaims, int targetClaimCount) {
        if (targetClaimCount <= 0 || desiredClaims.size() <= targetClaimCount) {
            return desiredClaims;
        }
        LinkedHashSet<ChunkDimPos> trimmed = new LinkedHashSet<>();
        for (ChunkDimPos pos : desiredClaims) {
            if (trimmed.size() >= targetClaimCount) {
                break;
            }
            trimmed.add(pos);
        }
        return trimmed;
    }

    public record ExpansionPlan(int targetClaimCount, List<ChunkDimPos> desiredClaims, List<ChunkDimPos> newClaims, String failureKey) {
        public boolean success() {
            return failureKey == null;
        }
    }
}
