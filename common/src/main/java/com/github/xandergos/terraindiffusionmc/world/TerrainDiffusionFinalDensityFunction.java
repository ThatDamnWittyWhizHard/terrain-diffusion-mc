package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Final terrain density for the Terrain Diffusion overworld.
 *
 * <p>{@link TerrainDiffusionDensityFunction} intentionally remains a pure surface-height density
 * for {@code preliminary_surface_level}. This final density adds 1.18-style large cheese cave
 * volumes after the terrain surface has been resolved, so cave generation scales vertically with
 * the selected TD world scale instead of stopping at a vanilla-height ceiling.</p>
 */
public class TerrainDiffusionFinalDensityFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionFinalDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionFinalDensityFunction::new);

    public static final KeyDispatchDataCodec<TerrainDiffusionFinalDensityFunction> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);

    private static final FastNoiseLite LARGE_CAVE_REGION = make2dNoise(11927, 1f / 840f, 3, 2.0f, 0.52f);
    private static final FastNoiseLite CHEESE_PRIMARY = make3dNoise(48271, 1f / 255f, 3, 2.0f, 0.48f);
    private static final FastNoiseLite CHEESE_SECONDARY = make3dNoise(73691, 1f / 150f, 2, 2.05f, 0.46f);
    private static final FastNoiseLite CHEESE_DETAIL = make3dNoise(90421, 1f / 78f, 2, 2.2f, 0.42f);

    private static FastNoiseLite make2dNoise(int seed, float frequency, int octaves, float lacunarity, float gain) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(frequency);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(octaves);
        noise.SetFractalLacunarity(lacunarity);
        noise.SetFractalGain(gain);
        return noise;
    }

    private static FastNoiseLite make3dNoise(int seed, float frequency, int octaves, float lacunarity, float gain) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(frequency);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(octaves);
        noise.SetFractalLacunarity(lacunarity);
        noise.SetFractalGain(gain);
        return noise;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return computeFinalDensity(context.blockX(), context.blockY(), context.blockZ());
    }

    private static double computeFinalDensity(int x, int y, int z) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = x >> tileShift;
        int tileZ = z >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data == null || data.heightmap == null) {
            return -y;
        }

        int localX = Math.max(0, Math.min(data.width  - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));

        int targetHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        double terrainDensity = targetHeight - y;
        return applyLargeCaves(terrainDensity, x, y, z, targetHeight);
    }

    private static double applyLargeCaves(double terrainDensity, int x, int y, int z, int surfaceY) {
        double depthBelowSurface = surfaceY - y;
        if (terrainDensity <= 0.0 || depthBelowSurface < 20.0 || y <= -60) {
            return terrainDensity;
        }

        int scale = Math.max(1, WorldScaleManager.getCurrentScale());

        // Compress only the Y coordinate into native terrain space. Horizontal cave footprint stays
        // in block coordinates, while vertical reach grows with TD scales 1..6.
        float scaledY = 63f + ((float) y - 63f) / (float) scale;

        double surfaceSeal = smoothstep(20.0, 86.0, depthBelowSurface);
        double bedrockFade = smoothstep(-60.0, -44.0, y);
        if (surfaceSeal <= 0.0 || bedrockFade <= 0.0) {
            return terrainDensity;
        }

        double region = LARGE_CAVE_REGION.GetNoise(x, z);
        double regionBoost = smoothstep(-0.34, 0.58, region);
        double depthBoost = smoothstep(72.0, 260.0 * scale, depthBelowSurface);

        double cheese = 0.62 * CHEESE_PRIMARY.GetNoise((float) x, scaledY, (float) z)
                + 0.25 * CHEESE_SECONDARY.GetNoise((float) x + 1137f, scaledY * 1.18f, (float) z - 791f)
                + 0.13 * CHEESE_DETAIL.GetNoise((float) x - 409f, scaledY * 1.85f, (float) z + 1301f);

        // Positive cheese regions become large open chambers. The threshold intentionally drops in
        // favorable cave regions and deep terrain, but rises near the surface to keep a natural roof.
        double threshold = 0.36 - 0.13 * regionBoost - 0.08 * depthBoost + 0.28 * (1.0 - surfaceSeal);
        double openness = (cheese + 0.12 * region) - threshold;
        if (openness <= 0.0) {
            return terrainDensity;
        }

        double caveStrength = openness * 128.0 * surfaceSeal * bedrockFade;
        double largeCaveDensity = 2.0 - caveStrength;
        return Math.min(terrainDensity, largeCaveDensity);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        if (edge0 == edge1) return x < edge0 ? 0.0 : 1.0;
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class FillContext {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        HeightmapData data;

        void update(int x, int z) {
            if (x < blockStartX || x >= blockEndX) this.init(x, z);
            if (z < blockStartZ || z >= blockEndZ) this.init(x, z);
        }

        void init(int x, int z) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);

            int tileX = x >> tileShift;
            int tileZ = z >> tileShift;

            this.blockStartX = tileX << tileShift;
            this.blockStartZ = tileZ << tileShift;
            this.blockEndX = blockStartX + tileSize;
            this.blockEndZ = blockStartZ + tileSize;

            this.data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        }
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        if (densities.length == 0) return;

        FillContext ctx = new FillContext();
        DensityFunction.FunctionContext pos = applier.forIndex(0);
        int x = pos.blockX();
        int z = pos.blockZ();
        ctx.init(x, z);

        for (int i = 0; i < densities.length; i++) {
            pos = applier.forIndex(i);
            x = pos.blockX();
            int y = pos.blockY();
            z = pos.blockZ();
            ctx.update(x, z);

            HeightmapData data = ctx.data;
            if (data == null || data.heightmap == null) {
                densities[i] = -y;
                continue;
            }

            int localX = Math.max(0, Math.min(data.width  - 1, x - ctx.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - ctx.blockStartZ));

            int targetHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
            double terrainDensity = targetHeight - y;
            densities[i] = applyLargeCaves(terrainDensity, x, y, z, targetHeight);
        }
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return -2048;
    }

    @Override
    public double maxValue() {
        return 2048;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC_HOLDER;
    }
}
