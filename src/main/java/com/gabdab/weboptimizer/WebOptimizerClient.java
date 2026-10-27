package com.gabdab.weboptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import org.lwjgl.glfw.GLFW;

public class WebOptimizerClient implements ClientModInitializer {
    private boolean menuKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        WebConfig.load();

        ClientTickEvents.START_CLIENT_TICK.register(WebLogic::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WebLogic.afterTick(client);
            handleMenuKey(client);
        });

        // свої удари запам'ятовуємо, щоб не ставити павутинку просто посеред атаки
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() && player == MinecraftClient.getInstance().player) {
                WebLogic.markAttack();
            }
            return ActionResult.PASS;
        });
    }

    /** Right Shift відкриває HUD. */
    private void handleMenuKey(MinecraftClient mc) {
        long window = mc.getWindow().getHandle();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (down && !menuKeyWasDown && mc.currentScreen == null && mc.player != null) {
            mc.setScreen(new WebScreen());
        }
        menuKeyWasDown = down;
    }
}
