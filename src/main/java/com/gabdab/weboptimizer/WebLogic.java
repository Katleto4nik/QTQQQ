package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Автоматична павутинка під ворога (з передбаченням руху) + керування Auto Drain. */
public final class WebLogic {
    private static final int PLACE_COOLDOWN = 2;
    private static final int ENEMY_COOLDOWN = 8;
    private static final long ATTACK_DELAY_MS = 200;
    private static final int PATH_STEPS = 28;
    /** Скільки тіків має пройти на землі, перш ніж можна ставити. */
    private static final int MIN_GROUND_TICKS = 2;

    private static final double[] WEIGHTS = {0.6, 1.0, 0.8, 0.5, 0.3};
    private static final double[] OFFSETS = {-0.3, 0.0, 0.3};
    private static final Map<UUID, Long> PLACED_AT = new HashMap<>();

    private static long tickCount = 0;
    private static volatile long lastAttackMs = 0;

    private static int cooldown = 0;
    private static boolean engaged = false;
    private static int holdTicks = 0;
    private static boolean resumeSprint = false;
    private static boolean keysBlocked = false;

    // стан гравця для "легітних" умов
    private static int groundTicks = 0;
    private static boolean airHit = false;

    // павутинка в руці (ми її взяли самі)
    private static boolean webHolding = false;
    private static int webHeldTicks = 0;

    record Placement(BlockPos support, Vec3d hit, UUID enemy) {}

    private WebLogic() {}

    public static void markAttack() {
        lastAttackMs = System.currentTimeMillis();
    }

    private static void reset() {
        Predictor.clear();
        PLACED_AT.clear();
        Rotation.reset();
        SlotSwap.reset();
        DrainLogic.reset();
        engaged = false;
        holdTicks = 0;
        resumeSprint = false;
        keysBlocked = false;
        groundTicks = 0;
        airHit = false;
        webHolding = false;
        webHeldTicks = 0;
    }

    /** Кінець тіка: відновлюємо реальний стан клавіш після blockMovement. */
    public static void afterTick(MinecraftClient mc) {
        if (keysBlocked) {
            KeyBinding.updatePressedStates();
            keysBlocked = false;
        }
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null || mc.getNetworkHandler() == null) {
            reset();
            return;
        }
        tickCount++;
        Predictor.update(world);
        if (cooldown > 0) {
            cooldown--;
        }
        trackPlayerState(player);

        if (holdTicks > 0) {
            blockMovement(mc, player);
            holdTicks--;
            if (holdTicks == 0) {
                resumeSprint = true;
            }
        } else if (resumeSprint) {
            resumeSprint = false;
            if (mc.options.forwardKey.isPressed()) {
                player.setSprinting(true);
            }
        }

        WebConfig cfg = WebConfig.INSTANCE;
        PlayerInventory inv = player.getInventory();

        boolean usable = mc.currentScreen == null
                && mc.interactionManager != null
                && player.isAlive()
                && !player.isSpectator()
                && !shieldHeld(mc, player);

        // 1) Auto Drain має пріоритет (вода/лава від ворога)
        if (usable && cfg.autoDrain) {
            if (DrainLogic.tick(mc, player, world, cfg)) {
                return;
            }
        } else {
            DrainLogic.idle(inv, cfg);
        }

        // 2) Павутинка
        boolean base = cfg.enabled
                && usable
                && System.currentTimeMillis() - lastAttackMs >= ATTACK_DELAY_MS
                && canPlaceNow(mc, player);

        Placement target = null;
        int webSlot = -1;
        if (base) {
            webSlot = SlotSwap.find(inv, Items.COBWEB);
            if (webSlot >= 0) {
                target = findTarget(mc, player, world, cfg);
            }
        }

        if (target == null) {
            if (engaged) {
                engaged = false;
                resumeSprint = true;
            }
            if (webHolding) {
                SlotSwap.abort(inv);
                webHolding = false;
            }
            webHeldTicks = 0;
            Rotation.release(mc, player);
            return;
        }

        if (!engaged && player.isSprinting()) {
            engaged = true;
        }
        if (engaged) {
            blockMovement(mc, player);
        }

        // Фаза 1: дуже швидко беремо павутинку в руку
        if (inv.getSelectedSlot() != webSlot) {
            SlotSwap.select(inv, webSlot);
            webHolding = true;
            webHeldTicks = 0;
        } else {
            webHeldTicks++;
        }

        // Плавний поворот на точку встановлення
        Vec3d eye = Rotation.eye(player);
        Rotation.begin(player);
        float[] aim = Rotation.anglesTo(eye, target.hit());
        Rotation.stepToward(aim[0], aim[1], cfg.smoothness);
        Rotation.sendLook(mc, player, Rotation.yaw, Rotation.pitch);

