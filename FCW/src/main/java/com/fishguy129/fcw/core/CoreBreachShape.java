package com.fishguy129.fcw.core;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

// Shared breach-zone math so the server's protection checks and the client's
// path visuals agree on the same footprint.
public final class CoreBreachShape {
    private CoreBreachShape() {
    }

    public static boolean isInsideRadius(BlockPos corePos, BlockPos targetPos, int radius) {
        int dx = targetPos.getX() - corePos.getX();
        int dz = targetPos.getZ() - corePos.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    public static boolean isInsidePath(BlockPos corePos, BlockPos targetPos, int pathWidth) {
        if (pathWidth <= 0) {
            return false;
        }

        double min = -(pathWidth * 0.5D);
        double max = pathWidth * 0.5D;
        int dx = targetPos.getX() - corePos.getX();
        int dz = targetPos.getZ() - corePos.getZ();

        return overlapsStrip(dx, min, max) || overlapsStrip(dz, min, max);
    }

    public static double halfPathWidth(int pathWidth) {
        return Math.max(0.0D, pathWidth * 0.5D);
    }

    public static boolean overlapsAlong(double minA, double maxA, double minB, double maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB) > 1.0E-4D;
    }

    public static boolean isWithinGateRange(double sample, double minAlong, double maxAlong) {
        return sample >= minAlong - 1.0E-4D && sample <= maxAlong + 1.0E-4D;
    }

    public static int circleSegments(int radius) {
        return Mth.clamp(radius * 8, 40, 104);
    }

    private static boolean overlapsStrip(int blockDelta, double stripMin, double stripMax) {
        double blockMin = blockDelta - 0.5D;
        double blockMax = blockDelta + 0.5D;
        return overlapsAlong(blockMin, blockMax, stripMin, stripMax);
    }
}
