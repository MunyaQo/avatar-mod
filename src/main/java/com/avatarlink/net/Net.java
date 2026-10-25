package com.avatarlink.net;

import com.avatarlink.AvatarLinkMod;
import com.avatarlink.client.ClientHandler;
import com.avatarlink.link.LinkManager;
import java.util.function.Supplier;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;

/** Сетевые пакеты между клиентом и сервером. */
public final class Net {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AvatarLinkMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Net() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, InputPacket.class, InputPacket::encode, InputPacket::decode, InputPacket::handle);
        CHANNEL.registerMessage(id++, AbilityPacket.class, AbilityPacket::encode, AbilityPacket::decode, AbilityPacket::handle);
        CHANNEL.registerMessage(id++, PrimaryPacket.class, PrimaryPacket::encode, PrimaryPacket::decode, PrimaryPacket::handle);
        CHANNEL.registerMessage(id++, ExitPacket.class, ExitPacket::encode, ExitPacket::decode, ExitPacket::handle);
        CHANNEL.registerMessage(id++, LinkStatePacket.class, LinkStatePacket::encode, LinkStatePacket::decode, LinkStatePacket::handle);
    }

    public static void sendLinkState(ServerPlayerEntity player, int entityId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new LinkStatePacket(entityId));
    }

    /** Клиент -> сервер: ввод игрока, пока он в аватаре. */
    public static final class InputPacket {
        final float forward;
        final float strafe;
        final boolean jump;
        final float yaw;
        final float pitch;

        public InputPacket(float forward, float strafe, boolean jump, float yaw, float pitch) {
            this.forward = forward;
            this.strafe = strafe;
            this.jump = jump;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        static void encode(InputPacket m, PacketBuffer b) {
            b.writeFloat(m.forward);
            b.writeFloat(m.strafe);
            b.writeBoolean(m.jump);
            b.writeFloat(m.yaw);
            b.writeFloat(m.pitch);
        }

        static InputPacket decode(PacketBuffer b) {
            return new InputPacket(b.readFloat(), b.readFloat(), b.readBoolean(), b.readFloat(), b.readFloat());
        }

        static void handle(InputPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayerEntity sender = c.getSender();
                if (sender != null) {
                    LinkManager.setInput(sender, m.forward, m.strafe, m.jump, m.yaw, m.pitch);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** Клиент -> сервер: левая кнопка мыши (основная атака аватара). */
    public static final class PrimaryPacket {
        static void encode(PrimaryPacket m, PacketBuffer b) {
        }

        static PrimaryPacket decode(PacketBuffer b) {
            return new PrimaryPacket();
        }

        static void handle(PrimaryPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayerEntity sender = c.getSender();
                if (sender != null) {
                    LinkManager.usePrimary(sender);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** Клиент -> сервер: нажата клавиша особой способности (R). */
    public static final class AbilityPacket {
        static void encode(AbilityPacket m, PacketBuffer b) {
        }

        static AbilityPacket decode(PacketBuffer b) {
            return new AbilityPacket();
        }

        static void handle(AbilityPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayerEntity sender = c.getSender();
                if (sender != null) {
                    LinkManager.useAbility(sender);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** Клиент -> сервер: нажата клавиша выхода из аватара. */
    public static final class ExitPacket {
        static void encode(ExitPacket m, PacketBuffer b) {
        }

        static ExitPacket decode(PacketBuffer b) {
            return new ExitPacket();
        }

        static void handle(ExitPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayerEntity sender = c.getSender();
                if (sender != null) {
                    LinkManager.stop(sender);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** Сервер -> клиент: id моба, чьими глазами смотреть (-1 = связь закончена). */
    public static final class LinkStatePacket {
        final int entityId;

        public LinkStatePacket(int entityId) {
            this.entityId = entityId;
        }

        static void encode(LinkStatePacket m, PacketBuffer b) {
            b.writeInt(m.entityId);
        }

        static LinkStatePacket decode(PacketBuffer b) {
            return new LinkStatePacket(b.readInt());
        }

        static void handle(LinkStatePacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.onLinkState(m.entityId)));
            c.setPacketHandled(true);
        }
    }
}
