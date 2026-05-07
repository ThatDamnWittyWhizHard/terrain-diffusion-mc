package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.Vec3d;

public final class LowResHeightmapOverlayRenderer {
    private static final double Y_OFFSET = 1.15D;
    private static final double THICKNESS = 0.08D;
    private static final double CELL_INSET = 0.35D;

    private LowResHeightmapOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(LowResHeightmapOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (!RiverDebugOverlayState.isLowResHeightmapEnabled()) {
            return;
        }

        LowResHeightmap heightmap = ClientLowResHeightmapCache.current();
        if (heightmap == null) {
            return;
        }

        if (context.worldState() == null || context.worldState().cameraRenderState == null) {
            return;
        }

        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        if (cameraPos == null) {
            return;
        }

        RenderLayer layer = RenderLayers.debugFilledBox();

        BufferAllocator allocator = new BufferAllocator(layer.getExpectedBufferSize());
        BufferBuilder buffer = new BufferBuilder(
                allocator,
                layer.getDrawMode(),
                layer.getVertexFormat()
        );

        try {
            renderCells(buffer, heightmap, cameraPos);

            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                layer.draw(builtBuffer);
            }
        } finally {
            allocator.close();
        }
    }

    private static void renderCells(BufferBuilder buffer, LowResHeightmap heightmap, Vec3d cameraPos) {
        int cellSize = heightmap.cellSizeBlocks();

        double minHeight = heightmap.minY();
        double maxHeight = heightmap.maxY();
        double heightRange = Math.max(1.0D, maxHeight - minHeight);

        int minCellX = heightmap.originCellX();
        int minCellZ = heightmap.originCellZ();
        int maxCellX = minCellX + heightmap.widthCells() - 1;
        int maxCellZ = minCellZ + heightmap.heightCells() - 1;

        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                double height = heightmap.heightAtWorldCell(cellX, cellZ);

                double x0 = cellX * (double) cellSize + CELL_INSET - cameraPos.x;
                double x1 = (cellX + 1) * (double) cellSize - CELL_INSET - cameraPos.x;
                double z0 = cellZ * (double) cellSize + CELL_INSET - cameraPos.z;
                double z1 = (cellZ + 1) * (double) cellSize - CELL_INSET - cameraPos.z;

                double y0 = height + Y_OFFSET - cameraPos.y;
                double y1 = y0 + THICKNESS;

                int[] color = colorForHeight(height, minHeight, heightRange);

                drawBox(
                        buffer,
                        (float) x0, (float) y0, (float) z0,
                        (float) x1, (float) y1, (float) z1,
                        color[0], color[1], color[2], 92
                );

                if (RiverDebugOverlayState.isWireframeEnabled()) {
                    drawCellBorder(buffer, x0, y1 + 0.015D, z0, x1, z1);
                }
            }
        }
    }

    private static int[] colorForHeight(double height, double minHeight, double heightRange) {
        double t = clamp01((height - minHeight) / heightRange);

        if (t < 0.25D) {
            return lerpColor(new int[]{30, 70, 180}, new int[]{40, 150, 210}, t / 0.25D);
        }

        if (t < 0.50D) {
            return lerpColor(new int[]{40, 150, 210}, new int[]{80, 180, 80}, (t - 0.25D) / 0.25D);
        }

        if (t < 0.75D) {
            return lerpColor(new int[]{80, 180, 80}, new int[]{190, 170, 80}, (t - 0.50D) / 0.25D);
        }

        return lerpColor(new int[]{190, 170, 80}, new int[]{235, 235, 235}, (t - 0.75D) / 0.25D);
    }

    private static int[] lerpColor(int[] a, int[] b, double t) {
        double u = clamp01(t);

        return new int[]{
                (int) Math.round(a[0] + (b[0] - a[0]) * u),
                (int) Math.round(a[1] + (b[1] - a[1]) * u),
                (int) Math.round(a[2] + (b[2] - a[2]) * u)
        };
    }

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }

        if (value > 1.0D) {
            return 1.0D;
        }

        return value;
    }

    private static void drawCellBorder(
            BufferBuilder buffer,
            double x0,
            double y,
            double z0,
            double x1,
            double z1
    ) {
        double thickness = 0.06D;

        drawBox(
                buffer,
                (float) x0, (float) y, (float) z0,
                (float) x1, (float) (y + thickness), (float) (z0 + thickness),
                0, 0, 0, 120
        );

        drawBox(
                buffer,
                (float) x0, (float) y, (float) (z1 - thickness),
                (float) x1, (float) (y + thickness), (float) z1,
                0, 0, 0, 120
        );

        drawBox(
                buffer,
                (float) x0, (float) y, (float) z0,
                (float) (x0 + thickness), (float) (y + thickness), (float) z1,
                0, 0, 0, 120
        );

        drawBox(
                buffer,
                (float) (x1 - thickness), (float) y, (float) z0,
                (float) x1, (float) (y + thickness), (float) z1,
                0, 0, 0, 120
        );
    }

    private static void drawBox(
            BufferBuilder buffer,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        // South face
        vertex(buffer, x0, y0, z1, red, green, blue, alpha);
        vertex(buffer, x1, y0, z1, red, green, blue, alpha);
        vertex(buffer, x1, y1, z1, red, green, blue, alpha);
        vertex(buffer, x0, y1, z1, red, green, blue, alpha);

        // North face
        vertex(buffer, x1, y0, z0, red, green, blue, alpha);
        vertex(buffer, x0, y0, z0, red, green, blue, alpha);
        vertex(buffer, x0, y1, z0, red, green, blue, alpha);
        vertex(buffer, x1, y1, z0, red, green, blue, alpha);

        // West face
        vertex(buffer, x0, y0, z0, red, green, blue, alpha);
        vertex(buffer, x0, y0, z1, red, green, blue, alpha);
        vertex(buffer, x0, y1, z1, red, green, blue, alpha);
        vertex(buffer, x0, y1, z0, red, green, blue, alpha);

        // East face
        vertex(buffer, x1, y0, z1, red, green, blue, alpha);
        vertex(buffer, x1, y0, z0, red, green, blue, alpha);
        vertex(buffer, x1, y1, z0, red, green, blue, alpha);
        vertex(buffer, x1, y1, z1, red, green, blue, alpha);

        // Top face
        vertex(buffer, x0, y1, z1, red, green, blue, alpha);
        vertex(buffer, x1, y1, z1, red, green, blue, alpha);
        vertex(buffer, x1, y1, z0, red, green, blue, alpha);
        vertex(buffer, x0, y1, z0, red, green, blue, alpha);

        // Bottom face
        vertex(buffer, x0, y0, z0, red, green, blue, alpha);
        vertex(buffer, x1, y0, z0, red, green, blue, alpha);
        vertex(buffer, x1, y0, z1, red, green, blue, alpha);
        vertex(buffer, x0, y0, z1, red, green, blue, alpha);
    }

    private static void vertex(
            BufferBuilder buffer,
            float x,
            float y,
            float z,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        buffer.vertex(x, y, z).color(red, green, blue, alpha);
    }
}