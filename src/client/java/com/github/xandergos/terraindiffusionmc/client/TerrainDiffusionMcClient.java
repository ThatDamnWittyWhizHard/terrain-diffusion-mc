package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionMc;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.ClientLowResHeightmapCache;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.LowResHeightmapOverlayRenderer;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.ClientTerrainCostMapCache;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.TerrainCostMapOverlayRenderer;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.ClientFlowDirectionMapCache;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.FlowDirectionOverlayRenderer;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.ClientFlowAccumulationMapCache;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.FlowAccumulationOverlayRenderer;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.ClientRiverCellMapCache;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.RiverCellOverlayRenderer;
import com.github.xandergos.terraindiffusionmc.client.riverdebug.RiverDebugScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class TerrainDiffusionMcClient implements ClientModInitializer {
    private static final KeyBinding.Category DEBUG_CATEGORY = KeyBinding.Category.create(
            Identifier.of(TerrainDiffusionMc.MOD_ID, "debug")
    );

    private static KeyBinding openRiverDebugMenuKey;

    @Override
    public void onInitializeClient() {
        openRiverDebugMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.terrain-diffusion-mc.open_river_debug_menu",
                GLFW.GLFW_KEY_F8,
                DEBUG_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(TerrainDiffusionMcClient::onClientTick);
        LowResHeightmapOverlayRenderer.register();
        TerrainCostMapOverlayRenderer.register();
        FlowDirectionOverlayRenderer.register();
        FlowAccumulationOverlayRenderer.register();
        RiverCellOverlayRenderer.register();
    }

    private static void onClientTick(MinecraftClient client) {
        while (openRiverDebugMenuKey.wasPressed()) {
            client.setScreen(new RiverDebugScreen(client.currentScreen));
        }
        ClientLowResHeightmapCache.tick(client);
        ClientTerrainCostMapCache.tick(client);
        ClientFlowDirectionMapCache.tick(client);
        ClientFlowAccumulationMapCache.tick(client);
        ClientRiverCellMapCache.tick(client);
    }
}
