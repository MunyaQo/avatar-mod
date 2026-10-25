package com.avatarlink;

import com.avatarlink.block.LinkPodBlock;
import com.avatarlink.item.SedativeItem;
import com.avatarlink.net.Net;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(AvatarLinkMod.MODID)
public class AvatarLinkMod {
    public static final String MODID = "avatarlink";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Block> LINK_POD = BLOCKS.register("link_pod",
            () -> new LinkPodBlock(AbstractBlock.Properties.of(Material.METAL).strength(3.0F).sound(SoundType.METAL)));

    public static final RegistryObject<Item> LINK_POD_ITEM = ITEMS.register("link_pod",
            () -> new BlockItem(LINK_POD.get(), new Item.Properties().tab(ItemGroup.TAB_MISC)));

    public static final RegistryObject<Item> SEDATIVE = ITEMS.register("sedative",
            () -> new SedativeItem(new Item.Properties().tab(ItemGroup.TAB_MISC).stacksTo(16)));

    public AvatarLinkMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener(this::setup);
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Net::register);
    }
}
