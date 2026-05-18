package com.github.xandergos.terraindiffusionmc.pipeline;

import static com.github.xandergos.terraindiffusionmc.biome.BiomePalette.*;

/**
 * Rule-based biome classifier driven by the four pipeline climate channels:
 * temperature, temperature seasonality, precipitation and precipitation variability.
 *
 * <p>Internal biome IDs are defined in {@code BiomePalette}; this class only decides which
 * ID to emit for each terrain pixel.</p>
 */
public final class BiomeClassifier {

    // Fixed-seed perturbations kept deterministic so biome IDs are stable for a given world seed.
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    private static final FastNoiseLite MICRO_BIOME_NOISE, MICRO_BIOME_FINE_NOISE, VEGETATION_PATCH_NOISE, VEGETATION_PATCH_FINE_NOISE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        MICRO_BIOME_NOISE = makeFnl(71191, 1f/38f, 2, 2f, 0.52f);
        MICRO_BIOME_FINE_NOISE = makeFnl(11939, 1f/15f, 2, 2.1f, 0.50f);
        VEGETATION_PATCH_NOISE = makeFnl(37211, 1f/44f, 3, 2f, 0.55f);
        VEGETATION_PATCH_FINE_NOISE = makeFnl(48109, 1f/18f, 2, 2.05f, 0.52f);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (4, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @return short array (H, W) with internal biome IDs from {@code BiomePalette}
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate small perturbations from the original classifier.
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope and aspect from padded elevation using Sobel.
        TerrainDerivatives terrainDerivatives = computeTerrainDerivatives(elevPadded, H, W, pixelSizeM);
        float[] slopeRatio = terrainDerivatives.slopeRatio();
        float[] northness = terrainDerivatives.northness();

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];
                float faceNorth = northness[idx];
                float microCoarse = MICRO_BIOME_NOISE.GetNoise(j0 + c, i0 + r);
                float microFine = MICRO_BIOME_FINE_NOISE.GetNoise(j0 + c, i0 + r);
                float vegetationCoarse = VEGETATION_PATCH_NOISE.GetNoise(j0 + c, i0 + r);
                float vegetationFine = VEGETATION_PATCH_FINE_NOISE.GetNoise(j0 + c, i0 + r);
                float microBiome = clamp01(0.5f + 0.38f * microCoarse + 0.28f * microFine);
                float vegetationPatch = clamp01(0.5f + 0.36f * vegetationCoarse + 0.30f * vegetationFine);

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float tempRaw  = climate[idx];
                float temp     = tempRaw + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precipRaw = Math.max(0f, climate[2 * H * W + idx]);
                float precip   = precipRaw * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold. Do not change: this controls the existing stony_peak distribution.
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Keep the pre-slope vegetation state so jagged_peaks are only injected in true
                // high-cold ice_spikes desert zones, not in every steep cold cliff where trees were
                // stripped by the slope override below.
                boolean naturalTreesNone = treesNone;

                // Slope overrides. Do not change: this feeds the existing stony_peak distribution.
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Aspect-aware snow line. In marginal cold bands, north-facing slopes keep snow
                // longer while south-facing slopes lose it earlier. Very cold zones stay snowy.
                float snowTransition = clamp01((temp + 8f) / 8f) * clamp01((5f - temp) / 7f);
                float aspectSnowBias = 3.5f * faceNorth * snowTransition;
                float snowTemp = temp + snowNoise[idx] - aspectSnowBias;
                float snowSteepLimit = 0.78f
                        + 0.20f * Math.max(0f, faceNorth) * snowTransition
                        - 0.10f * Math.max(0f, -faceNorth) * snowTransition;
                boolean isSteep = slope > snowSteepLimit;
                boolean hasSnow = snowTemp < 0f && precip > 220f && treeMoisture > 0.16f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean deepOcean = elevVal < -220f;
                boolean mountains = altM > 2500f;
                boolean highland  = altM > 900f;
                boolean upland    = altM > 300f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                // Climate refinements from the four pipeline values.
                boolean veryDry = treeMoisture < 0.12f || precip < 180f;
                boolean dry = treeMoisture < 0.35f || precip < 350f;
                boolean semiDry = treeMoisture < 0.55f || precip < 520f;
                boolean moist = treeMoisture >= 0.75f || precip > 800f;
                boolean veryMoist = treeMoisture >= 1.15f || precip > 1250f;
                boolean saturated = treeMoisture >= 1.45f || precip > 1650f;
                boolean seasonalPrecip = pCV > 80f;
                boolean stablePrecip = pCV < 45f;
                boolean highlySeasonalTemp = tSeason > 1800f;
                boolean stableTemp = tSeason < 900f;

