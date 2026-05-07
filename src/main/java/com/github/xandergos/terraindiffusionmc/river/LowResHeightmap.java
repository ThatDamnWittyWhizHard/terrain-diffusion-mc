package com.github.xandergos.terraindiffusionmc.river;

/**
 * Low-resolution terrain sample in world/block coordinates.
 *
 * <p>Cells are aligned to a fixed world grid. A cell (cx, cz) covers :
 * x=[cx*cellSizeBlocks, (cx+1)*cellSizeBlocks),
 * z=[cz*cellSizeBlocks, (cz+1)*cellSizeBlocks).
 *
 * <p>Heights are Minecraft Y values ; not raw model meters. Raw meter values can be
 * added later if the river planner needs physical slope in model space.
 */
public final class LowResHeightmap {
    private final int originCellX;
    private final int originCellZ;
    private final int widthCells;
    private final int heightCells;
    private final int cellSizeBlocks;
    private final short[] heightsY;
    private final short minY;
    private final short maxY;

    public LowResHeightmap(
            int originCellX,
            int originCellZ,
            int widthCells,
            int heightCells,
            int cellSizeBlocks,
            short[] heightsY
    ) {
        if (widthCells <= 0 || heightCells <= 0) {
            throw new IllegalArgumentException("heightmap dimensions must be positive");
        }
        if (cellSizeBlocks <= 0 || (cellSizeBlocks & (cellSizeBlocks - 1)) != 0) {
            throw new IllegalArgumentException("cellSizeBlocks must be a positive power of two");
        }
        if (heightsY.length != widthCells * heightCells) {
            throw new IllegalArgumentException("heightsY length does not match dimensions");
        }

        this.originCellX = originCellX;
        this.originCellZ = originCellZ;
        this.widthCells = widthCells;
        this.heightCells = heightCells;
        this.cellSizeBlocks = cellSizeBlocks;
        this.heightsY = heightsY;

        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        for (short h : heightsY) {
            if (h < min) min = h;
            if (h > max) max = h;
        }
        this.minY = min;
        this.maxY = max;
    }

    public int originCellX() {
        return originCellX;
    }

    public int originCellZ() {
        return originCellZ;
    }

    public int widthCells() {
        return widthCells;
    }

    public int heightCells() {
        return heightCells;
    }

    public int cellSizeBlocks() {
        return cellSizeBlocks;
    }

    public short minY() {
        return minY;
    }

    public short maxY() {
        return maxY;
    }

    public short heightAtLocal(int localCellX, int localCellZ) {
        return heightsY[index(localCellX, localCellZ)];
    }

    public short heightAtWorldCell(int cellX, int cellZ) {
        return heightAtLocal(cellX - originCellX, cellZ - originCellZ);
    }

    private int index(int localCellX, int localCellZ) {
        if (localCellX < 0 || localCellX >= widthCells || localCellZ < 0 || localCellZ >= heightCells) {
            throw new IndexOutOfBoundsException("cell outside heightmap: " + localCellX + ", " + localCellZ);
        }
        return localCellZ * widthCells + localCellX;
    }
}
