package com.avatarlink.link;

import com.avatarlink.AvatarLinkMod;
import com.avatarlink.block.LinkPodBlock;
import com.avatarlink.net.Net;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.monster.AbstractSkeletonEntity;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.entity.monster.EndermanEntity;
import net.minecraft.entity.monster.SpiderEntity;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileHelper;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.EntityRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.Explosion;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

/**
 * Вся серверная логика мода: подготовка моба, вселение, управление и способности.
 */
@Mod.EventBusSubscriber(modid = AvatarLinkMod.MODID)
public final class LinkManager {
    private static final String OWNER_TAG = "avatarlink_owner";
    private static final double SEARCH_RADIUS = 32.0D;
    private static final int RAGE_TICKS = 100;

    /** Активные связи: игрок -> состояние связи. */
    private static final Map<UUID, Link> LINKS = new ConcurrentHashMap<>();

    private LinkManager() {
    }

    private static final class Link {
        final UUID mob;
        final BlockPos pod;
        float forward;
        float strafe;
        boolean jump;
        float yaw;
        float pitch;
        double vy;
        int cooldown;          // кулдаун особой способности (R)
        int primaryCooldown;   // кулдаун основной атаки (левая кнопка)
        int boostTicks;        // ярость зомби
        int leapTicks;         // рывок (прыжок паука, отскок скелета)
        boolean leapLaunch;
        double leapX;
        double leapZ;
        double leapVy;

        Link(UUID mob, BlockPos pod, float yaw, float pitch) {
            this.mob = mob;
            this.pod = pod;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    // ---------------------------------------------------------------- подготовка моба

    public static boolean isSupported(Entity e) {
        return e instanceof ZombieEntity
                || e instanceof AbstractSkeletonEntity
                || e instanceof CreeperEntity
                || e instanceof EndermanEntity
                || e instanceof SpiderEntity;
    }

    public static boolean isPrepared(MobEntity mob) {
        return mob.getPersistentData().contains(OWNER_TAG);
    }

    public static void prepare(MobEntity mob, PlayerEntity owner) {
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.getPersistentData().putString(OWNER_TAG, owner.getUUID().toString());
        // Защита от сгорания на солнце, чтобы спящий аватар не погиб просто так.
        mob.addEffect(new EffectInstance(Effects.FIRE_RESISTANCE, 20000000, 0, false, false));
        if (mob.level instanceof ServerWorld) {
            ((ServerWorld) mob.level).sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    mob.getX(), mob.getY() + mob.getBbHeight() / 2.0D, mob.getZ(),
                    12, 0.4D, 0.5D, 0.4D, 0.02D);
        }
        owner.displayClientMessage(new TranslationTextComponent("message.avatarlink.prepared"), true);
    }

    // ---------------------------------------------------------------- вселение

    public static boolean isLinked(PlayerEntity player) {
        return LINKS.containsKey(player.getUUID());
    }

    public static void tryStart(ServerPlayerEntity player, ServerWorld world, BlockPos pod) {
        if (LINKS.containsKey(player.getUUID())) {
            return;
        }
        final String ownerId = player.getUUID().toString();
        final double cx = pod.getX() + 0.5D;
        final double cy = pod.getY() + 0.5D;
        final double cz = pod.getZ() + 0.5D;

        List<MobEntity> found = world.getEntitiesOfClass(MobEntity.class,
                new AxisAlignedBB(pod).inflate(SEARCH_RADIUS),
                m -> m.isAlive() && ownerId.equals(m.getPersistentData().getString(OWNER_TAG)));
        found.sort(Comparator.comparingDouble(m -> m.distanceToSqr(cx, cy, cz)));

        if (found.isEmpty()) {
            player.displayClientMessage(new TranslationTextComponent("message.avatarlink.no_mob"), true);
            return;
        }
        MobEntity mob = null;
        for (MobEntity candidate : found) {
            if (!isLinkedByAnyone(candidate.getUUID())) {
                mob = candidate;
                break;
            }
        }
        if (mob == null) {
            player.displayClientMessage(new TranslationTextComponent("message.avatarlink.busy"), true);
            return;
        }

        BlockState podState = world.getBlockState(pod);
        BlockPos head = LinkPodBlock.headPos(podState, pod);
        // Тело игрока ложится в капсулу, крышка закрывается.
        player.connection.teleport(head.getX() + 0.5D, head.getY() + 0.375D, head.getZ() + 0.5D, player.yRot, player.xRot);
        LinkPodBlock.setOpen(world, pod, false);

        LINKS.put(player.getUUID(), new Link(mob.getUUID(), pod, mob.yRot, mob.xRot));
        Net.sendLinkState(player, mob.getId(), head);
        player.displayClientMessage(new TranslationTextComponent("message.avatarlink.linked"), true);
    }

