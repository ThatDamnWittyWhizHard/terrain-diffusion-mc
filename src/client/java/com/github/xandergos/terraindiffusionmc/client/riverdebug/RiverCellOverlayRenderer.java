package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.FlowDirectionMap;
import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import com.github.xandergos.terraindiffusionmc.river.RiverCellMap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.Vec3d;

/** World-space debug overlay for raster river extraction. */
public final class RiverCellOverlayRenderer {
    private static final double Y_OFFSET = 2.04D;
    private static final double THICKNESS = 0.14D;
    private static final double CELL_INSET = 2.10D;

    private RiverCellOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(RiverCellOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        RiverDebugOverlayState.RiverLayer layer = RiverDebugOverlayState.riverLayer();
        if (layer == RiverDebugOverlayState.RiverLayer.NONE) {
            return;
        }

        RiverCellMap riverMap = ClientRiverCellMapCache.current();
        if (riverMap == null) {
            return;
        }

        if (context.worldState() == null || context.worldState().cameraRenderState == null) {
            return;
        }

        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        if (cameraPos == null) {
            return;
        }

        RenderLayer renderLayer = RenderLayers.debugFilledBox();
        BufferAllocator allocator = new BufferAllocator(renderLayer.getExpectedBufferSize());
        BufferBuilder buffer = new BufferBuilder(
                allocator,
                renderLayer.getDrawMode(),
                renderLayer.getVertexFormat()
        );

        try {
            renderRivers(buffer, riverMap, layer, cameraPos);
            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                renderLayer.draw(builtBuffer);
            }
        } finally {
            allocator.close();
        }
    }

    private static void renderRivers(
            BufferBuilder buffer,
            RiverCellMap riverMap,
            RiverDebugOverlayState.RiverLayer layer,
            Vec3d cameraPos
    ) {
        LowResHeightmap heightmap = riverMap.heightmap();
        int cellSize = riverMap.cellSizeBlocks();

        for (int localZ = 0; localZ < riverMap.heightCells(); localZ++) {
            int worldCellZ = riverMap.originCellZ() + localZ;
            double z0 = worldCellZ * (double) cellSize + CELL_INSET - cameraPos.z;
            double z1 = (worldCellZ + 1) * (double) cellSize - CELL_INSET - cameraPos.z;

            for (int localX = 0; localX < riverMap.widthCells(); localX++) {
                if (!riverMap.isRiverAtLocal(localX, localZ)) {
                    continue;
                }

                RenderDecision decision = renderDecision(riverMap, layer, localX, localZ);
                if (!decision.visible()) {
                    continue;
                }

                int worldCellX = riverMap.originCellX() + localX;
                double x0 = worldCellX * (double) cellSize + CELL_INSET - cameraPos.x;
                double x1 = (worldCellX + 1) * (double) cellSize - CELL_INSET - cameraPos.x;
                double terrainY = heightmap.heightAtLocal(localX, localZ);
                double y0 = terrainY + Y_OFFSET - cameraPos.y;
                double y1 = y0 + THICKNESS;

                drawBox(
                        buffer,
                        (float) x0, (float) y0, (float) z0,
                        (float) x1, (float) y1, (float) z1,
                        decision.color(),
                        decision.alpha()
                );

                if (RiverDebugOverlayState.isWireframeEnabled()) {
                    drawCellBorder(buffer, x0, y1 + 0.020D, z0, x1, z1);
                }
            }
        }
    }

    private static RenderDecision renderDecision(
            RiverCellMap riverMap,
            RiverDebugOverlayState.RiverLayer layer,
            int localX,
            int localZ
    ) {
        return switch (layer) {
            case RIVER_CELLS -> new RenderDecision(true, riverIntensityColor(riverMap.riverIntensityAtLocal(localX, localZ)), 160);
            case SOURCES -> riverMap.isSourceAtLocal(localX, localZ)
                    ? new RenderDecision(true, rgb(70, 235, 90), 210)
                    : RenderDecision.hidden();
            case CONFLUENCES -> riverMap.isConfluenceAtLocal(localX, localZ)
                    ? new RenderDecision(true, rgb(255, 205, 45), 220)
                    : RenderDecision.hidden();
            case TERMINALS -> riverMap.isTerminalAtLocal(localX, localZ)
                    ? terminalDecision(riverMap, localX, localZ)
                    : RenderDecision.hidden();
            case CLASSIFIED -> classifiedDecision(riverMap, localX, localZ);
            case NONE -> RenderDecision.hidden();
        };
    }

    private static RenderDecision classifiedDecision(RiverCellMap riverMap, int localX, int localZ) {
        if (riverMap.isConfluenceAtLocal(localX, localZ)) {
            return new RenderDecision(true, rgb(255, 205, 45), 220);
        }
        if (riverMap.isTerminalAtLocal(localX, localZ)) {
            return terminalDecision(riverMap, localX, localZ);
        }
        if (riverMap.isSourceAtLocal(localX, localZ)) {
            return new RenderDecision(true, rgb(70, 235, 90), 205);
        }
        return new RenderDecision(true, riverIntensityColor(riverMap.riverIntensityAtLocal(localX, localZ)), 145);
    }

    private static RenderDecision terminalDecision(RiverCellMap riverMap, int localX, int localZ) {
        byte direction = riverMap.flowMap().directionAtLocal(localX, localZ);
        if (FlowDirectionMap.isSink(direction)) {
            return new RenderDecision(true, rgb(230, 45, 65), 220);
        }
        if (FlowDirectionMap.isEdge(direction)) {
            return new RenderDecision(true, rgb(80, 120, 255), 165);
        }
        return new RenderDecision(true, rgb(170, 75, 230), 190);
    }

    private static int riverIntensityColor(float value) {
        float t = clamp01(value);
        return lerp4(
                rgb(35, 135, 230),
                rgb(40, 220, 230),
                rgb(245, 245, 245),
                rgb(60, 80, 255),
                t
        );
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
        drawBox(buffer, (float) x0, (float) y, (float) z0, (float) x1, (float) (y + thickness), (float) (z0 + thickness), rgb(0, 0, 0), 120);
        drawBox(buffer, (float) x0, (float) y, (float) (z1 - thickness), (float) x1, (float) (y + thickness), (float) z1, rgb(0, 0, 0), 120);
        drawBox(buffer, (float) x0, (float) y, (float) z0, (float) (x0 + thickness), (float) (y + thickness), (float) z1, rgb(0, 0, 0), 120);
        drawBox(buffer, (float) (x1 - thickness), (float) y, (float) z0, (float) x1, (float) (y + thickness), (float) z1, rgb(0, 0, 0), 120);
    }

    private static int lerp4(int a, int b, int c, int d, float t) {
        if (t < 0.40f) {
            return lerpColor(a, b, t / 0.40f);
        }
        if (t < 0.78f) {
            return lerpColor(b, c, (t - 0.40f) / 0.38f);
        }
        return lerpColor(c, d, (t - 0.78f) / 0.22f);
    }

    private static int lerpColor(int a, int b, float t) {
        float u = clamp01(t);
        int ar = red(a);
        int ag = green(a);
        int ab = blue(a);
        return rgb(
                Math.round(ar + (red(b) - ar) * u),
                Math.round(ag + (green(b) - ag) * u),
                Math.round(ab + (blue(b) - ab) * u)
        );
    }

    private static float clamp01(float value) {
        if (value < 0.0f) return 0.0f;
        if (value > 1.0f) return 1.0f;
        return value;
    }

    private static int rgb(int red, int green, int blue) {
        return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    private static int red(int rgb) {
        return (rgb >>> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >>> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static void drawBox(
            BufferBuilder buffer,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            int color,
            int alpha
    ) {
        vertex(buffer, x0, y0, z1, color, alpha);
        vertex(buffer, x1, y0, z1, color, alpha);
        vertex(buffer, x1, y1, z1, color, alpha);
        vertex(buffer, x0, y1, z1, color, alpha);

        vertex(buffer, x1, y0, z0, color, alpha);
        vertex(buffer, x0, y0, z0, color, alpha);
        vertex(buffer, x0, y1, z0, color, alpha);
        vertex(buffer, x1, y1, z0, color, alpha);

        vertex(buffer, x0, y0, z0, color, alpha);
        vertex(buffer, x0, y0, z1, color, alpha);
        vertex(buffer, x0, y1, z1, color, alpha);
        vertex(buffer, x0, y1, z0, color, alpha);

        vertex(buffer, x1, y0, z1, color, alpha);
        vertex(buffer, x1, y0, z0, color, alpha);
        vertex(buffer, x1, y1, z0, color, alpha);
        vertex(buffer, x1, y1, z1, color, alpha);

        vertex(buffer, x0, y1, z1, color, alpha);
        vertex(buffer, x1, y1, z1, color, alpha);
        vertex(buffer, x1, y1, z0, color, alpha);
        vertex(buffer, x0, y1, z0, color, alpha);

        vertex(buffer, x0, y0, z0, color, alpha);
        vertex(buffer, x1, y0, z0, color, alpha);
        vertex(buffer, x1, y0, z1, color, alpha);
        vertex(buffer, x0, y0, z1, color, alpha);
    }

    private static void vertex(BufferBuilder buffer, float x, float y, float z, int color, int alpha) {
        buffer.vertex(x, y, z).color(red(color), green(color), blue(color), alpha);
    }

    private record RenderDecision(boolean visible, int color, int alpha) {
        private static RenderDecision hidden() {
            return new RenderDecision(false, 0, 0);
        }
    }
}
