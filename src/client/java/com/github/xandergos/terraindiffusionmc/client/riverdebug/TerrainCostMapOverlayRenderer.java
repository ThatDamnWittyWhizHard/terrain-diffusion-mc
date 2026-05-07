package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import com.github.xandergos.terraindiffusionmc.river.TerrainCostMap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.Vec3d;

/** World-space debug overlay for terrain cost layers used by the future river router. */
public final class TerrainCostMapOverlayRenderer {
    private static final double Y_OFFSET = 1.34D;
    private static final double THICKNESS = 0.10D;
    private static final double CELL_INSET = 0.75D;

    private TerrainCostMapOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(TerrainCostMapOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        RiverDebugOverlayState.CostLayer layer = RiverDebugOverlayState.costLayer();
        if (layer == RiverDebugOverlayState.CostLayer.NONE) {
            return;
        }

        TerrainCostMap costMap = ClientTerrainCostMapCache.current();
        if (costMap == null) {
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
            renderCells(buffer, costMap, layer, cameraPos);
            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                renderLayer.draw(builtBuffer);
            }
        } finally {
            allocator.close();
        }
    }

    private static void renderCells(
            BufferBuilder buffer,
            TerrainCostMap costMap,
            RiverDebugOverlayState.CostLayer debugLayer,
            Vec3d cameraPos
    ) {
        TerrainCostMap.Layer layer = toCostMapLayer(debugLayer);
        LowResHeightmap heightmap = costMap.heightmap();
        int cellSize = costMap.cellSizeBlocks();

        for (int localZ = 0; localZ < costMap.heightCells(); localZ++) {
            int worldCellZ = costMap.originCellZ() + localZ;
            double z0 = worldCellZ * (double) cellSize + CELL_INSET - cameraPos.z;
            double z1 = (worldCellZ + 1) * (double) cellSize - CELL_INSET - cameraPos.z;

            for (int localX = 0; localX < costMap.widthCells(); localX++) {
                int worldCellX = costMap.originCellX() + localX;
                double x0 = worldCellX * (double) cellSize + CELL_INSET - cameraPos.x;
                double x1 = (worldCellX + 1) * (double) cellSize - CELL_INSET - cameraPos.x;

                double terrainY = heightmap.heightAtLocal(localX, localZ);
                double y0 = terrainY + Y_OFFSET - cameraPos.y;
                double y1 = y0 + THICKNESS;

                float value = costMap.valueAtLocal(layer, localX, localZ);
                int color = colorForLayer(debugLayer, value);

                drawBox(
                        buffer,
                        (float) x0, (float) y0, (float) z0,
                        (float) x1, (float) y1, (float) z1,
                        color, 105
                );

                if (RiverDebugOverlayState.isWireframeEnabled()) {
                    drawCellBorder(buffer, x0, y1 + 0.018D, z0, x1, z1);
                }
            }
        }
    }

    private static TerrainCostMap.Layer toCostMapLayer(RiverDebugOverlayState.CostLayer layer) {
        return switch (layer) {
            case FINAL_COST -> TerrainCostMap.Layer.FINAL_COST;
            case SLOPE_COST -> TerrainCostMap.Layer.SLOPE_COST;
            case RIDGE_COST -> TerrainCostMap.Layer.RIDGE_COST;
            case VALLEY_BONUS -> TerrainCostMap.Layer.VALLEY_BONUS;
            case BIOME_COST -> TerrainCostMap.Layer.BIOME_COST;
            case ROCK_SOIL_COST -> TerrainCostMap.Layer.ROCK_SOIL_COST;
            case FORBIDDEN_COST -> TerrainCostMap.Layer.FORBIDDEN_COST;
            case NONE -> TerrainCostMap.Layer.FINAL_COST;
        };
    }

    private static int colorForLayer(RiverDebugOverlayState.CostLayer layer, float value) {
        float t = clamp01(value / 100.0f);

        if (layer == RiverDebugOverlayState.CostLayer.VALLEY_BONUS) {
            // Bonus layer : high value is desirable so use blue/cyan instead of red.
            return lerp3(rgb(25, 25, 40), rgb(20, 120, 190), rgb(60, 240, 210), t);
        }

        if (layer == RiverDebugOverlayState.CostLayer.FORBIDDEN_COST) {
            // Reserved hard constraints : black -> purple -> magenta.
            return lerp3(rgb(20, 20, 20), rgb(95, 20, 130), rgb(240, 30, 220), t);
        }

        // Cost layers : green is cheap, yellow is medium and red/white is expensive.
        return lerp4(
                rgb(25, 150, 60),
                rgb(180, 190, 50),
                rgb(220, 95, 35),
                rgb(245, 245, 245),
                t
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
