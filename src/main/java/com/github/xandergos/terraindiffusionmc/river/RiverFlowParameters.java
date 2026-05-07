package com.github.xandergos.terraindiffusionmc.river;

/** Tunable coefficients for choosing a downstream neighbor on the low-res river grid. */
public record RiverFlowParameters(
        float terrainCostWeight,
        float uphillPenalty,
        float downhillBonus,
        float slopePenalty,
        float flatPenalty,
        float diagonalStepPenalty,
        float minDownhillDropBlocks,
        float hardUphillLimitBlocks,
        float debugDropFullBlocks
) {
    public static RiverFlowParameters defaults() {
        return new RiverFlowParameters(
                1.00f,  // terrainCostWeight: terrain preference remains secondary to gravity.
                1500.0f, // uphillPenalty: kept for future soft-uphill modes; hard-uphill is disabled by default.
                7.0f,   // downhillBonus: makes lower neighbours strongly preferred once they exist.
                1.5f,   // slopePenalty: discourages absurd cliffs without cancelling the downhill pull.
                5.0f,   // flatPenalty: flat routing is allowed only when no downhill neighbour exists.
                2.5f,   // diagonalStepPenalty: avoids diagonal bias caused by larger neighbour distance.
                1.0f,   // minDownhillDropBlocks: integer-height low-res drop required to be a true descent.
                0.0f,   // hardUphillLimitBlocks: 0 means rivers cannot step upward at this stage.
                32.0f   // debugDropFullBlocks: selected drop that maps to full color intensity.
        );
    }
}
