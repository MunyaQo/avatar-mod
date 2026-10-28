package com.avatarlink.client;

import com.avatarlink.AvatarLinkMod;
import com.avatarlink.net.Net;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Pose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Effects;
import net.minecraft.util.MovementInput;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Клиентская часть: клавиши, камера "глазами моба", экран (хотбар, сердечки аватара,
 * красные края при ярости), тело игрока, лежащее в капсуле, и отправка ввода на сервер.
 */
public final class ClientHandler {
    static final KeyBinding ABILITY_KEY = new KeyBinding("key.avatarlink.ability", GLFW.GLFW_KEY_R, "key.categories.avatarlink");
    static final KeyBinding EXIT_KEY = new KeyBinding("key.avatarlink.exit", GLFW.GLFW_KEY_V, "key.categories.avatarlink");
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

    /** id моба, в которого мы вселены; -1 если связи нет. */
    private static int linkedEntityId = -1;
    /** Головной блок капсулы, в которой лежит тело. */
    private static BlockPos podHead = BlockPos.ZERO;

    // сохранённое состояние тела на время отрисовки
    private static boolean bodyOverridden;
    private static Pose sPose;
    private static Optional<BlockPos> sSleepPos = Optional.empty();

    private ClientHandler() {
    }

    /** Вызывается из сетевого пакета. */
    public static void onLinkState(int entityId, BlockPos headPos) {
        linkedEntityId = entityId;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (entityId >= 0) {
            podHead = headPos;
            Vector3d m = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0D, m.y, 0.0D);
        } else {
            mc.setCameraEntity(mc.player);
        }
    }

    @Mod.EventBusSubscriber(modid = AvatarLinkMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            ClientRegistry.registerKeyBinding(ABILITY_KEY);
            ClientRegistry.registerKeyBinding(EXIT_KEY);
            // Стеклянная крышка капсулы полупрозрачная.
            event.enqueueWork(() -> RenderTypeLookup.setRenderLayer(AvatarLinkMod.LINK_POD.get(), RenderType.translucent()));
        }
    }

    @Mod.EventBusSubscriber(modid = AvatarLinkMod.MODID, value = Dist.CLIENT)
    public static final class ForgeEvents {

        /** Забираем нажатия клавиш движения у настоящего тела и отправляем их аватару. */
        @SubscribeEvent
        public static void onInput(InputUpdateEvent event) {
            if (linkedEntityId < 0) {
                return;
            }
            PlayerEntity player = event.getPlayer();
            MovementInput in = event.getMovementInput();
            Net.CHANNEL.sendToServer(new Net.InputPacket(
                    in.forwardImpulse, in.leftImpulse, in.jumping,
                    player.yRot, player.xRot));
            in.forwardImpulse = 0.0F;
            in.leftImpulse = 0.0F;
            in.jumping = false;
            in.shiftKeyDown = false;
            // Тело сразу замирает, без скольжения по инерции.
            player.setSprinting(false);
            Vector3d m = player.getDeltaMovement();
            player.setDeltaMovement(0.0D, m.y, 0.0D);
        }

        /** Левая кнопка мыши: атака аватара. Настоящее тело ничего не делает. */
        @SubscribeEvent
        public static void onClick(InputEvent.ClickInputEvent event) {
            if (linkedEntityId < 0) {
                return;
            }
            if (event.isAttack()) {
                Net.CHANNEL.sendToServer(new Net.PrimaryPacket());
            }
            event.setCanceled(true);
            event.setSwingHand(false);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (ABILITY_KEY.consumeClick()) {
                if (linkedEntityId >= 0) {
                    Net.CHANNEL.sendToServer(new Net.AbilityPacket());
                }
            }
            while (EXIT_KEY.consumeClick()) {
                if (linkedEntityId >= 0) {
                    Net.CHANNEL.sendToServer(new Net.ExitPacket());
                }
            }
        }

        /** Каждый кадр: камера - глаза моба, поворот моба = поворот мышки игрока. */
        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START || linkedEntityId < 0) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            Entity target = mc.level.getEntity(linkedEntityId);
            if (target == null) {
                return;
            }
            if (mc.getCameraEntity() != target) {
                mc.setCameraEntity(target);
            }
            float yaw = mc.player.yRot;
            float pitch = mc.player.xRot;
            target.yRot = yaw;
            target.yRotO = yaw;
            target.xRot = pitch;
            target.xRotO = pitch;
            if (target instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) target;
                living.yHeadRot = yaw;
                living.yHeadRotO = yaw;
                living.yBodyRot = yaw;
                living.yBodyRotO = yaw;
            }
        }

        // --- настоящее тело лежит в капсуле: на время отрисовки включаем позу сна

        @SubscribeEvent
        public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (linkedEntityId < 0 || event.getPlayer() != mc.player) {
                return;
            }
            PlayerEntity p = event.getPlayer();
            sPose = p.getPose();
            sSleepPos = p.getSleepingPos();
            p.setPose(Pose.SLEEPING);
            p.setSleepingPos(podHead);
            bodyOverridden = true;
        }

        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
            if (!bodyOverridden) {
                return;
            }
            PlayerEntity p = event.getPlayer();
            p.setPose(sPose);
            if (sSleepPos.isPresent()) {
                p.setSleepingPos(sSleepPos.get());
            } else {
                p.clearSleepingPos();
            }
            bodyOverridden = false;
        }

        // --- экран: хотбар, сердечки аватара, красные края при ярости

        @SubscribeEvent
        public static void onOverlay(RenderGameOverlayEvent.Post event) {
            if (linkedEntityId < 0 || event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) {
                return;
            }
            MatrixStack ms = event.getMatrixStack();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            if (mc.player.hasEffect(Effects.DAMAGE_BOOST)) {
                drawRage(ms, w, h);
            }
            drawHotbar(mc, ms, w, h);
            drawAvatarHearts(mc, ms, w, h);
        }

        private static void drawRage(MatrixStack ms, int w, int h) {
            // Несколько слоёв красной рамки: чем ближе к краю, тем краснее.
            int color = 0x30D01010;
            for (int i = 1; i <= 6; i++) {
                int t = i * 10;
                AbstractGui.fill(ms, 0, 0, w, t, color);
                AbstractGui.fill(ms, 0, h - t, w, h, color);
                AbstractGui.fill(ms, 0, t, t, h - t, color);
                AbstractGui.fill(ms, w - t, t, w, h - t, color);
            }
        }

        private static void drawHotbar(Minecraft mc, MatrixStack ms, int w, int h) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bind(WIDGETS);
            int x = w / 2 - 91;
            int y = h - 22;
            mc.gui.blit(ms, x, y, 0, 0, 182, 22);
            mc.gui.blit(ms, x - 1 + mc.player.inventory.selected * 20, y - 1, 0, 22, 24, 24);
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.inventory.getItem(i);
                if (!stack.isEmpty()) {
                    int ix = x + 3 + i * 20;
                    int iy = y + 3;
                    mc.getItemRenderer().renderAndDecorateItem(mc.player, stack, ix, iy);
                    mc.getItemRenderer().renderGuiItemDecorations(mc.font, stack, ix, iy);
                }
            }
        }

        private static void drawAvatarHearts(Minecraft mc, MatrixStack ms, int w, int h) {
            Entity e = mc.level.getEntity(linkedEntityId);
            if (!(e instanceof LivingEntity)) {
                return;
            }
            LivingEntity avatar = (LivingEntity) e;
            float health = avatar.getHealth();
            int hearts = (int) Math.ceil(avatar.getMaxHealth() / 2.0D);
            RenderSystem.enableBlend();
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bind(AbstractGui.GUI_ICONS_LOCATION);
            for (int i = 0; i < hearts; i++) {
                int x = w / 2 - 91 + (i % 10) * 8;
                int y = h - 39 - (i / 10) * 10;
                mc.gui.blit(ms, x, y, 16, 0, 9, 9);
                float left = health - i * 2.0F;
                if (left >= 2.0F) {
                    mc.gui.blit(ms, x, y, 52, 0, 9, 9);
                } else if (left >= 1.0F) {
                    mc.gui.blit(ms, x, y, 61, 0, 9, 9);
                }
            }
        }

        // --- настоящее тело ни с чем не взаимодействует, пока игрок в аватаре

        @SubscribeEvent
        public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            if (linkedEntityId >= 0) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            if (linkedEntityId >= 0) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            if (linkedEntityId >= 0) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
            if (linkedEntityId >= 0) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onAttackEntity(AttackEntityEvent event) {
            if (linkedEntityId >= 0) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            linkedEntityId = -1;
        }
    }
}