                // Cold elevated dry/barren areas become rocky or icy transition bands, not broad grove fallbacks.
                // Keep snowy_slope selection untouched: these flags are only consumed when !hasSnow.
                boolean coldElevated = altM > 1050f && temp < 6f;
                boolean coldElevatedIceSpikesDesert = coldElevated && !hasSnow && naturalTreesNone
                        && (barren || dry || (altM > 1550f && semiDry && temp < 3f));
                boolean coldElevatedIceSpikesCandidate = coldElevatedIceSpikesDesert && treesNone;

                short biome = PLAINS;

                if (isOcean) {
                    if (frozen) biome = deepOcean ? DEEP_FROZEN_OCEAN : FROZEN_OCEAN;
                    else if (cold) biome = deepOcean ? DEEP_COLD_OCEAN : COLD_OCEAN;
                    else if (temperate || cool) biome = deepOcean ? DEEP_OCEAN : OCEAN;
                    else if (warm && !hot) biome = deepOcean ? DEEP_LUKEWARM_OCEAN : LUKEWARM_OCEAN;
                    else biome = WARM_OCEAN;
                } else if (mountains) {
                    if (slopeBare) {
                        if (coldElevated) {
                            biome = chooseColdElevatedPeak(altM, temp, slope, snowNoise[idx], tempNoise[idx], coldElevatedIceSpikesDesert);
                        } else {
                            biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                        }
                    } else if (hasSnow) {
                        if (treesNone) biome = SNOWY_SLOPES;
                        else if (treesSparse || treesForest) biome = chooseSparseSnowyTaiga(
                                effTreeMoisture, vegetationPatch, highland, slopeMedium);
                        else biome = SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (coldElevatedIceSpikesCandidate) biome = ICE_SPIKES;
                        else if (coldElevated || barren || dry || cold || frozen) biome = SNOWY_PLAINS;
                        else biome = MEADOW;
                    } else if (treesSparse || treesForest) {
                        if (cool && moist && stablePrecip) biome = CHERRY_GROVE;
                        else biome = chooseSparseTaiga(effTreeMoisture, vegetationPatch, highland, moist, slopeMedium);
                    } else {
                        biome = moist ? OLD_GROWTH_SPRUCE_TAIGA : TAIGA;
                    }
                } else {
                    // Lowland/midland. The broad tree classes are the same as before; subtype selection
                    // is now finer and comes from climate/elevation instead of scattered hard-coded IDs.
                    if (hasSnow && treesNone) {
                        if (frozen && lowland && precip > 250f && !slopeMedium) biome = ICE_SPIKES;
                        else biome = SNOWY_PLAINS;
                    } else if (hasSnow) {
                        if (treesDense && moist) biome = SNOWY_TAIGA;
                        else biome = (treesSparse || treesForest)
                                ? chooseSparseSnowyTaiga(effTreeMoisture, vegetationPatch, highland, slopeMedium)
                                : SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (coldElevatedIceSpikesCandidate) {
                            biome = ICE_SPIKES;
                        } else if ((warm || hot) && veryDry) {
                            if (hot && upland && precip > 90f && precip < 320f && treeMoisture > 0.04f) biome = BADLANDS;
                            else biome = DESERT;
                        } else if ((warm || hot) && semiDry) {
                            if (slopeMedium && highland) biome = ERODED_BADLANDS;
                            else biome = BADLANDS;
                        } else if (barren && !lowland && (cold || cool || temperate)) {
                            biome = (cold || frozen || coldElevated || (highland && temp < 3f)) ? SNOWY_PLAINS : PLAINS;
                        } else if (dry) {
                            biome = (cold || frozen || coldElevated || (highland && temp < 2f && precip > 220f)) ? SNOWY_PLAINS : PLAINS;
                        } else if (cool && moist && upland && stablePrecip) {
                            biome = MEADOW;
                        } else {
                            biome = (cold || frozen) ? SNOWY_PLAINS : (stablePrecip && temperate ? SUNFLOWER_PLAINS : PLAINS);
                        }
                    } else if (treesSparse || treesForest) {
                        if (hot) {
                            if (saturated && lowland && stablePrecip) biome = BAMBOO_JUNGLE;
                            else biome = SPARSE_JUNGLE;
                        } else if (warm) {
                            if (semiDry && highland && !slopeMedium && !seasonalPrecip) biome = WOODED_BADLANDS;
                            else if (semiDry && seasonalPrecip) biome = slopeMedium ? WINDSWEPT_SAVANNA : (highland ? SAVANNA_PLATEAU : SAVANNA);
                            else if (veryMoist && lowland) biome = MANGROVE_SWAMP;
                            else if (treesForest && !slopeMedium) biome = chooseSparseForest(
                                    effTreeMoisture, vegetationPatch, upland, moist, PLAINS);
                            else biome = SAVANNA;
                        } else if (temperate) {
                            if (slopeMedium) biome = WINDSWEPT_FOREST;
                            else if (moist && stablePrecip && upland) biome = OLD_GROWTH_BIRCH_FOREST;
                            else if (moist && stablePrecip) biome = FLOWER_FOREST;
                            else if (stableTemp && !veryMoist) biome = BIRCH_FOREST;
                            else biome = chooseSparseForest(effTreeMoisture, vegetationPatch, upland, moist,
                                    stablePrecip && temperate ? SUNFLOWER_PLAINS : PLAINS);
                        } else if (cool) {
                            if (moist && stablePrecip && upland) biome = CHERRY_GROVE;
                            else biome = chooseSparseTaiga(effTreeMoisture, vegetationPatch, upland, moist, slopeMedium);
                        } else {
                            biome = chooseSparseTaiga(effTreeMoisture, vegetationPatch, upland, moist, slopeMedium);
                        }
                    } else if (treesDense) {
                        if (hot) {
                            biome = saturated && stablePrecip ? BAMBOO_JUNGLE : JUNGLE;
                        } else if (warm && lowland) {
                            biome = veryMoist ? MANGROVE_SWAMP : SWAMP;
                        } else if (warm && semiDry && seasonalPrecip) {
                            biome = SAVANNA;
                        } else if (temperate) {
                            if (slopeMedium) biome = WINDSWEPT_FOREST;
                            else if (veryMoist && stableTemp && !stablePrecip) biome = PALE_GARDEN;
                            else if (veryMoist && stablePrecip) biome = DARK_FOREST;
                            else if (moist && stableTemp && upland) biome = OLD_GROWTH_BIRCH_FOREST;
                            else if (moist && stableTemp) biome = FLOWER_FOREST;
                            else biome = FOREST;
                        } else if (cool || cold) {
                            if (veryMoist && !highlySeasonalTemp) biome = OLD_GROWTH_SPRUCE_TAIGA;
                            else if (moist) biome = OLD_GROWTH_PINE_TAIGA;
                            else biome = TAIGA;
                        } else {
                            biome = FOREST;
                        }
                    } else { // rainforest
                        if (hot || (warm && temp >= 18f && tStd < 5f)) {
                            if (saturated && stablePrecip) biome = BAMBOO_JUNGLE;
                            else biome = JUNGLE;
                        } else if (lowland) {
                            biome = warm ? MANGROVE_SWAMP : SWAMP;
                        } else if (cool || cold) {
                            biome = veryMoist ? OLD_GROWTH_SPRUCE_TAIGA : TAIGA;
                        } else {
                            biome = veryMoist ? (stablePrecip ? DARK_FOREST : PALE_GARDEN) : FOREST;
                        }
                    }
                }

