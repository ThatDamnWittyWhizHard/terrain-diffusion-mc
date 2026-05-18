package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.github.xandergos.terraindiffusionmc.biome.BiomePalette;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private static final FastNoiseLite CAVE_REGION_NOISE = makeCaveNoise(83017, 1f / 360f, 3, 2.0f, 0.54f);
    private static final FastNoiseLite CAVE_DETAIL_NOISE = makeCaveNoise(42157, 1f / 96f, 2, 2.1f, 0.50f);
    private static final FastNoiseLite DEEP_DARK_NOISE = makeCaveNoise(69239, 1f / 420f, 3, 2.0f, 0.55f);

    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    private static FastNoiseLite makeCaveNoise(int seed, float frequency, int octaves, float lacunarity, float gain) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(frequency);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(octaves);
        noise.SetFractalLacunarity(lacunarity);
        noise.SetFractalGain(gain);
        return noise;
    }

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            biomeIdMap = BiomePalette.buildHolderMap(this.biomeLookup);
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return biomeIdMap.values().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> defaultEntry = biomeIdMap.get(BiomePalette.DEFAULT);

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockY = QuartPos.toBlock(y);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));

            short biomeId = data.biomeIds[localZ][localX];
            if (data.heightmap != null) {
                int surfaceY = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
                biomeId = applyUndergroundBiome(biomeId, blockX, blockY, blockZ, surfaceY);
            }

            Holder<Biome> entry = biomeIdMap.get(biomeId);
            if (entry != null) return entry;
        }

        return defaultEntry;
    }

    private static short applyUndergroundBiome(short surfaceBiome, int blockX, int blockY, int blockZ, int surfaceY) {
        if (blockY > 80 || blockY > surfaceY - 24) {
            return surfaceBiome;
        }

        // Vanilla-style vertical biome layering: surface biomes above, cave biomes only in
        // actual underground noise-biome samples. This keeps grass/snow/desert surfaces out of caves
        // while allowing vanilla cave decorations to run in lush/dripstone/deep_dark regions.
        float depth = Math.min(1f, Math.max(0f, (surfaceY - blockY - 24f) / 96f));
        float lowY = Math.min(1f, Math.max(0f, (32f - blockY) / 96f));
        float cave = 0.62f * CAVE_REGION_NOISE.GetNoise(blockX, blockZ)
                + 0.38f * CAVE_DETAIL_NOISE.GetNoise(blockX, blockZ);
        float deep = DEEP_DARK_NOISE.GetNoise(blockX, blockZ);

        if (blockY < 8 && depth > 0.45f && deep > 0.28f + 0.16f * cave) {
            return BiomePalette.DEEP_DARK;
        }

        if (depth < 0.25f) {
            return surfaceBiome;
        }

        if (cave > 0.18f - 0.10f * depth) {
            return BiomePalette.LUSH_CAVES;
        }
        if (cave < -0.16f + 0.08f * lowY) {
            return BiomePalette.DRIPSTONE_CAVES;
        }

        return surfaceBiome;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}
