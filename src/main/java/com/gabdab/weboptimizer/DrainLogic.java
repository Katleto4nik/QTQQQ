package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto Drain (легітна версія).
 *
 * Коли ЧУЖИЙ гравець ставить воду або лаву поруч, мод:
 *  1) "помічає" це не миттєво, а з людською затримкою реакції (більшою, якщо джерело ззаду);
 *  2) бере в руку ПОРОЖНЄ відро, як це робить людина (без підміни пакетів);
 *  3) плавно повертає СПРАВЖНЮ камеру (кроки кратні чутливості миші, з легким тремтінням і гальмуванням);
 *  4) клікає лише тоді, коли справжній приціл вже дивиться на це джерело в межах дальності;
 *  5) забирає рідину (відро НІЧОГО не ставить) і повертає попередній слот.
 */
final class DrainLogic {
    private static final int TTL = 60;                 // скільки тіків пам'ятаємо нове джерело
    private static final int MAX_TRIES = 3;
    private static final double ENEMY_RADIUS_SQ = 64.0; // ворог у межах 8 блоків від нового джерела

    private static Set<Long> known = new HashSet<>();
    private static final Map<Long, Target> TARGETS = new LinkedHashMap<>();

    private static ClientWorld lastWorld;
    private static Vec3d lastPos;
    private static BlockPos prevCenter;
    private static int prevRadius;
    private static boolean initialized;

    private static int tick;
    private static boolean holding;     // відро в руці взяли ми
    private static boolean used;        // хоч раз клікнули
    private static int heldTicks;       // скільки тіків відро вже в руці
    private static int swapWait;        // "час на взяття відра"
    private static int onTargetTicks;   // скільки тіків приціл стоїть на цілі
    private static int clickDelay;      // скільки тіків дивитись перед кліком
    private static int idleTicks;       // скільки тіків цілей нема (перед поверненням слота)
    private static int releaseDelay;    // коли повертати слот

    private static final class Target {
        final long key;
        final BlockPos pos;
        final int expireTick;
        final int readyTick;            // раніше цього тіка "не помітив"
        int retryTick;
        int tries;
        Vec3d aim;                      // випадкова точка всередині блока

        Target(long key, BlockPos pos, int expireTick, int readyTick) {
            this.key = key;
            this.pos = pos;
            this.expireTick = expireTick;
            this.readyTick = readyTick;
        }
    }

    private DrainLogic() {}

    static void reset() {
        known = new HashSet<>();
        TARGETS.clear();
        lastWorld = null;
        lastPos = null;
        prevCenter = null;
        initialized = false;
        holding = false;
        used = false;
        resetCounters();
    }

    private static void resetCounters() {
        heldTicks = 0;
        onTargetTicks = 0;
        idleTicks = 0;
        swapWait = rnd(1, 3);
        clickDelay = rnd(0, 2);
        releaseDelay = rnd(2, 5);
    }

    /** Auto Drain вимкнений / екран відкрито: віддаємо слот і скидаємо стеження. */
    static void idle(PlayerInventory inv, WebConfig cfg) {
        finishHold(inv, cfg);
        TARGETS.clear();
        initialized = false;
    }