        // Фаза 2: павутинка вже в руці (мінімум 1 тік) і приціл готовий - ставимо
        if (Rotation.remaining(aim[0], aim[1]) <= 0.5 && cooldown == 0 && webHeldTicks >= 1) {
            place(mc, player, target, cfg);
            webHolding = false;
            cooldown = PLACE_COOLDOWN;
            PLACED_AT.put(target.enemy(), tickCount);
            if (engaged) {
                engaged = false;
                holdTicks = 1;
            }
        }
    }

    // ----- легітні умови -----

    private static void trackPlayerState(ClientPlayerEntity player) {
        if (player.isOnGround()) {
            groundTicks++;
        } else {
            groundTicks = 0;
        }
        // вдарили, поки ми в повітрі - блокуємо, поки не приземлимось і не пройде hurtTime
        if (player.hurtTime > 0 && !player.isOnGround()) {
            airHit = true;
        } else if (player.isOnGround() && player.hurtTime == 0) {
            airHit = false;
        }
    }

    /** Павутинку можна ставити ТІЛЬКИ коли ти стоїш на землі. */
    private static boolean canPlaceNow(MinecraftClient mc, ClientPlayerEntity player) {
        if (!player.isOnGround() || groundTicks < MIN_GROUND_TICKS) {
            return false;                       // стрибок / падіння / політ
        }
        if (airHit) {
            return false;                       // нас відкинув гравець у повітрі
        }
        if (player.isTouchingWater() || player.isSubmergedInWater() || player.isSwimming() || player.isInLava()) {
            return false;                       // плавання
        }
        if (player.isGliding() || player.getAbilities().flying || player.hasVehicle() || player.isClimbing()) {
            return false;                       // елітра / креатив-політ / транспорт / драбина
        }
        return !mc.options.jumpKey.isPressed(); // натиснутий стрибок
    }

    private static boolean shieldHeld(MinecraftClient mc, ClientPlayerEntity player) {
        if (player.isBlocking()) {
            return true;
        }
        if (!mc.options.useKey.isPressed()) {
            return false;
        }
        return player.getMainHandStack().isOf(Items.SHIELD) || player.getOffHandStack().isOf(Items.SHIELD);
    }

    private static void blockMovement(MinecraftClient mc, ClientPlayerEntity player) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.sprintKey.setPressed(false);
        player.setSprinting(false);
        keysBlocked = true;
    }

    // ----- пошук цілі -----

    private static Placement findTarget(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, WebConfig cfg) {
        Vec3d eye = Rotation.eye(player);
        double ping = pingTicks(mc, player);
        double maxStep = Rotation.maxStepDeg(cfg.smoothness);
        double reach = cfg.placeReach;

        Placement best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity enemy : world.getPlayers()) {
            if (enemy == player || !enemy.isAlive() || enemy.isSpectator()) {
                continue;
            }
            if (player.isTeammate(enemy)) {
                continue;
            }
            if (player.squaredDistanceTo(enemy) > 64.0) {
                continue;
            }
            if (enemy.isTouchingWater() || enemy.isSubmergedInWater() || enemy.isInLava()) {
                continue;
            }
            if (webNear(world, enemy)) {
                continue;
            }
            Long last = PLACED_AT.get(enemy.getUuid());
            if (last != null && tickCount - last < ENEMY_COOLDOWN) {
                continue;
            }

            double aimTicks = aimTicks(player, eye, enemy, maxStep);
            double lead = MathHelper.clamp(ping + 2.0 + aimTicks, 1.0, 14.0);
            Predictor.Prediction pred = Predictor.predict(world, enemy, PATH_STEPS);

            for (Placement pl : candidates(world, enemy, pred, lead)) {
                double d = eye.distanceTo(pl.hit());
                if (d > reach) {
                    continue;                   // за межами налаштованої дальності
                }
                if (d < bestDist) {
                    bestDist = d;
                    best = pl;
                }
                break;
            }
        }
        return best;
    }

    private static double pingTicks(MinecraftClient mc, ClientPlayerEntity player) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) {
            return 0.0;
        }
        PlayerListEntry entry = handler.getPlayerListEntry(player.getUuid());
        return entry == null ? 0.0 : entry.getLatency() / 50.0;
    }

    private static double aimTicks(ClientPlayerEntity player, Vec3d eye, PlayerEntity enemy, double maxStep) {
        float[] a = Rotation.anglesTo(eye, new Vec3d(enemy.getX(), enemy.getY(), enemy.getZ()));
        float curYaw = Rotation.active ? Rotation.yaw : player.getYaw();
        float curPitch = Rotation.active ? Rotation.pitch : player.getPitch();
        double dy = MathHelper.wrapDegrees(a[0] - curYaw);
        double dp = a[1] - curPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        return Math.max(0.0, Math.ceil(dist / maxStep) - 1.0);
    }

    private static boolean webNear(ClientWorld world, PlayerEntity enemy) {
        Box box = enemy.getBoundingBox().expand(0.9, 0.3, 0.9);
        int x0 = MathHelper.floor(box.minX);
        int x1 = MathHelper.floor(box.maxX);
        int y0 = MathHelper.floor(box.minY);
        int y1 = MathHelper.floor(box.maxY);
        int z0 = MathHelper.floor(box.minZ);
        int z1 = MathHelper.floor(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Placement> candidates(ClientWorld world, PlayerEntity enemy, Predictor.Prediction pred, double lead) {
        Vec3d[] path = pred.path();
        List<Placement> out = new ArrayList<>();

        if (!enemy.isOnGround()) {
            if (pred.landStep() < 0) {
                return out;
            }
            Vec3d p = path[pred.landStep()];
            Placement pl = placementAt(world, enemy, MathHelper.floor(p.x), MathHelper.floor(p.z), p.y);
            if (pl != null) {
                out.add(pl);
            }
            return out;
        }

        int idx = MathHelper.clamp((int) Math.round(lead), 1, path.length - 4);
        double refY = path[idx].y;

        Map<Long, double[]> cells = new HashMap<>();
        for (int k = -1; k <= 3; k++) {
            int i = idx + k;
            if (i < 0 || i >= path.length) {
                continue;
            }
            Vec3d p = path[i];
            for (double ox : OFFSETS) {
                for (double oz : OFFSETS) {
                    int cx = MathHelper.floor(p.x + ox);
                    int cz = MathHelper.floor(p.z + oz);
                    long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    cells.putIfAbsent(key, new double[]{cx, cz, 0.0});
                }
            }
        }

        List<double[]> list = new ArrayList<>(cells.values());
        for (double[] cell : list) {
            double score = 0.0;
            for (int k = -1; k <= 3; k++) {
                int i = idx + k;
                if (i < 0 || i >= path.length) {
                    continue;
                }
                score += WEIGHTS[k + 1] * overlap((int) cell[0], path[i].x) * overlap((int) cell[1], path[i].z);
            }
            cell[2] = score;
        }
        list.sort((a, b) -> Double.compare(b[2], a[2]));

        for (double[] cell : list) {
            if (cell[2] <= 0.0001) {
                break;
            }
            Placement pl = placementAt(world, enemy, (int) cell[0], (int) cell[1], refY);
            if (pl != null) {
                out.add(pl);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    /** Перетин клітинки з хітбоксом гравця (півширини 0.3) по одній осі. */
    private static double overlap(int cell, double center) {
        return Math.max(0.0, Math.min(cell + 1.0, center + 0.3) - Math.max((double) cell, center - 0.3));
    }

    private static Placement placementAt(ClientWorld world, PlayerEntity enemy, int x, int z, double refY) {
        BlockPos webPos = new BlockPos(x, MathHelper.floor(refY + 0.001), z);
        BlockState at = world.getBlockState(webPos);
        if (at.isOf(Blocks.COBWEB)) {
            return null;
        }
        if (!at.getFluidState().isEmpty()) {
            return null;
        }
        if (!at.isAir() && !at.isReplaceable()) {
            return null;
        }

        for (int dy = 1; dy <= 5; dy++) {
            BlockPos support = webPos.down(dy);
            BlockState ss = world.getBlockState(support);
            if (!ss.getFluidState().isEmpty()) {
                return null;
            }
            if (ss.isSideSolidFullSquare(world, support, Direction.UP)) {
                BlockPos spot = support.up();
                BlockState sp = world.getBlockState(spot);
                if (sp.isOf(Blocks.COBWEB)) {
                    return null;
                }
                if (!sp.getFluidState().isEmpty()) {
                    return null;
                }
                if (!sp.isAir() && !sp.isReplaceable()) {
                    return null;
                }
                if (webAround(world, spot)) {
                    return null;
                }
                Vec3d hit = new Vec3d(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
                return new Placement(support, hit, enemy.getUuid());
            }
            if (ss.isOf(Blocks.COBWEB)) {
                return null;
            }
            if (!ss.isAir() && !ss.isReplaceable()) {
                return null;
            }
        }
        return null;
    }

    private static boolean webAround(ClientWorld world, BlockPos center) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ----- встановлення -----

    private static void place(MinecraftClient mc, ClientPlayerEntity player, Placement pl, WebConfig cfg) {
        BlockHitResult hit = new BlockHitResult(pl.hit(), Direction.UP, pl.support(), false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        SlotSwap.finish(player.getInventory(), cfg.returnSword);
    }
}
