package com.fishguy129.fcw.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// Client-side cache for the local player's own faction claim outline.
public final class ClientClaimOutlineState {
    private static volatile ClaimOutline outline;
    private static volatile long stateSerial;
    private static volatile long semanticRevision;
    private static volatile long semanticSignature;

    private ClientClaimOutlineState() {
    }

    public static void update(ResourceLocation dimensionId, int coreX, int coreY, int coreZ,
                              int breachRadius, int breachPathWidth, Collection<Long> chunkLongs) {
        Set<Long> canonicalChunkLongs = Set.copyOf(new LinkedHashSet<>(chunkLongs));
        long nextSignature = computeSignature(dimensionId, coreX, coreY, coreZ, breachRadius, breachPathWidth, canonicalChunkLongs);
        if (outline == null || semanticSignature != nextSignature) {
            semanticRevision++;
            semanticSignature = nextSignature;
        }
        stateSerial++;
        outline = new ClaimOutline(dimensionId, new BlockPos(coreX, coreY, coreZ), breachRadius, breachPathWidth,
                canonicalChunkLongs, stateSerial, semanticRevision, semanticSignature);
    }

    public static void clear() {
        stateSerial++;
        if (outline != null) {
            semanticRevision++;
        }
        semanticSignature = 0L;
        outline = null;
    }

    public static ClaimOutline get() {
        return outline;
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

    private static long computeSignature(ResourceLocation dimensionId, int coreX, int coreY, int coreZ,
                                         int breachRadius, int breachPathWidth, Set<Long> chunkLongs) {
        long signature = 1469598103934665603L;
        signature ^= dimensionId.hashCode();
        signature *= 1099511628211L;
        signature ^= coreX;
        signature *= 1099511628211L;
        signature ^= coreY;
        signature *= 1099511628211L;
        signature ^= coreZ;
        signature *= 1099511628211L;
        signature ^= breachRadius;
        signature *= 1099511628211L;
        signature ^= breachPathWidth;
        signature *= 1099511628211L;

        long[] sortedChunks = chunkLongs.stream().mapToLong(Long::longValue).sorted().toArray();
        for (long chunkLong : sortedChunks) {
            signature ^= chunkLong;
            signature *= 1099511628211L;
        }
        return signature;
    }

    public record ClaimOutline(ResourceLocation dimensionId, BlockPos corePos, int breachRadius,
                               int breachPathWidth, Set<Long> chunkLongs,
                               long stateSerial, long semanticRevision, long semanticSignature) {
        public Set<Long> chunkLongsView() {
            return Collections.unmodifiableSet(chunkLongs);
        }
    }
}
