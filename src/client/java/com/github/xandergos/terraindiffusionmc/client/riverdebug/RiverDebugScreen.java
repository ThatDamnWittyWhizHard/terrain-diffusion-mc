package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Simple in-game menu for river/debug overlays. */
public final class RiverDebugScreen extends Screen {
    private static final int BUTTON_WIDTH = 190;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 24;

    private final Screen parent;
    private ButtonWidget heightmapButton;
    private ButtonWidget cellSizeButton;
    private ButtonWidget costLayerButton;
    private ButtonWidget flowLayerButton;
    private ButtonWidget accumulationLayerButton;
    private ButtonWidget riverLayerButton;
    private ButtonWidget riverThresholdButton;
    private ButtonWidget radiusMinusButton;
    private ButtonWidget radiusPlusButton;
    private ButtonWidget wireframeButton;
    private TextWidget statusWidget;

    public RiverDebugScreen(Screen parent) {
        super(Text.translatable("terrain-diffusion-mc.river_debug.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 32;

        addCenteredText(this.title.copy().formatted(Formatting.BOLD), centerX, 14, 0xFFFFFF);

        heightmapButton = this.addDrawableChild(ButtonWidget.builder(heightmapText(), button -> {
            RiverDebugOverlayState.toggleLowResHeightmap();
            if (RiverDebugOverlayState.isLowResHeightmapEnabled() && this.client != null) {
                ClientLowResHeightmapCache.forceRefresh(this.client);
            }
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        cellSizeButton = this.addDrawableChild(ButtonWidget.builder(cellSizeText(), button -> {
            RiverDebugOverlayState.cycleCellSize();
            if (this.client != null) ClientLowResHeightmapCache.forceRefresh(this.client);
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        costLayerButton = this.addDrawableChild(ButtonWidget.builder(costLayerText(), button -> {
            RiverDebugOverlayState.cycleCostLayer();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        flowLayerButton = this.addDrawableChild(ButtonWidget.builder(flowLayerText(), button -> {
            RiverDebugOverlayState.cycleFlowLayer();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        accumulationLayerButton = this.addDrawableChild(ButtonWidget.builder(accumulationLayerText(), button -> {
            RiverDebugOverlayState.cycleAccumulationLayer();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        riverLayerButton = this.addDrawableChild(ButtonWidget.builder(riverLayerText(), button -> {
            RiverDebugOverlayState.cycleRiverLayer();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        riverThresholdButton = this.addDrawableChild(ButtonWidget.builder(riverThresholdText(), button -> {
            RiverDebugOverlayState.cycleRiverThreshold();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        radiusMinusButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Radius -"), button -> {
            RiverDebugOverlayState.addRadius(-1);
            if (this.client != null) ClientLowResHeightmapCache.forceRefresh(this.client);
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH / 2 - 2, BUTTON_HEIGHT).build());

        radiusPlusButton = this.addDrawableChild(ButtonWidget.builder(radiusText(), button -> {
            RiverDebugOverlayState.addRadius(1);
            if (this.client != null) ClientLowResHeightmapCache.forceRefresh(this.client);
            refreshLabels();
        }).dimensions(centerX + 2, y, BUTTON_WIDTH / 2 - 2, BUTTON_HEIGHT).build());
        y += ROW;

        wireframeButton = this.addDrawableChild(ButtonWidget.builder(wireframeText(), button -> {
            RiverDebugOverlayState.toggleWireframe();
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("terrain-diffusion-mc.river_debug.refresh"), button -> {
            if (this.client != null) ClientLowResHeightmapCache.forceRefresh(this.client);
            refreshLabels();
        }).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW + 4;

        statusWidget = new TextWidget(centerX - 260, y, 520, 20, statusText(), this.textRenderer);
        this.addDrawableChild(statusWidget);
        refreshLabels();
    }

    @Override
    public void tick() {
        refreshLabels();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private void refreshLabels() {
        if (heightmapButton != null) heightmapButton.setMessage(heightmapText());
        if (cellSizeButton != null) cellSizeButton.setMessage(cellSizeText());
        if (costLayerButton != null) costLayerButton.setMessage(costLayerText());
        if (flowLayerButton != null) flowLayerButton.setMessage(flowLayerText());
        if (accumulationLayerButton != null) accumulationLayerButton.setMessage(accumulationLayerText());
        if (riverLayerButton != null) riverLayerButton.setMessage(riverLayerText());
        if (riverThresholdButton != null) riverThresholdButton.setMessage(riverThresholdText());
        if (radiusMinusButton != null) radiusMinusButton.setMessage(Text.literal("Radius -"));
        if (radiusPlusButton != null) radiusPlusButton.setMessage(radiusText());
        if (wireframeButton != null) wireframeButton.setMessage(wireframeText());
        if (statusWidget != null) statusWidget.setMessage(statusText());
    }

    private Text heightmapText() {
        return Text.literal("Low-res heightmap: " + (RiverDebugOverlayState.isLowResHeightmapEnabled() ? "ON" : "OFF"));
    }

    private Text cellSizeText() {
        return Text.literal("Cell size: " + RiverDebugOverlayState.cellSizeBlocks() + " blocks");
    }

    private Text costLayerText() {
        return Text.literal("Cost map: " + RiverDebugOverlayState.costLayer().label());
    }

    private Text flowLayerText() {
        return Text.literal("Flow direction: " + RiverDebugOverlayState.flowLayer().label());
    }

    private Text accumulationLayerText() {
        return Text.literal("Accumulation: " + RiverDebugOverlayState.accumulationLayer().label());
    }

    private Text riverLayerText() {
        return Text.literal("Rivers: " + RiverDebugOverlayState.riverLayer().label());
    }

    private Text riverThresholdText() {
        return Text.literal("River threshold: " + RiverDebugOverlayState.riverMinAccumulationCells() + " cells");
    }

    private Text radiusText() {
        int diameter = RiverDebugOverlayState.radiusCells() * 2 + 1;
        return Text.literal("Radius +  (" + RiverDebugOverlayState.radiusCells() + ", " + diameter + "x" + diameter + ")");
    }

    private Text wireframeText() {
        return Text.literal("Cell borders: " + (RiverDebugOverlayState.isWireframeEnabled() ? "ON" : "OFF"));
    }

    private Text statusText() {
        String costStatus = RiverDebugOverlayState.costLayer() == RiverDebugOverlayState.CostLayer.NONE
                ? "cost=" + (!RiverDebugOverlayState.needsTerrainCostMap() ? "off" : ClientTerrainCostMapCache.status())
                : "cost=" + ClientTerrainCostMapCache.status();
        String flowStatus = RiverDebugOverlayState.flowLayer() == RiverDebugOverlayState.FlowLayer.NONE
                ? "flow=" + (!RiverDebugOverlayState.needsFlowDirectionMap() ? "off" : ClientFlowDirectionMapCache.status())
                : "flow=" + ClientFlowDirectionMapCache.status();
        String accumulationStatus = RiverDebugOverlayState.accumulationLayer() == RiverDebugOverlayState.AccumulationLayer.NONE
                ? "acc=" + (!RiverDebugOverlayState.needsFlowAccumulationMap() ? "off" : ClientFlowAccumulationMapCache.status())
                : "acc=" + ClientFlowAccumulationMapCache.status();
        String riverStatus = RiverDebugOverlayState.riverLayer() == RiverDebugOverlayState.RiverLayer.NONE
                ? "rivers=off"
                : "rivers=" + ClientRiverCellMapCache.status();
        return Text.literal("Heightmap: " + ClientLowResHeightmapCache.status() + " | " + costStatus + " | " + flowStatus + " | " + accumulationStatus + " | " + riverStatus).formatted(Formatting.GRAY);
    }

    private void addCenteredText(Text text, int centerX, int y, int color) {
        int textWidth = this.textRenderer.getWidth(text);
        MutableText coloredText = text.copy().styled(style -> style.withColor(color));
        this.addDrawableChild(new TextWidget(centerX - textWidth / 2, y, textWidth, 9, coloredText, this.textRenderer));
    }
}
