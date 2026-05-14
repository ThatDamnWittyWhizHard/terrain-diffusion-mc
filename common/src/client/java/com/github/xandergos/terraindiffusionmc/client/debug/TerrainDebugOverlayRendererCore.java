package com.github.xandergos.terraindiffusionmc.client.debug;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.debug.river.TerrainRiverConfig;
import com.github.xandergos.terraindiffusionmc.debug.river.vector.TerrainRiverNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;


public final class TerrainDebugOverlayRendererCore {
    private TerrainDebugOverlayRendererCore() {
    }

    public static void render(LevelRenderer levelRenderer) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || levelRenderer == null) {
            return;
        }

        TerrainDebugOverlayMode mode = TerrainDebugOverlayState.mode();
        if (mode == TerrainDebugOverlayMode.OFF) {
            return;
        }

        int tileSize = TerrainDiffusionConfig.tileSize();
        int playerBlockX = Mth.floor(client.player.getX());
        int playerBlockZ = Mth.floor(client.player.getZ());
        int centerTileX = Math.floorDiv(playerBlockX, tileSize);
        int centerTileZ = Math.floorDiv(playerBlockZ, tileSize);
        int radius = TerrainDebugOverlayState.radiusTiles();

        try (Gizmos.TemporaryCollection ignored = levelRenderer.collectPerFrameGizmos()) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int blockStartX = (centerTileX + dx) * tileSize;
                    int blockStartZ = (centerTileZ + dz) * tileSize;

                    TerrainDebugTileClientCache.requestRiverVector(blockStartZ, blockStartX, tileSize, tileSize);
                    TerrainRiverNetwork network = TerrainDebugTileClientCache.getRiverVectorIfReady(blockStartZ, blockStartX, tileSize, tileSize);
                    if (network != null) {
                        emitRiverTraceWidth(network);
                    }
                }
            }
        }
    }

    private static void emitRiverTraceWidth(TerrainRiverNetwork network) {
        emitLakeAccumulations(network);

        for (TerrainRiverNetwork.Segment segment : network.segments()) {
            if (segment.points().size() < 2) {
                continue;
            }

            int affluentCount = segment.upstreamAffluentCount();
            int color = riverAffluentVectorColor(affluentCount);
            float width = riverTraceLineWidth(segment);
            for (int i = 0; i < segment.points().size() - 1; i++) {
                Vec3 a = vectorPoint(segment.points().get(i));
                Vec3 b = vectorPoint(segment.points().get(i + 1));
                Gizmos.line(a, b, color, width);
            }

            emitSegmentAffluentTicks(segment, affluentCount, color);
        }

        emitConfluenceAffluentMarkers(network);
    }

    private static void emitLakeAccumulations(TerrainRiverNetwork network) {
        for (TerrainRiverNetwork.Lake lake : network.lakes()) {
            int fillColor = lakeAccumulationColor(lake.maxAccumulation(), network.maxAccumulation(), 150);
            int edgeColor = lakeAccumulationColor(lake.maxAccumulation(), network.maxAccumulation(), 230);
            float lineWidth = lakeRunLineWidth(lake);

            for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
                double y = run.waterLevelY() + TerrainDebugOverlayState.yOffset() + 0.46D;
                double z = run.worldZ() + 0.5D;
                Vec3 a = new Vec3(run.startWorldX(), y, z);
                Vec3 b = new Vec3(run.endWorldX(), y, z);
                Gizmos.line(a, b, fillColor, lineWidth);
            }

            emitLakeBoundary(lake, edgeColor);
            emitLakeOutletMarker(lake, edgeColor);
        }
    }

    private static void emitLakeBoundary(TerrainRiverNetwork.Lake lake, int color) {
        for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
            if (isBoundaryRun(lake, run, -1)) {
                emitLakeEdge(run.startWorldX(), run.endWorldX(), run.worldZ(), run.waterLevelY(), color);
            }
            if (isBoundaryRun(lake, run, 1)) {
                emitLakeEdge(run.startWorldX(), run.endWorldX(), run.worldZ() + 1, run.waterLevelY(), color);
            }
        }
    }

    private static void emitLakeEdge(int startX, int endX, int z, float waterLevelY, int color) {
        double y = waterLevelY + TerrainDebugOverlayState.yOffset() + 0.62D;
        Gizmos.line(new Vec3(startX, y, z), new Vec3(endX, y, z), color, 1.30F);
    }

    private static boolean isBoundaryRun(TerrainRiverNetwork.Lake lake, TerrainRiverNetwork.LakeRun run, int dz) {
        int neighborZ = run.worldZ() + dz;
        for (TerrainRiverNetwork.LakeRun other : lake.runs()) {
            if (other.worldZ() != neighborZ) {
                continue;
            }
            if (other.startWorldX() <= run.startWorldX() && other.endWorldX() >= run.endWorldX()) {
                return false;
            }
        }
        return true;
    }

    private static void emitLakeOutletMarker(TerrainRiverNetwork.Lake lake, int color) {
        if (lake.outletWorldX() == Integer.MIN_VALUE || lake.outletWorldZ() == Integer.MIN_VALUE) {
            return;
        }

        double y = lake.waterLevelY() + TerrainDebugOverlayState.yOffset() + 0.82D;
        Vec3 center = new Vec3(lake.outletWorldX() + 0.5D, y, lake.outletWorldZ() + 0.5D);
        double radius = Math.max(0.85D, Math.min(2.40D, Math.sqrt(Math.max(1, lake.surfaceCellCount())) * 0.08D));
        Gizmos.line(center.add(-radius, 0.0D, 0.0D), center.add(radius, 0.0D, 0.0D), color, 1.75F);
        Gizmos.line(center.add(0.0D, 0.0D, -radius), center.add(0.0D, 0.0D, radius), color, 1.75F);
        Gizmos.line(center, center.add(0.0D, 0.75D + Math.min(5, lake.inflowCount()) * 0.18D, 0.0D), color, 1.55F);
    }

    private static float lakeRunLineWidth(TerrainRiverNetwork.Lake lake) {
        float depth = Mth.clamp(lake.meanDepthBlocks(), 0.5F, 8.0F);
        return Mth.clamp(1.30F + depth * 0.22F, 1.30F, 3.20F);
    }

    private static int lakeAccumulationColor(float accumulation, float maxAccumulation, int alpha) {
        float t = (float) Math.sqrt(clamp01(logNormalize(accumulation, maxAccumulation)));
        int r = (int) lerp(t, 45.0F, 40.0F);
        int g = (int) lerp(t, 105.0F, 245.0F);
        int b = (int) lerp(t, 255.0F, 255.0F);
        return argb(alpha, r, g, b);
    }

    private static float logNormalize(float value, float maxValue) {
        double denominator = Math.log1p(Math.max(1.0F, maxValue));
        return denominator <= 0.0D
                ? 0.0F
                : clamp01((float) (Math.log1p(Math.max(0.0F, value)) / denominator));
    }

    private static Vec3 vectorPoint(TerrainRiverNetwork.Point point) {
        return new Vec3(
                point.worldX(),
                point.surfaceY() + TerrainDebugOverlayState.yOffset() + 0.78D,
                point.worldZ()
        );
    }

    private static float riverTraceLineWidth(TerrainRiverNetwork.Segment segment) {
        float n = widthVisualNormalized(segment.maxWidthBlocks());
        return Mth.clamp(0.42F + n * 2.75F, 0.42F, 3.10F);
    }

    private static void emitSegmentAffluentTicks(TerrainRiverNetwork.Segment segment, int affluentCount, int color) {
        int visibleTicks = Math.min(affluentCount, 5);
        if (visibleTicks <= 0 || segment.points().size() < 3) {
            return;
        }

        TerrainRiverNetwork.Point center = segment.points().get(segment.points().size() / 2);
        byte direction = center.direction();
        if (direction < 0) {
            direction = segment.downstreamDirection();
        }
        if (direction < 0) {
            return;
        }

        Vec3 middle = vectorPoint(center).add(0.0D, 0.28D, 0.0D);
        double perpX = -directionZ(direction);
        double perpZ = directionX(direction);
        double length = Math.sqrt(perpX * perpX + perpZ * perpZ);
        if (length <= 1.0E-6D) {
            return;
        }
        perpX /= length;
        perpZ /= length;

        double halfLength = Math.max(0.42D, Math.min(1.45D, center.widthBlocks() * 0.28D));
        double spacing = 0.32D;
        double startOffset = -(visibleTicks - 1) * spacing * 0.5D;
        for (int i = 0; i < visibleTicks; i++) {
            double along = startOffset + i * spacing;
            double tangentX = directionX(direction);
            double tangentZ = directionZ(direction);
            Vec3 a = middle.add(tangentX * along - perpX * halfLength, 0.0D, tangentZ * along - perpZ * halfLength);
            Vec3 b = middle.add(tangentX * along + perpX * halfLength, 0.0D, tangentZ * along + perpZ * halfLength);
            Gizmos.line(a, b, color, 1.20F);
        }
    }

    private static void emitConfluenceAffluentMarkers(TerrainRiverNetwork network) {
        for (TerrainRiverNetwork.Node node : network.nodes()) {
            int directAffluents = node.directAffluentCount();
            if (directAffluents <= 0) {
                continue;
            }

            int color = riverAffluentVectorColor(directAffluents);
            Vec3 base = new Vec3(
                    node.worldX(),
                    node.surfaceY() + TerrainDebugOverlayState.yOffset() + 1.05D,
                    node.worldZ()
            );
            double height = 0.75D + Math.min(directAffluents, 6) * 0.28D;
            Vec3 top = base.add(0.0D, height, 0.0D);
            Gizmos.line(base, top, color, 1.55F);

            int spokes = Math.min(directAffluents, 6);
            double radius = 0.58D + Math.min(directAffluents, 4) * 0.13D;
            for (int i = 0; i < spokes; i++) {
                double angle = (Math.PI * 2.0D * i) / spokes;
                Vec3 end = top.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
                Gizmos.line(top, end, color, 1.25F);
            }
        }
    }

    private static int riverAffluentVectorColor(int affluentCount) {
        return switch (Math.min(Math.max(affluentCount, 0), 5)) {
            case 0 -> argb(255, 35, 145, 255);
            case 1 -> argb(255, 40, 235, 215);
            case 2 -> argb(255, 105, 245, 70);
            case 3 -> argb(255, 255, 225, 45);
            case 4 -> argb(255, 255, 135, 35);
            default -> argb(255, 255, 45, 180);
        };
    }

    private static int directionX(byte direction) {
        return switch (direction) {
            case 1, 2, 3 -> 1;
            case 5, 6, 7 -> -1;
            default -> 0;
        };
    }

    private static int directionZ(byte direction) {
        return switch (direction) {
            case 3, 4, 5 -> 1;
            case 7, 0, 1 -> -1;
            default -> 0;
        };
    }

    private static float widthVisualNormalized(float widthBlocks) {
        float base = normalize(widthBlocks, TerrainRiverConfig.MIN_WIDTH_BLOCKS, TerrainRiverConfig.MAX_WIDTH_BLOCKS);
        return (float) Math.sqrt(clamp01(base));
    }

    private static float normalize(float value, float min, float max) {
        if (max == min) {
            return value <= min ? 0.0F : 1.0F;
        }
        return clamp01((value - min) / (max - min));
    }

    private static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    private static int argb(int a, int r, int g, int b) {
        return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
    }
}
