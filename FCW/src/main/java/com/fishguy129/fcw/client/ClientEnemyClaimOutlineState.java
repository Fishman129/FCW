package com.fishguy129.fcw.client;

import com.fishguy129.fcw.network.EnemyClaimOutlineMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ClientEnemyClaimOutlineState {
    private static volatile List<EnemyOutline> outlines = List.of();
    private static volatile long stateSerial;
    private static volatile long semanticRevision;
    private static volatile long semanticSignature;

    private ClientEnemyClaimOutlineState() {
    }

    public static void update(List<EnemyClaimOutlineMessage.EnemyOutline> incoming) {
        List<EnemyOutline> parsed = new ArrayList<>(incoming.size());
        for (EnemyClaimOutlineMessage.EnemyOutline raw : incoming) {
            parsed.add(new EnemyOutline(
                    new ResourceLocation(raw.dimensionId()),
                    new BlockPos(raw.coreX(), raw.coreY(), raw.coreZ()),
                    raw.breachRadius(),
                    raw.breachPathWidth(),
                    Set.copyOf(new LinkedHashSet<>(raw.chunkLongs())),
                    0L
            ));
        }
        long nextSignature = computeSignature(parsed);
        if (semanticSignature != nextSignature) {
            semanticRevision++;
            semanticSignature = nextSignature;
        }
        stateSerial++;

        List<EnemyOutline> finalized = new ArrayList<>(parsed.size());
        for (EnemyOutline outline : parsed) {
            finalized.add(new EnemyOutline(
                    outline.dimensionId(),
                    outline.corePos(),
                    outline.breachRadius(),
                    outline.breachPathWidth(),
                    outline.chunkLongs(),
                    stateSerial
            ));
        }
        outlines = List.copyOf(finalized);
    }

    public static void clear() {
        stateSerial++;
        if (!outlines.isEmpty()) {
            semanticRevision++;
        }
        semanticSignature = 0L;
        outlines = List.of();
    }

    public static List<EnemyOutline> all() {
        return outlines;
    }

    public static long stateSerial() {
        return stateSerial;
    }

    public static long semanticRevision() {
        return semanticRevision;
    }

    public static long semanticSignature() {
        return semanticSignature;
    }

    private static long computeSignature(List<EnemyOutline> parsed) {
        long signature = 1469598103934665603L;
        List<EnemyOutline> sorted = new ArrayList<>(parsed);
        sorted.sort((left, right) -> {
            int compareDimension = left.dimensionId().toString().compareTo(right.dimensionId().toString());
            if (compareDimension != 0) {
                return compareDimension;
            }
            int compareX = Integer.compare(left.corePos().getX(), right.corePos().getX());
            if (compareX != 0) {
                return compareX;
            }
            int compareY = Integer.compare(left.corePos().getY(), right.corePos().getY());
            if (compareY != 0) {
                return compareY;
            }
            return Integer.compare(left.corePos().getZ(), right.corePos().getZ());
        });

        for (EnemyOutline outline : sorted) {
            signature ^= outline.dimensionId().hashCode();
            signature *= 1099511628211L;
            signature ^= outline.corePos().hashCode();
            signature *= 1099511628211L;
            signature ^= outline.breachRadius();
            signature *= 1099511628211L;
            signature ^= outline.breachPathWidth();
            signature *= 1099511628211L;
            long[] sortedChunks = outline.chunkLongs().stream().mapToLong(Long::longValue).sorted().toArray();
            for (long chunkLong : sortedChunks) {
                signature ^= chunkLong;
                signature *= 1099511628211L;
            }
        }
        return signature;
    }

    public record EnemyOutline(ResourceLocation dimensionId, BlockPos corePos, int breachRadius,
                               int breachPathWidth, Set<Long> chunkLongs, long stateSerial) {
        public Set<Long> chunkLongsView() {
            return Collections.unmodifiableSet(chunkLongs);
        }
    }
}
