package com.fishguy129.fcw.client;

import com.fishguy129.fcw.network.EnemyClaimOutlineMessage;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ClientEnemyClaimOutlineState {
    private static volatile List<EnemyOutline> outlines = List.of();

    private ClientEnemyClaimOutlineState() {
    }

    public static void update(List<EnemyClaimOutlineMessage.EnemyOutline> incoming) {
        List<EnemyOutline> parsed = new ArrayList<>(incoming.size());
        for (EnemyClaimOutlineMessage.EnemyOutline raw : incoming) {
            parsed.add(new EnemyOutline(
                    new ResourceLocation(raw.dimensionId()),
                    raw.coreY(),
                    Set.copyOf(new LinkedHashSet<>(raw.chunkLongs()))
            ));
        }
        outlines = List.copyOf(parsed);
    }

    public static void clear() {
        outlines = List.of();
    }

    public static List<EnemyOutline> all() {
        return outlines;
    }

    public record EnemyOutline(ResourceLocation dimensionId, int coreY, Set<Long> chunkLongs) {
        public Set<Long> chunkLongsView() {
            return Collections.unmodifiableSet(chunkLongs);
        }
    }
}
