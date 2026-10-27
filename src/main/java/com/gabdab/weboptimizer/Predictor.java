package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Прогноз руху гравців: історія позицій + проста фізика (гравітація, опір повітря, сходинки). */
final class Predictor {
    private static final int HIST = 6;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private Predictor() {}

    record Prediction(Vec3d[] path, int landStep) {}

    private static final class Track {
        final Vec3d[] h = new Vec3d[HIST];
        int n = 0;

        void push(Vec3d pos) {
            if (n > 0 && pos.squaredDistanceTo(h[0]) > 16.0) {
                n = 0; // телепорт - стара історія не годиться
            }
            for (int i = HIST - 1; i > 0; i--) {
                h[i] = h[i - 1];
            }
            h[0] = pos;
            if (n < HIST) {
                n++;
            }
        }
    }

    static void clear() {
        TRACKS.clear();
    }

    static void update(ClientWorld world) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerEntity p : world.getPlayers()) {
            UUID id = p.getUuid();
            seen.add(id);
            TRACKS.computeIfAbsent(id, k -> new Track()).push(new Vec3d(p.getX(), p.getY(), p.getZ()));
        }
        TRACKS.keySet().retainAll(seen);
    }

    static Prediction predict(ClientWorld world, PlayerEntity player, int steps) {
        Track t = TRACKS.get(player.getUuid());
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());

        double vx = 0, vz = 0, vy = 0, ax = 0, az = 0;
        if (t != null && t.n >= 2) {
            Vec3d d0 = t.h[0].subtract(t.h[1]);
            Vec3d d1 = t.n > 2 ? t.h[1].subtract(t.h[2]) : d0;
            Vec3d d2 = t.n > 3 ? t.h[2].subtract(t.h[3]) : d1;
            vx = d0.x * 0.5 + d1.x * 0.3 + d2.x * 0.2;
            vz = d0.z * 0.5 + d1.z * 0.3 + d2.z * 0.2;
            vy = d0.y;
            ax = MathHelper.clamp((d0.x - d2.x) * 0.5, -0.04, 0.04);
            az = MathHelper.clamp((d0.z - d2.z) * 0.5, -0.04, 0.04);
        }
        if (Math.sqrt(vx * vx + vz * vz) < 0.012) {
            vx = vz = ax = az = 0;
        }

        final double ivx = vx;
        final double ivz = vz;
        double px = pos.x, py = pos.y, pz = pos.z;
        boolean onGround = player.isOnGround();
        int landStep = -1;

        Vec3d[] path = new Vec3d[steps + 1];
        path[0] = pos;

        for (int i = 1; i <= steps; i++) {
            ax *= 0.7;
            az *= 0.7;
            vx += ax;
            vz += az;
            if (vx * ivx + vz * ivz < 0) { // розвернулись проти початкового напрямку - зупиняємо
                vx = vz = ax = az = 0;
            }

            double nx = px + vx;
            double nz = pz + vz;
            if (blocked(world, nx, py, nz)) {
                vx = vz = 0;
                nx = px;
                nz = pz;
            }

            double ny = py;
            if (!onGround) {
                vy = (vy - GRAVITY) * AIR_DRAG;
                ny = py + vy;
                double gt = groundTop(world, nx, nz, py + 0.6);
                if (vy <= 0 && ny <= gt) {
                    ny = gt;
                    onGround = true;
                    vy = 0;
                    if (landStep < 0) {
                        landStep = i;
                    }
                }
            } else {
                double gt = groundTop(world, nx, nz, py + 0.6);
                if (gt > py + 1e-6 && gt <= py + 0.6) {
                    ny = gt; // сходинка вгору
                } else if (gt < py - 0.6) {
                    onGround = false; // зійшов з уступу
                    vy = 0;
                }
            }

            px = nx;
            py = ny;
            pz = nz;
            path[i] = new Vec3d(px, py, pz);
        }
        return new Prediction(path, landStep);
    }

    /** Верхня точка першого твердого блоку під (x, startY), шукаємо до 40 блоків вниз. */
    private static double groundTop(ClientWorld world, double x, double z, double startY) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);
        int by = MathHelper.floor(startY);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = by; y >= by - 40 && y >= world.getBottomY(); y--) {
            pos.set(bx, y, bz);
            BlockState state = world.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(world, pos);
            if (!shape.isEmpty()) {
                return y + shape.getMax(Direction.Axis.Y);
            }
        }
        return -1000.0;
    }

    /** Чи заважає блок рухові (вищий за сходинку). */
    private static boolean blocked(ClientWorld world, double x, double y, double z) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        pos.set(MathHelper.floor(x), MathHelper.floor(y + 0.1), MathHelper.floor(z));
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return false;
        }
        return pos.getY() + shape.getMax(Direction.Axis.Y) > y + 0.6;
    }
}
