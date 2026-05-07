package com.github.xandergos.terraindiffusionmc.river;

/** Tunable coefficients for the terrain preference cost map used by river planning. */
public record TerrainCostParameters(
        float baseCost,
        float slopeWeight,
        float ridgeWeight,
        float valleyBonusWeight,
        float biomeWeight,
        float rockSoilWeight,
        float forbiddenWeight,
        float slopeCostStart,
        float slopeCostFull,
        float ridgeHeightStart,
        float ridgeHeightFull,
        float valleyDepthStart,
        float valleyDepthFull
) {
    public static TerrainCostParameters defaults() {
        return new TerrainCostParameters(
                35.0f,  // baseCost : neutral terrain sits around the lower middle of 0..100
                0.55f,  // slopeWeight : steep cells should become unattractive but gravity remains handled later
                0.45f,  // ridgeWeight : avoid convex high points and divides
                0.60f,  // valleyBonusWeight : favor concave/low channels
                1.00f,  // biomeWeight : neutral until biome rules are wired in
                1.00f,  // rockSoilWeight : neutral until geology/soil rules are wired in
                1.00f,  // forbiddenWeight : reserved for hard constraints
                0.025f, // slopeCostStart : dy/dx; below this is effectively flat at low-res scale
                0.220f, // slopeCostFull : dy/dx; above this is very expensive terrain
                1.50f,  // ridgeHeightStart : center cell blocks above neighborhood before ridge cost begins
                16.0f,  // ridgeHeightFull : strong convex ridge/divide
                1.00f,  // valleyDepthStart : center cell blocks below neighborhood before valley bonus begins
                14.0f   // valleyDepthFull : strong concavity/valley
        );
    }
}
