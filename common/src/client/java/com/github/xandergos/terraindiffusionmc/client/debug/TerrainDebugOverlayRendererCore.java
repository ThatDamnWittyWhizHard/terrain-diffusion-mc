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

    private static int argb(int a, int r, int g, int b) {
        return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
    }
}
