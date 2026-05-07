package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.FlowAccumulationMap;
import com.github.xandergos.terraindiffusionmc.river.FlowDirectionMap;
import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.Vec3d;

/** World-space debug overlay for flow accumulation. */
public final class FlowAccumulationOverlayRenderer {
    private static final double Y_OFFSET = 1.82D;
    private static final double THICKNESS = 0.12D;
    private static final double CELL_INSET = 1.45D;

    private FlowAccumulationOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(FlowAccumulationOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        RiverDebugOverlayState.AccumulationLayer layer = RiverDebugOverlayState.accumulationLayer();
        if (layer == RiverDebugOverlayState.AccumulationLayer.NONE) {
            return;
        }

        FlowAccumulationMap accumulationMap = ClientFlowAccumulationMapCache.current();
        if (accumulationMap == null) {
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
            renderAccumulation(buffer, accumulationMap, layer, cameraPos);
            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                renderLayer.draw(builtBuffer);
            }
        } finally {
            allocator.close();
        }
    }

    private static void renderAccumulation(
            BufferBuilder buffer,
            FlowAccumulationMap accumulationMap,
            RiverDebugOverlayState.AccumulationLayer layer,
            Vec3d cameraPos
    ) {
        LowResHeightmap heightmap = accumulationMap.heightmap();
        FlowDirectionMap flowMap = accumulationMap.flowMap();
        int cellSize = accumulationMap.cellSizeBlocks();

        for (int localZ = 0; localZ < accumulationMap.heightCells(); localZ++) {
            int worldCellZ = accumulationMap.originCellZ() + localZ;
            double z0 = worldCellZ * (double) cellSize + CELL_INSET - cameraPos.z;
            double z1 = (worldCellZ + 1) * (double) cellSize - CELL_INSET - cameraPos.z;

            for (int localX = 0; localX < accumulationMap.widthCells(); localX++) {
                int worldCellX = accumulationMap.originCellX() + localX;
                double x0 = worldCellX * (double) cellSize + CELL_INSET - cameraPos.x;
                double x1 = (worldCellX + 1) * (double) cellSize - CELL_INSET - cameraPos.x;
                double terrainY = heightmap.heightAtLocal(localX, localZ);
                double y0 = terrainY + Y_OFFSET - cameraPos.y;
                double y1 = y0 + THICKNESS;

                RenderDecision decision = renderDecision(accumulationMap, flowMap, layer, localX, localZ);
                if (!decision.visible()) {
                    continue;
                }

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
            FlowAccumulationMap accumulationMap,
            FlowDirectionMap flowMap,
            RiverDebugOverlayState.AccumulationLayer layer,
            int localX,
            int localZ
    ) {
        byte direction = flowMap.directionAtLocal(localX, localZ);

        if (layer == RiverDebugOverlayState.AccumulationLayer.CYCLES) {
            return accumulationMap.isCycleAtLocal(localX, localZ)
                    ? new RenderDecision(true, rgb(235, 40, 220), 175)
                    : RenderDecision.hidden();
        }

        if (layer == RiverDebugOverlayState.AccumulationLayer.SINKS) {
            return FlowDirectionMap.isSink(direction)
                    ? new RenderDecision(true, rgb(230, 45, 65), 190)
                    : RenderDecision.hidden();
        }

        if (layer == RiverDebugOverlayState.AccumulationLayer.WINDOW_OUTLETS) {
            return accumulationMap.isOutletAtLocal(localX, localZ)
                    ? new RenderDecision(true, rgb(35, 120, 240), 115)
                    : RenderDecision.hidden();
        }

        if (layer == RiverDebugOverlayState.AccumulationLayer.TERMINALS) {
            if (accumulationMap.isCycleAtLocal(localX, localZ)) {
                return new RenderDecision(true, rgb(235, 40, 220), 175);
            }
            if (FlowDirectionMap.isSink(direction)) {
                return new RenderDecision(true, rgb(230, 45, 65), 190);
            }
            if (accumulationMap.isOutletAtLocal(localX, localZ)) {
                return new RenderDecision(true, rgb(35, 120, 240), 85);
            }
            return RenderDecision.hidden();
        }

        float value = accumulationMap.debugValueAtLocal(localX, localZ);
        if (value <= 0.0f && accumulationMap.accumulationAtLocal(localX, localZ) <= 1) {
            return new RenderDecision(true, rgb(8, 18, 38), 48);
        }
        return new RenderDecision(true, heatColor(value), 130);
    }

    private static int heatColor(float value) {
        float t = clamp01(value / 100.0f);
        // Accumulation: dark blue -> cyan -> yellow -> red -> white.
        return lerp5(
                rgb(10, 25, 65),
                rgb(25, 160, 220),
                rgb(230, 215, 70),
                rgb(225, 70, 35),
                rgb(250, 250, 250),
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

    private static int lerp5(int a, int b, int c, int d, int e, float t) {
        if (t < 0.25f) {
            return lerpColor(a, b, t / 0.25f);
        }
        if (t < 0.50f) {
            return lerpColor(b, c, (t - 0.25f) / 0.25f);
        }
        if (t < 0.75f) {
            return lerpColor(c, d, (t - 0.50f) / 0.25f);
        }
        return lerpColor(d, e, (t - 0.75f) / 0.25f);
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