    /** @return true якщо Auto Drain зараз зайнятий (тоді павутинка цього тіка не працює). */
    static boolean tick(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, WebConfig cfg) {
        tick++;
        PlayerInventory inv = player.getInventory();

        if (player.isUsingItem()) {          // їмо / тягнемо лук - людина б не стала відволікатись
            finishHold(inv, cfg);
            return false;
        }

        double reach = cfg.placeReach;
        int radius = (int) Math.ceil(reach) + 1;
        BlockPos center = player.getBlockPos();
        Set<Long> now = scan(world, center, radius);

        boolean teleported = lastPos != null && lastPos.squaredDistanceTo(Rotation.pos(player)) > 36.0;
        if (!initialized || world != lastWorld || teleported) {
            // перший кадр / інший світ / телепорт: лише запам'ятовуємо те, що вже є
            known = now;
            TARGETS.clear();
            initialized = true;
            lastWorld = world;
            lastPos = Rotation.pos(player);
            prevCenter = center;
            prevRadius = radius;
            finishHold(inv, cfg);
            return false;
        }
        lastPos = Rotation.pos(player);

        // нові джерела води/лави, що з'явились у зоні, яку ми сканували і минулого тіка
        for (long key : now) {
            if (known.contains(key) || TARGETS.containsKey(key)) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(key);
            if (!inPrevBox(pos)) {
                continue;                    // просто зайшло в зону сканування - це не нове
            }
            if (placedByEnemy(world, player, pos)) {
                TARGETS.put(key, new Target(key, pos, tick + TTL, tick + reactionTicks(player, pos, cfg)));
            }
        }
        known = now;
        prevCenter = center;
        prevRadius = radius;

        // прибираємо застарілі / вже забрані / безнадійні цілі
        Iterator<Target> it = TARGETS.values().iterator();
        while (it.hasNext()) {
            Target t = it.next();
            FluidState fs = world.getFluidState(t.pos);
            if (t.expireTick < tick || t.tries >= MAX_TRIES || fs.isEmpty() || !fs.isStill()) {
                it.remove();
            }
        }

        Vec3d eye = Rotation.eye(player);
        int bucketSlot = SlotSwap.find(inv, Items.BUCKET);   // тільки ПОРОЖНЄ відро забирає рідину

        // вибираємо найближчу ціль, яку вже "помітили" і яку видно
        Target best = null;
        double bestDist = Double.MAX_VALUE;
        if (bucketSlot >= 0) {
            for (Target t : TARGETS.values()) {
                if (t.readyTick > tick || t.retryTick > tick) {
                    continue;
                }
                if (t.aim == null || !visible(world, player, eye, t.pos, t.aim, reach)) {
                    t.aim = pickAim(world, player, eye, t.pos, reach);
                }
                if (t.aim == null) {
                    continue;
                }
                double d = eye.squaredDistanceTo(t.aim);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
        }

        if (best == null) {
            onTargetTicks = 0;
            if (!holding) {
                return false;
            }
            // ціль зникла (або забрали): не кидаємо слот одразу, а через кілька тіків
            idleTicks++;
            if (idleTicks >= releaseDelay) {
                finishHold(inv, cfg);
                return false;
            }
            return true;
        }
        idleTicks = 0;

        // 1) беремо відро в руку
        if (inv.getSelectedSlot() != bucketSlot) {
            SlotSwap.select(inv, bucketSlot);
            holding = true;
            heldTicks = 0;
            swapWait = rnd(1, 3);
            onTargetTicks = 0;
        } else {
            heldTicks++;
        }

        // 2) плавно повертаємо справжню камеру
        Rotation.reset();                    // тихий поворот павутинки більше не потрібен
        stepCamera(mc, player, eye, best.aim, cfg.drainSmoothness);

        // 3) клікаємо тільки коли реальний приціл дивиться на джерело
        if (heldTicks >= swapWait && lookingAt(world, player, eye, best.pos, reach)) {
            onTargetTicks++;
            if (onTargetTicks > clickDelay) {
                useBucket(mc, player);
                best.tries++;
                best.retryTick = tick + rnd(4, 6);
                used = true;
                onTargetTicks = 0;
                clickDelay = rnd(0, 2);
            }
        } else {
            onTargetTicks = 0;
        }
        return true;
    }

    // ----- людська затримка реакції -----

    private static int reactionTicks(ClientPlayerEntity player, BlockPos pos, WebConfig cfg) {
        int base = rnd(cfg.drainReactMin, cfg.drainReactMax);
        float[] a = Rotation.anglesTo(Rotation.eye(player), pos.toCenterPos());
        double dy = MathHelper.wrapDegrees(a[0] - player.getYaw());
        double dp = a[1] - player.getPitch();
        double angle = Math.sqrt(dy * dy + dp * dp);
        return base + (int) Math.round(angle / 180.0 * 6.0); // що далі від погляду - то довше "помічаємо"
    }

    // ----- камера -----

    /** Крок справжньою камерою: гальмування біля цілі, легке тремтіння, кроки кратні чутливості миші. */
    private static void stepCamera(MinecraftClient mc, ClientPlayerEntity player, Vec3d eye, Vec3d aim, int smoothness) {
        float[] want = Rotation.anglesTo(eye, aim);
        double dy = MathHelper.wrapDegrees(want[0] - player.getYaw());
        double dp = want[1] - player.getPitch();
        double dist = Math.sqrt(dy * dy + dp * dp);
        if (dist < 0.05) {
            return;
        }

        double maxStep = Rotation.maxStepDeg(smoothness);
        double step = Math.min(maxStep, Math.max(0.8, dist * 0.45));
        if (dist <= step) {
            step = dist;
        }
        double k = step / dist;
        double sy = dy * k;
        double sp = dp * k;

        if (dist > 6.0) {                    // далеко від цілі рука "тремтить" трохи
            ThreadLocalRandom r = ThreadLocalRandom.current();
            sy += r.nextGaussian() * 0.12;
            sp += r.nextGaussian() * 0.10;
        }

        // реальна миша рухає камеру цілими "відліками": 1 відлік = g * 0.15 градуса
        double sens = mc.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double g = f * f * f * 8.0;
        double unit = g * 0.15;
        long cx = Math.round(sy / unit);
        long cy = Math.round(sp / unit);
        if (cx == 0 && cy == 0) {
            return;
        }
        player.changeLookDirection(cx * g, cy * g);
    }

    /** Чи дивиться ВЖЕ реальний приціл (без підміни) на це джерело в межах дальності. */
    private static boolean lookingAt(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos pos, double reach) {
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(reach));
        BlockHitResult r = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, player));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos);
    }

    /** Правий клік порожнім відром: забирає воду/лаву, нічого не ставить. */
    private static void useBucket(MinecraftClient mc, ClientPlayerEntity player) {
        ActionResult result = mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ----- цілі -----

    private static Vec3d pickAim(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos pos, double reach) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            Vec3d t = new Vec3d(
                    pos.getX() + 0.25 + r.nextDouble() * 0.5,
                    pos.getY() + 0.25 + r.nextDouble() * 0.5,
                    pos.getZ() + 0.25 + r.nextDouble() * 0.5);
            if (visible(world, player, eye, pos, t, reach)) {
                return t;
            }
        }
        Vec3d c = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return visible(world, player, eye, pos, c, reach) ? c : null;
    }

    /** Точка всередині блока видима з очей, у межах дальності, і перший влучений блок - саме цей. */
    private static boolean visible(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos pos, Vec3d t, double reach) {
        if (eye.distanceTo(t) > reach) {
            return false;
        }
        BlockHitResult r = world.raycast(new RaycastContext(
                eye, t, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, player));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos);
    }

    private static Set<Long> scan(ClientWorld world, BlockPos c, int r) {
        Set<Long> out = new HashSet<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                    FluidState fs = world.getFluidState(m);
                    if (fs.isEmpty() || !fs.isStill()) {
                        continue;
                    }
                    if (fs.isIn(FluidTags.WATER) || fs.isIn(FluidTags.LAVA)) {
                        out.add(m.asLong());
                    }
                }
            }
        }
        return out;
    }

    private static boolean inPrevBox(BlockPos pos) {
        if (prevCenter == null) {
            return false;
        }
        return Math.abs(pos.getX() - prevCenter.getX()) <= prevRadius
                && Math.abs(pos.getY() - prevCenter.getY()) <= prevRadius
                && Math.abs(pos.getZ() - prevCenter.getZ()) <= prevRadius;
    }

    /** Нове джерело поставив ЧУЖИЙ гравець: він поруч і тримає відро / щойно махнув рукою. */
    private static boolean placedByEnemy(ClientWorld world, ClientPlayerEntity self, BlockPos pos) {
        Vec3d c = pos.toCenterPos();
        for (PlayerEntity p : world.getPlayers()) {
            if (p == self || !p.isAlive() || p.isSpectator() || self.isTeammate(p)) {
                continue;
            }
            if (p.squaredDistanceTo(c) > ENEMY_RADIUS_SQ) {
                continue;
            }
            if (p.handSwinging || holdsBucket(p.getMainHandStack()) || holdsBucket(p.getOffHandStack())) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsBucket(ItemStack s) {
        return s.isOf(Items.BUCKET) || s.isOf(Items.WATER_BUCKET) || s.isOf(Items.LAVA_BUCKET);
    }

    // ----- слот -----

    private static void finishHold(PlayerInventory inv, WebConfig cfg) {
        if (holding) {
            if (used) {
                SlotSwap.finish(inv, cfg.returnSword);
            } else {
                SlotSwap.abort(inv);
            }
        }
        holding = false;
        used = false;
        resetCounters();
    }

    private static int rnd(int min, int max) {
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
