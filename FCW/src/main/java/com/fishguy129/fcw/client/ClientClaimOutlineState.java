package com.fishguy129.fcw.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// Client-side cache for the local player's own faction claim outline.
public final class ClientClaimOutlineState {
    private static volatile ClaimOutline outline;

    private ClientClaimOutlineState() {
    }

    public static void update(ResourceLocation dimensionId, int coreY, Collection<Long> chunkLongs) {
        outline = new ClaimOutline(dimensionId, coreY, Set.copyOf(new LinkedHashSet<>(chunkLongs)));
    }

    public static void clear() {
        outline = null;
    }

    public static ClaimOutline get() {
        return outline;
    }

    public record ClaimOutline(ResourceLocation dimensionId, int coreY, Set<Long> chunkLongs) {
        public Set<Long> chunkLongsView() {
            return Collections.unmodifiableSet(chunkLongs);
        }
    }
}