    private static boolean isLinkedByAnyone(UUID mobId) {
        for (Link l : LINKS.values()) {
            if (l.mob.equals(mobId)) {
                return true;
            }
        }
        return false;
    }

    /** Выход из аватара по желанию игрока (без штрафа). */
    public static void stop(ServerPlayerEntity player) {
        Link link = LINKS.remove(player.getUUID());
        if (link != null) {
            finish(player, link, "message.avatarlink.unlinked");
        }
    }

    /** Общее завершение связи: открыть крышку, убрать значки, сообщить клиенту. */
    private static void finish(ServerPlayerEntity player, Link link, String messageKey) {
        if (player.level instanceof ServerWorld) {
            LinkPodBlock.setOpen((ServerWorld) player.level, link.pod, true);
        }
        player.removeEffect(Effects.DAMAGE_BOOST);
        Net.sendLinkState(player, -1, BlockPos.ZERO);
        if (messageKey != null) {
            player.displayClientMessage(new TranslationTextComponent(messageKey), true);
        }
    }

    public static void setInput(ServerPlayerEntity player, float forward, float strafe, boolean jump, float yaw, float pitch) {
        Link l = LINKS.get(player.getUUID());
        if (l == null) {
            return;
        }
        if (Float.isNaN(yaw) || Float.isInfinite(yaw) || Float.isNaN(pitch) || Float.isInfinite(pitch)
                || Float.isNaN(forward) || Float.isNaN(strafe)) {
            return;
        }
        l.forward = MathHelper.clamp(forward, -1.0F, 1.0F);
        l.strafe = MathHelper.clamp(strafe, -1.0F, 1.0F);
        l.jump = jump;
        l.yaw = yaw;
        l.pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
    }