                // Bare slope override for lowland/non-mountain cliffs.
                // Cold elevated cliffs blend stony/frozen peaks; jagged peaks are only mixed in
                // when the surrounding high-cold dry zone would classify as an ice_spikes desert.
                if (slopeBare && !isOcean && !mountains) {
                    if (coldElevated) {
                        biome = chooseColdElevatedPeak(altM, temp, slope, snowNoise[idx], tempNoise[idx], coldElevatedIceSpikesDesert);
                    } else {
                        biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                    }
                }

                biome = applyMicroBiomeTransitions(biome, temp, treeMoisture, effTreeMoisture,
                        hasSnow, isOcean, slopeBare, upland, highland, moist, microBiome, vegetationPatch);
                biome = normalizeColdOpenBiome(biome, temp, hasSnow, isOcean);

                out[idx] = biome;
            }
        }
        return out;
    }

    private static short chooseSparseForest(float effTreeMoisture, float patchNoise,
                                            boolean upland, boolean moist, short openBiome) {
        float treeShare = clamp01(0.18f + 0.72f * clamp01((effTreeMoisture - 0.18f) / 0.70f));
        if (patchNoise < treeShare) {
            return moist && upland ? BIRCH_FOREST : FOREST;
        }
        return moist && upland ? MEADOW : openBiome;
    }

    private static short chooseSparseTaiga(float effTreeMoisture, float patchNoise,
                                           boolean upland, boolean moist, boolean slopeMedium) {
        float treeShare = clamp01(0.16f + 0.70f * clamp01((effTreeMoisture - 0.18f) / 0.72f));
        if (patchNoise < treeShare) {
            return moist ? OLD_GROWTH_PINE_TAIGA : TAIGA;
        }
        if (slopeMedium) return WINDSWEPT_FOREST;
        return upland && moist ? MEADOW : PLAINS;
    }

    private static short chooseSparseSnowyTaiga(float effTreeMoisture, float patchNoise,
                                                boolean highland, boolean slopeMedium) {
        float treeShare = clamp01(0.14f + 0.68f * clamp01((effTreeMoisture - 0.16f) / 0.70f));
        if (patchNoise < treeShare) {
            return SNOWY_TAIGA;
        }
        if (highland || slopeMedium) return SNOWY_SLOPES;
        return SNOWY_PLAINS;
    }

    private static short applyMicroBiomeTransitions(short biome, float temp, float treeMoisture,
                                                    float effTreeMoisture, boolean hasSnow,
                                                    boolean isOcean, boolean slopeBare,
                                                    boolean upland, boolean highland,
                                                    boolean moist, float microNoise,
                                                    float patchNoise) {
        if (isOcean || slopeBare) return biome;

        float forestEdge = Math.max(edgeFactor(effTreeMoisture, 0.50f, 0.17f),
                edgeFactor(treeMoisture, 0.55f, 0.14f));
        float coldEdge = Math.max(edgeFactor(temp, -5f, 3.6f), edgeFactor(temp, 5f, 3.6f));
        float warmEdge = Math.max(edgeFactor(temp, 12f, 3.8f), edgeFactor(temp, 20f, 3.8f));
        float aridEdge = Math.max(edgeFactor(treeMoisture, 0.12f, 0.10f),
                edgeFactor(treeMoisture, 0.35f, 0.18f));
        float granular = clamp01(0.55f * patchNoise + 0.45f * microNoise);

        // Dry/green borders get small plains/savanna/desert/badlands flecks. Do not let this path
        // create cold/snow biomes; that was the source of snow islands beside deserts and badlands.
        if (aridEdge > 0f && temp > 16f) {
            float aridPatchShare = 0.22f + 0.34f * aridEdge;
            switch (biome) {
                case PLAINS:
                case SUNFLOWER_PLAINS:
                    if (treeMoisture < 0.20f && temp > 25f && granular < aridPatchShare) return DESERT;
                    if (treeMoisture < 0.42f && granular < aridPatchShare) return SAVANNA;
                    break;
                case SAVANNA:
                case SAVANNA_PLATEAU:
                    if (treeMoisture < 0.18f && temp > 25f && granular < 0.14f + 0.22f * aridEdge) return DESERT;
                    if (granular > 0.82f - 0.24f * aridEdge) return PLAINS;
                    break;
                case DESERT:
                    if (upland && treeMoisture > 0.07f && granular > 0.78f - 0.24f * aridEdge) return BADLANDS;
                    if (treeMoisture > 0.18f && granular > 0.84f - 0.24f * aridEdge) return SAVANNA;
                    break;
                case BADLANDS:
                case ERODED_BADLANDS:
                    if (granular < 0.16f + 0.20f * aridEdge) return DESERT;
                    if (treeMoisture > 0.20f && granular > 0.82f - 0.22f * aridEdge) return SAVANNA;
                    break;
                default:
                    break;
            }
        }

        if (forestEdge > 0f && granular < 0.30f + 0.36f * forestEdge) {
            switch (biome) {
                case PLAINS:
                case SUNFLOWER_PLAINS:
                    if (hasSnow) return SNOWY_PLAINS;
                    if (temp < 8f) return upland && moist ? MEADOW : PLAINS;
                    if (temp < 20f) return moist && upland ? MEADOW : FOREST;
                    return SAVANNA;
                case MEADOW:
                    if (temp < 8f) return (hasSnow || highland) ? SNOWY_PLAINS : MEADOW;
                    return FOREST;
                case GROVE:
                    return (hasSnow || highland) ? SNOWY_PLAINS : MEADOW;
                default:
                    break;
            }
        }

        if (forestEdge > 0f && granular > 0.72f - 0.34f * forestEdge) {
            switch (biome) {
                case FOREST:
                case BIRCH_FOREST:
                case FLOWER_FOREST:
                    return moist && upland ? MEADOW : PLAINS;
                case TAIGA:
                case OLD_GROWTH_PINE_TAIGA:
                    return highland && moist ? MEADOW : PLAINS;
                case SNOWY_TAIGA:
                    return highland ? SNOWY_SLOPES : SNOWY_PLAINS;
                default:
                    break;
            }
        }

        if (coldEdge > 0f && microNoise < 0.22f + 0.24f * coldEdge) {
            switch (biome) {
                case TAIGA:
                    return hasSnow ? SNOWY_TAIGA : (temp < 5f ? SNOWY_PLAINS : (highland ? SNOWY_PLAINS : PLAINS));
                case GROVE:
                    return (hasSnow || highland) ? SNOWY_PLAINS : MEADOW;
                case PLAINS:
                    return (hasSnow || temp < 5f) ? SNOWY_PLAINS : PLAINS;
                case SNOWY_PLAINS:
                    return (hasSnow || temp < 5f) ? SNOWY_PLAINS : PLAINS;
                case SNOWY_TAIGA:
                    return hasSnow ? SNOWY_TAIGA : TAIGA;
                default:
                    break;
            }
        }

        if (warmEdge > 0f && microNoise > 0.78f - 0.22f * warmEdge) {
            switch (biome) {
                case FOREST: return BIRCH_FOREST;
                case BIRCH_FOREST: return PLAINS;
                case PLAINS: return temp > 18f ? SAVANNA : PLAINS;
                case SAVANNA: return PLAINS;
                default: break;
            }
        }

        return biome;
    }


    private static short normalizeColdOpenBiome(short biome, float temp, boolean hasSnow, boolean isOcean) {
        if (isOcean || temp >= 5f) return biome;
        return switch (biome) {
            case PLAINS, SUNFLOWER_PLAINS, MEADOW -> SNOWY_PLAINS;
            default -> biome;
        };
    }

    private static float edgeFactor(float value, float threshold, float halfWidth) {
        return clamp01(1f - Math.abs(value - threshold) / halfWidth);
    }

    private static short chooseColdElevatedPeak(float altM, float temp, float slope,
                                                 float snowNoiseValue, float rockNoiseValue,
                                                 boolean mixJaggedIntoRock) {
        // Frozen peaks become more common with altitude, without moving the snowy_slope branch.
        float altitudeFactor = clamp01((altM - 1800f) / 2200f);
        float coldFactor = clamp01((4f - temp) / 12f);
        float frozenShare = clamp01(0.08f + 0.55f * altitudeFactor + 0.20f * coldFactor);
        frozenShare = Math.min(0.82f, frozenShare);

        // SNOW_NOISE is already sampled for this pixel; reuse it so the blend is spatial, not random per tick.
        float pattern = clamp01(0.5f + snowNoiseValue / 10f);
        if (pattern < frozenShare) {
            return FROZEN_PEAKS;
        }

        if (mixJaggedIntoRock) {
            float steepFactor = clamp01((slope - 0.78f) / 0.70f);
            float jaggedShare = clamp01(0.08f + 0.06f * altitudeFactor + 0.08f * steepFactor);
            float rockPattern = clamp01(0.42f - rockNoiseValue / 1.4f - 0.10f * altitudeFactor - 0.05f * steepFactor);
            if (rockPattern < jaggedShare) {
                return JAGGED_PEAKS;
            }
        }

        return STONY_PEAKS;
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private record TerrainDerivatives(float[] slopeRatio, float[] northness) {}

    private static TerrainDerivatives computeTerrainDerivatives(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output.
        // northness is +1 on north-facing slopes, -1 on south-facing slopes, 0 on flat/east-west slopes.
        float[] slope = new float[H * W];
        float[] northness = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                float gradient = (float) Math.sqrt(dx * dx + dy * dy);
                int idx = r * W + c;
                slope[idx] = gradient / pixelSizeM;
                northness[idx] = gradient > 1e-5f ? dy / gradient : 0f;
            }
        }
        return new TerrainDerivatives(slope, northness);
    }
}
