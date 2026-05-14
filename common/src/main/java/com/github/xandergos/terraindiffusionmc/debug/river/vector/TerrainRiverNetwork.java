package com.github.xandergos.terraindiffusionmc.debug.river.vector;

import java.util.List;

public record TerrainRiverNetwork(
        int blockStartX,
        int blockStartZ,
        int width,
        int height,
        List<Node> nodes,
        List<Segment> segments,
        List<Lake> lakes,
        float maxAccumulation
) {
    public enum NodeType {
        SOURCE,
        CONFLUENCE,
        OUTLET,
        BOUNDARY,
        INTERNAL
    }

    public record Node(
            int id,
            int localX,
            int localZ,
            double worldX,
            double worldZ,
            int surfaceY,
            NodeType type,
            float accumulation,
            float widthBlocks,
            int directAffluentCount
    ) {
    }

    public record Point(
            double worldX,
            double worldZ,
            int surfaceY,
            float accumulation,
            float widthBlocks,
            float depthBlocks,
            byte direction
    ) {
    }

    public record Segment(
            int id,
            int startNodeId,
            int endNodeId,
            List<Point> points,
            float meanAccumulation,
            float maxAccumulation,
            float meanWidthBlocks,
            float maxWidthBlocks,
            float depthBlocks,
            byte downstreamDirection,
            int upstreamAffluentCount
    ) {
    }

    public record Lake(
            int id,
            List<LakeRun> runs,
            double centerWorldX,
            double centerWorldZ,
            float waterLevelY,
            float meanDepthBlocks,
            float maxDepthBlocks,
            float maxAccumulation,
            int surfaceCellCount,
            int inflowCount,
            int outletWorldX,
            int outletWorldZ
    ) {
    }

    public record LakeRun(
            int startWorldX,
            int endWorldX,
            int worldZ,
            float waterLevelY,
            float meanDepthBlocks,
            float maxAccumulation
    ) {
    }
}