    // ---------------------------------------------------------------- события сервера

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || LINKS.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Link>> it = LINKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Link> entry = it.next();
            Link link = entry.getValue();
            ServerPlayerEntity player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!player.isAlive()) {
                it.remove();
                finish(player, link, null);
                continue;
            }
            ServerWorld world = (ServerWorld) player.level;
            Entity entity = world.getEntity(link.mob);
            if (!(entity instanceof MobEntity) || !entity.isAlive()) {
                it.remove();
                finish(player, link, "message.avatarlink.lost");
                continue;
            }
            if (!(world.getBlockState(link.pod).getBlock() instanceof LinkPodBlock)) {
                it.remove();
                finish(player, link, "message.avatarlink.pod_lost");
                continue;
            }
            drive((MobEntity) entity, link);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Link link = LINKS.remove(event.getPlayer().getUUID());
        if (link != null && event.getPlayer().level instanceof ServerWorld) {
            LinkPodBlock.setOpen((ServerWorld) event.getPlayer().level, link.pod, true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(FMLServerStoppedEvent event) {
        LINKS.clear();
    }

    // Пока игрок в аватаре, его настоящее тело ни с чем не взаимодействует.

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (event.getPlayer() != null && isLinked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- управление мобом

    private static double speedFor(MobEntity mob) {
        if (mob instanceof EndermanEntity) {
            return 0.26D;
        }
        if (mob instanceof SpiderEntity) {
            return 0.24D;
        }
        return 0.19D;
    }

    private static void drive(MobEntity mob, Link l) {
        if (!mob.isNoAi()) {
            mob.setNoAi(true);
        }
        mob.yRot = l.yaw;
        mob.xRot = l.pitch;
        mob.yHeadRot = l.yaw;
        mob.yBodyRot = l.yaw;

        double speed = speedFor(mob);
        if (l.boostTicks > 0) {
            speed *= 1.5D;
            l.boostTicks--;
        }
        float rad = l.yaw * ((float) Math.PI / 180.0F);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double mx = -sin * l.forward + cos * l.strafe;
        double mz = cos * l.forward + sin * l.strafe;
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len > 1.0D) {
            mx /= len;
            mz /= len;
        }
        mx *= speed;
        mz *= speed;

        if (l.leapTicks > 0) {
            mx = l.leapX;
            mz = l.leapZ;
            l.leapTicks--;
        }

        boolean wantsToMove = l.forward != 0.0F || l.strafe != 0.0F;
        boolean climbing = mob instanceof SpiderEntity && mob.horizontalCollision && wantsToMove;

        if (mob.isInWater()) {
            l.vy = l.jump ? 0.12D : -0.03D;
            mx *= 0.5D;
            mz *= 0.5D;
        } else if (climbing) {
            l.vy = 0.2D;
        } else if (mob.isOnGround()) {
            l.vy = l.jump ? 0.42D : -0.0784D;
        } else {
            l.vy = (l.vy - 0.08D) * 0.98D;
        }
        if (l.leapLaunch) {
            l.vy = l.leapVy;
            l.leapLaunch = false;
        }

        mob.move(MoverType.SELF, new Vector3d(mx, l.vy, mz));
        if (mob.verticalCollision && l.vy > 0.0D) {
            l.vy = 0.0D;
        }
        mob.setDeltaMovement(mx, l.vy, mz);

        if (l.cooldown > 0) {
            l.cooldown--;
        }
        if (l.primaryCooldown > 0) {
            l.primaryCooldown--;
        }
    }

    // ---------------------------------------------------------------- основная атака (левая кнопка)

    public static void usePrimary(ServerPlayerEntity player) {
        Link l = LINKS.get(player.getUUID());
        if (l == null || l.primaryCooldown > 0) {
            return;
        }
        MobEntity mob = findMob(player, l);
        if (mob == null) {
            return;
        }
        if (mob instanceof SpiderEntity) {
            melee(mob, player, 2.0F);
            l.primaryCooldown = 10;
        } else if (mob instanceof ZombieEntity) {
            melee(mob, player, l.boostTicks > 0 ? 5.0F : 3.0F);
            l.primaryCooldown = 10;
        } else if (mob instanceof AbstractSkeletonEntity) {
            shootArrow(mob);
            l.primaryCooldown = 20;
        }
    }

    // ---------------------------------------------------------------- особые способности (R)

    public static void useAbility(ServerPlayerEntity player) {
        Link l = LINKS.get(player.getUUID());
        if (l == null || l.cooldown > 0) {
            return;
        }
        MobEntity mob = findMob(player, l);
        if (mob == null) {
            return;
        }
        float rad = l.yaw * ((float) Math.PI / 180.0F);
        if (mob instanceof SpiderEntity) {
            // Прыжок вперёд
            if (mob.isOnGround()) {
                l.leapX = -Math.sin(rad) * 0.6D;
                l.leapZ = Math.cos(rad) * 0.6D;
                l.leapTicks = 10;
                l.leapVy = 0.55D;
                l.leapLaunch = true;
                l.cooldown = 60;
                mob.level.playSound(null, mob.blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundCategory.HOSTILE, 1.0F, 1.2F);
            }
        } else if (mob instanceof ZombieEntity) {
            // Ярость: бег быстрее на 50%, удар сильнее, значок эффекта и красные края экрана
            l.boostTicks = RAGE_TICKS;
            l.cooldown = 300;
            player.addEffect(new EffectInstance(Effects.DAMAGE_BOOST, RAGE_TICKS, 0, false, false, true));
            mob.level.playSound(null, mob.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundCategory.HOSTILE, 1.0F, 1.1F);
            if (mob.level instanceof ServerWorld) {
                ((ServerWorld) mob.level).sendParticles(ParticleTypes.ANGRY_VILLAGER,
                        mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(),
                        8, 0.4D, 0.3D, 0.4D, 0.02D);
            }
        } else if (mob instanceof AbstractSkeletonEntity) {
            // Отскок: прыжок назад, чтобы держать дистанцию
            if (mob.isOnGround()) {
                l.leapX = Math.sin(rad) * 0.5D;
                l.leapZ = -Math.cos(rad) * 0.5D;
                l.leapTicks = 8;
                l.leapVy = 0.38D;
                l.leapLaunch = true;
                l.cooldown = 50;
                mob.level.playSound(null, mob.blockPosition(), SoundEvents.SKELETON_AMBIENT, SoundCategory.HOSTILE, 1.0F, 1.3F);
            }
        } else if (mob instanceof CreeperEntity) {
            explode(mob);
            l.cooldown = 80;
        } else if (mob instanceof EndermanEntity) {
            teleport(mob);
            l.cooldown = 20;
        }
    }

    private static MobEntity findMob(ServerPlayerEntity player, Link l) {
        Entity entity = ((ServerWorld) player.level).getEntity(l.mob);
        return entity instanceof MobEntity ? (MobEntity) entity : null;
    }

    private static void melee(MobEntity mob, PlayerEntity controller, float damage) {
        World w = mob.level;
        Vector3d eye = mob.getEyePosition(1.0F);
        Vector3d look = mob.getViewVector(1.0F);
        Vector3d end = eye.add(look.scale(3.0D));
        AxisAlignedBB box = mob.getBoundingBox().expandTowards(look.scale(3.0D)).inflate(1.0D);
        EntityRayTraceResult hit = ProjectileHelper.getEntityHitResult(w, mob, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && e != controller);
        mob.swing(Hand.MAIN_HAND);
        if (hit != null) {
            hit.getEntity().hurt(DamageSource.mobAttack(mob), damage);
        }
    }

    private static void shootArrow(MobEntity mob) {
        World w = mob.level;
        ArrowEntity arrow = new ArrowEntity(w, mob);
        arrow.shootFromRotation(mob, mob.xRot, mob.yRot, 0.0F, 3.0F, 1.0F);
        w.addFreshEntity(arrow);
        w.playSound(null, mob.blockPosition(), SoundEvents.SKELETON_SHOOT, SoundCategory.HOSTILE, 1.0F, 1.0F);
    }

    private static void explode(MobEntity mob) {
        World w = mob.level;
        Explosion.Mode mode = w.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                ? Explosion.Mode.DESTROY : Explosion.Mode.NONE;
        w.explode(mob, mob.getX(), mob.getY(), mob.getZ(), 2.5F, mode);
    }

    private static void teleport(MobEntity mob) {
        World w = mob.level;
        Vector3d eye = mob.getEyePosition(1.0F);
        Vector3d look = mob.getViewVector(1.0F);
        Vector3d end = eye.add(look.scale(24.0D));
        BlockRayTraceResult hit = w.clip(new RayTraceContext(eye, end,
                RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, mob));
        Vector3d dest = hit.getType() == RayTraceResult.Type.MISS
                ? end : hit.getLocation().subtract(look.scale(0.6D));
        mob.randomTeleport(dest.x, dest.y, dest.z, true);
    }
}
