package com.avatarlink.client;

import com.avatarlink.AvatarLinkMod;
import com.avatarlink.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.MovementInput;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Клиентская часть: клавиши, камера "глазами моба" и отправка ввода на сервер.
 */
public final class ClientHandler {
    static final KeyBinding ABILITY_KEY = new KeyBinding("key.avatarlink.ability", GLFW.GLFW_KEY_R, "key.categories.avatarlink");
    static final KeyBinding EXIT_KEY = new KeyBinding("key.avatarlink.exit", GLFW.GLFW_KEY_V, "key.categories.avatarlink");

    /** id моба, в которого мы вселены; -1 если связи нет. */
    private static int linkedEntityId = -1;

    private ClientHandler() {
    }

    /** Вызывается из сетевого пакета. */
    public static void onLinkState(int entityId) {
        linkedEntityId = entityId;
        Minecraft mc = Minecraft.getInstance();
        if (entityId < 0 && mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
    }

    @Mod.EventBusSubscriber(modid = AvatarLinkMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            ClientRegistry.registerKeyBinding(ABILITY_KEY);
            ClientRegistry.registerKeyBinding(EXIT_KEY);
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
            MovementInput in = event.getMovementInput();
            Net.CHANNEL.sendToServer(new Net.InputPacket(
                    in.forwardImpulse, in.leftImpulse, in.jumping,
                    event.getPlayer().yRot, event.getPlayer().xRot));
            in.forwardImpulse = 0.0F;
            in.leftImpulse = 0.0F;
            in.jumping = false;
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

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            linkedEntityId = -1;
        }
    }
}
