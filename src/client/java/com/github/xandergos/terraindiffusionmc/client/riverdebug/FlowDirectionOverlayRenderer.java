package com.github.xandergos.terraindiffusionmc.client.riverdebug;

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

/** World-space debug overlay for the weighted D8 flow-direction layer. */
public final class FlowDirectionOverlayRenderer {
    private static final double CELL_INSET = 1.10D;
    private static final double CELL_Y_OFFSET = 1.58D;
    private static final double ARROW_Y_OFFSET = 2.05D;
    private static final double CELL_THICKNESS = 0.10D;

    private FlowDirectionOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(FlowDirectionOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        RiverDebugOverlayState.FlowLayer layer = RiverDebugOverlayState.flowLayer();
        if (layer == RiverDebugOverlayState.FlowLayer.NONE) {
            return;
        }

        FlowDirectionMap flowMap = ClientFlowDirectionMapCache.current();
        if (flowMap == null) {
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
            renderFlow(buffer, flowMap, layer, cameraPos);
            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                renderLayer.draw(builtBuffer);
            }
        } finally {
            allocator.close();
        }
    }

    private static void renderFlow(
            BufferBuilder buffer,
            FlowDirectionMap flowMap,
            RiverDebugOverlayState.FlowLayer layer,
            Vec3d cameraPos
    ) {
        LowResHeightmap heightmap = flowMap.heightmap();
        int cellSize = flowMap.cellSizeBlocks();

        for (int localZ = 0; localZ < flowMap.heightCells(); localZ++) {
            int worldCellZ = flowMap.originCellZ() + localZ;
            double z0 = worldCellZ * (double) cellSize + CELL_INSET - cameraPos.z;
            double z1 = (worldCellZ + 1) * (double) cellSize - CELL_INSET - cameraPos.z;

            for (int localX = 0; localX < flowMap.widthCells(); localX++) {
                int worldCellX = flowMap.originCellX() + localX;
                double x0 = worldCellX * (double) cellSize + CELL_INSET - cameraPos.x;
                double x1 = (worldCellX + 1) * (double) cellSize - CELL_INSET - cameraPos.x;
                double terrainY = heightmap.heightAtLocal(localX, localZ);
                byte direction = flowMap.directionAtLocal(localX, localZ);

                if (FlowDirectionMap.isEdge(direction)) {
                    continue;
                }

                if (layer == RiverDebugOverlayState.FlowLayer.SINKS) {
                    if (FlowDirectionMap.isSink(direction)) {
                        double y0 = terrainY + CELL_Y_OFFSET - cameraPos.y;
                        drawBox(buffer, (float) x0, (float) y0, (float) z0, (float) x1, (float) (y0 + CELL_THICKNESS), (float) z1, rgb(230, 30, 60), 150);
                    }
                    continue;
                }

                if (layer == RiverDebugOverlayState.FlowLayer.SCORE || layer == RiverDebugOverlayState.FlowLayer.DROP) {
                    double y0 = terrainY + CELL_Y_OFFSET - cameraPos.y;
                    int color = FlowDirectionMap.isSink(direction)
                            ? rgb(230, 30, 60)
                            : colorForDebugLayer(layer, flowMap.debugValueAtLocal(localX, localZ));
                    drawBox(buffer, (float) x0, (float) y0, (float) z0, (float) x1, (float) (y0 + CELL_THICKNESS), (float) z1, color, 105);
                }

                if (FlowDirectionMap.isDirected(direction)) {
                    renderArrow(buffer, flowMap, localX, localZ, direction, terrainY, cameraPos);
                } else if (FlowDirectionMap.isSink(direction) && layer == RiverDebugOverlayState.FlowLayer.ARROWS) {
                    renderSinkMarker(buffer, worldCellX, worldCellZ, cellSize, terrainY, cameraPos);
                }
            }
        }
    }

    private static void renderArrow(
            BufferBuilder buffer,
            FlowDirectionMap flowMap,
            int localX,
            int localZ,
            byte direction,
            double terrainY,
            Vec3d cameraPos
    ) {
        int cellSize = flowMap.cellSizeBlocks();
        int worldCellX = flowMap.originCellX() + localX;
        int worldCellZ = flowMap.originCellZ() + localZ;

        double centerX = (worldCellX + 0.5D) * cellSize;
        double centerZ = (worldCellZ + 0.5D) * cellSize;
        double dx = FlowDirectionMap.dx(direction);
        double dz = FlowDirectionMap.dz(direction);
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 0.0D) {
            return;
        }
        dx /= length;
        dz /= length;

        double drop = selectedDrop(flowMap, localX, localZ, direction);
        int color = drop > 0.0D ? rgb(40, 210, 235) : rgb(245, 185, 55);
        double y = terrainY + ARROW_Y_OFFSET;
        double small = Math.max(0.55D, Math.min(2.4D, cellSize * 0.045D));
        double large = small * 1.65D;

        // Dot-chain arrow: works with the filled-box debug layer and avoids version-fragile line rendering.
        for (int i = 1; i <= 4; i++) {
            double t = i * 0.085D * cellSize;
            double px = centerX + dx * t;
            double pz = centerZ + dz * t;
            drawCenteredBox(buffer, px, y, pz, small, small * 0.42D, color, 175, cameraPos);
        }

        double headDistance = 0.42D * cellSize;
        drawCenteredBox(buffer, centerX + dx * headDistance, y, centerZ + dz * headDistance, large, small * 0.55D, color, 210, cameraPos);
    }

    private static void renderSinkMarker(
            BufferBuilder buffer,
            int worldCellX,
            int worldCellZ,
            int cellSize,
            double terrainY,
            Vec3d cameraPos
    ) {
        double centerX = (worldCellX + 0.5D) * cellSize;
        double centerZ = (worldCellZ + 0.5D) * cellSize;
        double size = Math.max(1.25D, Math.min(4.0D, cellSize * 0.075D));
        drawCenteredBox(buffer, centerX, terrainY + ARROW_Y_OFFSET, centerZ, size, size * 0.65D, rgb(230, 30, 60), 220, cameraPos);
    }

    private static double selectedDrop(FlowDirectionMap flowMap, int localX, int localZ, byte direction) {
        int nx = localX + FlowDirectionMap.dx(direction);
        int nz = localZ + FlowDirectionMap.dz(direction);
        if (nx < 0 || nz < 0 || nx >= flowMap.widthCells() || nz >= flowMap.heightCells()) {
            return 0.0D;
        }
        LowResHeightmap heightmap = flowMap.heightmap();
        return Math.max(0.0D, heightmap.heightAtLocal(localX, localZ) - heightmap.heightAtLocal(nx, nz));
    }

    private static int colorForDebugLayer(RiverDebugOverlayState.FlowLayer layer, float value) {
        float t = clamp01(value / 100.0f);
        if (layer == RiverDebugOverlayState.FlowLayer.DROP) {
            // Selected drop: dark blue -> cyan -> white.
            return lerp3(rgb(20, 35, 95), rgb(40, 200, 230), rgb(245, 245, 245), t);
        }
        // Score: green is a cheap/good decision, red/white is expensive or marginal.
        return lerp4(rgb(25, 150, 60), rgb(180, 190, 50), rgb(220, 95, 35), rgb(245, 245, 245), t);
    }

    private static void drawCenteredBox(
            BufferBuilder buffer,
            double centerX,
            double centerY,
            double centerZ,
            double horizontalRadius,
            double verticalRadius,
            int color,
            int alpha,
            Vec3d cameraPos
    ) {
        drawBox(
                buffer,
                (float) (centerX - horizontalRadius - cameraPos.x),
                (float) (centerY - verticalRadius - cameraPos.y),
                (float) (centerZ - horizontalRadius - cameraPos.z),
                (float) (centerX + horizontalRadius - cameraPos.x),
                (float) (centerY + verticalRadius - cameraPos.y),
                (float) (centerZ + horizontalRadius - cameraPos.z),
                color,
                alpha
        );
    }

    private static int lerp3(int a, int b, int c, float t) {
        if (t < 0.5f) {
            return lerpColor(a, b, t / 0.5f);
        }
        return lerpColor(b, c, (t - 0.5f) / 0.5f);
    }

    private static int lerp4(int a, int b, int c, int d, float t) {
        if (t < 0.33333334f) {
            return lerpColor(a, b, t / 0.33333334f);
        }
        if (t < 0.6666667f) {
            return lerpColor(b, c, (t - 0.33333334f) / 0.33333334f);
        }
        return lerpColor(c, d, (t - 0.6666667f) / 0.33333334f);
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

    private static float clamp01(float value) {
        if (value < 0.0f) return 0.0f;
        if (value > 1.0f) return 1.0f;
        return value;
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
}
