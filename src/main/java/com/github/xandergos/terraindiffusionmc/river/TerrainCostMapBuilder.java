package com.github.xandergos.terraindiffusionmc.river;

/** Builds the first river cost layer from a low-resolution global heightmap. */
public final class TerrainCostMapBuilder {
    private TerrainCostMapBuilder() {
    }

    /** Production/default path : store only the final cost layer. */
    public static TerrainCostMap build(LowResHeightmap heightmap) {
        return build(heightmap, TerrainCostParameters.defaults(), null);
    }

    /** Production/default path : store only the final cost layer. */
    public static TerrainCostMap build(LowResHeightmap heightmap, TerrainCostParameters parameters) {
        return build(heightmap, parameters, null);
    }

    /** Debug path : store the final cost plus one optional diagnostic layer. */
    public static TerrainCostMap buildForDebug(LowResHeightmap heightmap, TerrainCostMap.Layer debugLayer) {
        return build(heightmap, TerrainCostParameters.defaults(), debugLayer);
    }

    public static TerrainCostMap build(
            LowResHeightmap heightmap,
            TerrainCostParameters parameters,
            TerrainCostMap.Layer debugLayer
    ) {
        int width = heightmap.widthCells();
        int height = heightmap.heightCells();
        int count = width * height;

        byte[] finalCost = new byte[count];
        byte[] debugValues = debugLayer != null && debugLayer != TerrainCostMap.Layer.FINAL_COST
                ? new byte[count]
                : null;

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;

                LocalShape shape = localShape(heightmap, x, z);

                float slopeLayer = 100.0f * smoothstep(parameters.slopeCostStart(), parameters.slopeCostFull(), shape.slope());
                float ridgeLayer = 100.0f * smoothstep(parameters.ridgeHeightStart(), parameters.ridgeHeightFull(), shape.ridgeHeight());
                float valleyLayer = 100.0f * smoothstep(parameters.valleyDepthStart(), parameters.valleyDepthFull(), shape.valleyDepth());

                // Neutral placeholders. Store them only when real biome/geology masks are wired in.
                float biomeLayer = 0.0f;
                float rockSoilLayer = 0.0f;
                float forbiddenLayer = 0.0f;

                float composed = parameters.baseCost()
                        + parameters.slopeWeight() * slopeLayer
                        + parameters.ridgeWeight() * ridgeLayer
                        - parameters.valleyBonusWeight() * valleyLayer
                        + parameters.biomeWeight() * biomeLayer
                        + parameters.rockSoilWeight() * rockSoilLayer
                        + parameters.forbiddenWeight() * forbiddenLayer;

                finalCost[index] = costByte(composed);
                if (debugValues != null) {
                    debugValues[index] = costByte(switch (debugLayer) {
                        case SLOPE_COST -> slopeLayer;
                        case RIDGE_COST -> ridgeLayer;
                        case VALLEY_BONUS -> valleyLayer;
                        case BIOME_COST -> biomeLayer;
                        case ROCK_SOIL_COST -> rockSoilLayer;
                        case FORBIDDEN_COST -> forbiddenLayer;
                        case FINAL_COST -> composed;
                    });
                }
            }
        }

        return new TerrainCostMap(heightmap, finalCost, debugLayer, debugValues);
    }

    /** Central-difference dy/dx slope plus convex/concave local relief. */
    private static LocalShape localShape(LowResHeightmap heightmap, int x, int z) {
        float center = heightAtClamped(heightmap, x, z);
        float west = heightAtClamped(heightmap, x - 1, z);
        float east = heightAtClamped(heightmap, x + 1, z);
        float north = heightAtClamped(heightmap, x, z - 1);
        float south = heightAtClamped(heightmap, x, z + 1);

        float dx = (east - west) / (2.0f * heightmap.cellSizeBlocks());
        float dz = (south - north) / (2.0f * heightmap.cellSizeBlocks());
        float slope = (float) Math.sqrt(dx * dx + dz * dz);

        float sum = 0.0f;
        int count = 0;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oz == 0) continue;
                sum += heightAtClamped(heightmap, x + ox, z + oz);
                count++;
            }
        }
        float mean = sum / count;
        return new LocalShape(slope, Math.max(0.0f, center - mean), Math.max(0.0f, mean - center));
    }

    private static float heightAtClamped(LowResHeightmap heightmap, int x, int z) {
        int clampedX = clampInt(x, 0, heightmap.widthCells() - 1);
        int clampedZ = clampInt(z, 0, heightmap.heightCells() - 1);
        return heightmap.heightAtLocal(clampedX, clampedZ);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0f : 0.0f;
        }
        float t = (value - edge0) / (edge1 - edge0);
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    private static byte costByte(float value) {
        int rounded = Math.round(Math.max(0.0f, Math.min(100.0f, value)));
        return (byte) rounded;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record LocalShape(float slope, float ridgeHeight, float valleyDepth) {
    }
}
