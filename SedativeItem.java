package com.avatarlink.item;

import com.avatarlink.link.LinkManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.text.TranslationTextComponent;

/** Усыпитель: ПКМ по мобу - моб засыпает и привязывается к игроку. */
public class SedativeItem extends Item {
    public SedativeItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public ActionResultType interactLivingEntity(ItemStack stack, PlayerEntity player, LivingEntity target, Hand hand) {
        if (!LinkManager.isSupported(target)) {
            return ActionResultType.PASS;
        }
        if (player.level.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        MobEntity mob = (MobEntity) target;
        if (LinkManager.isPrepared(mob)) {
            player.displayClientMessage(new TranslationTextComponent("message.avatarlink.already_prepared"), true);
            return ActionResultType.CONSUME;
        }
        LinkManager.prepare(mob, player);
        if (!player.abilities.instabuild) {
            stack.shrink(1);
        }
        return ActionResultType.CONSUME;
    }
}
