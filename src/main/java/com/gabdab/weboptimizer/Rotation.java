package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Спільний "тихий" поворот: сервер бачить плавний поворот, а твій екран не смикається. */
final class Rotation {
    static boolean active;
    static float yaw;
    static float pitch;
    static int lastSmooth = 100;

    private Rotation() {}

    static void reset() {
        active = false;
    }

    static Vec3d eye(ClientPlayerEntity p) {
        return new Vec3d(p.getX(), p.getEyeY(), p.getZ());
    }

    static Vec3d pos(ClientPlayerEntity p) {
        return new Vec3d(p.getX(), p.getY(), p.getZ());
    }

    static void begin(ClientPlayerEntity player) {
        if (!active) {
            yaw = player.getYaw();
            pitch = player.getPitch();
            active = true;
        }
    }

    static float[] anglesTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float y = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float p = (float) (-Math.toDegrees(Math.atan2(dy, h)));
        return new float[]{y, p};
    }

    /** smoothness 0 = миттєво (180°/тік), 1000 = дуже плавно (~3°/тік). */
    static double maxStepDeg(int smoothness) {
        int s = MathHelper.clamp(smoothness, 0, 1000);
        return 180.0 * Math.pow(1.0 / 60.0, s / 1000.0);
    }

    static void stepToward(float targetYaw, float targetPitch, int smoothness) {
        lastSmooth = smoothness;
        float dy = MathHelper.wrapDegrees(targetYaw - yaw);
        float dp = targetPitch - pitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        double step = maxStepDeg(smoothness);
        if (dist <= step) {
            yaw = targetYaw;
            pitch = targetPitch;
        } else {
            double k = step / dist;
            yaw += (float) (dy * k);
            pitch += (float) (dp * k);
        }
    }

    static double remaining(float targetYaw, float targetPitch) {
        float dy = MathHelper.wrapDegrees(targetYaw - yaw);
        float dp = targetPitch - pitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    static void sendLook(MinecraftClient mc, ClientPlayerEntity player, float y, float p) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) {
            return;
        }
        handler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(y, p, player.isOnGround(), player.horizontalCollision));
    }

    /** Плавно повертає серверний погляд назад до справжнього. */
    static void release(MinecraftClient mc, ClientPlayerEntity player) {
        if (!active) {
            return;
        }
        float realYaw = player.getYaw();
        float realPitch = player.getPitch();
        stepToward(realYaw, realPitch, lastSmooth);
        if (remaining(realYaw, realPitch) <= 0.5) {
            sendLook(mc, player, realYaw, realPitch);
            active = false;
        } else {
            sendLook(mc, player, yaw, pitch);
        }
    }
}
