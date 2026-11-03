package io.github.mobtalk.game;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

/** What a wardrobe, a cupboard, a fridge or a chest of drawers holds: one to three rows of nine slots, opened with a click on any of
 * its cells, with the door's sound going and coming. The contents fall out when the piece is broken. */
public class FurnitureStorage extends BlockEntity implements Inventory, NamedScreenHandlerFactory {
    private final DefaultedList<ItemStack> items;
    private final int rows;
    private int viewers;

    public FurnitureStorage(BlockPos pos, BlockState state) {
        super(FurnitureBlocks.STORAGE, pos, state);
        rows = state.getBlock() instanceof FurnitureBlocks.Storage s ? s.rows : 1;
        items = DefaultedList.ofSize(rows * 9, ItemStack.EMPTY);
    }

    @Override protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        items.clear();
        Inventories.readNbt(nbt, items, registries);
    }

    @Override protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        Inventories.writeNbt(nbt, items, registries);
    }

    @Override public int size() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getStack(int slot) { return items.get(slot); }
    @Override public ItemStack removeStack(int slot, int amount) { ItemStack s = Inventories.splitStack(items, slot, amount); if (!s.isEmpty()) markDirty(); return s; }
    @Override public ItemStack removeStack(int slot) { return Inventories.removeStack(items, slot); }
    @Override public void setStack(int slot, ItemStack stack) { items.set(slot, stack); stack.capCount(getMaxCount(stack)); markDirty(); }
    @Override public boolean canPlayerUse(PlayerEntity player) { return Inventory.canPlayerUse(this, player); }
    @Override public void clear() { items.clear(); }

    @Override public void onOpen(PlayerEntity player) {
        if (world != null && !player.isSpectator() && viewers++ == 0) door(true);
    }

    @Override public void onClose(PlayerEntity player) {
        if (world != null && !player.isSpectator() && --viewers <= 0) { viewers = 0; door(false); }
    }

    private void door(boolean open) {
        FurnitureUse.Sound s = FurnitureUse.doorSound(getCachedState(), open);
        world.playSound(null, pos, s.event(), SoundCategory.BLOCKS, s.volume(), s.pitch());
    }

    @Override public Text getDisplayName() { return getCachedState().getBlock().getName(); }

    @Override public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {
        ScreenHandlerType<GenericContainerScreenHandler> type = switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            default -> ScreenHandlerType.GENERIC_9X3;
        };
        return new GenericContainerScreenHandler(type, syncId, inventory, this, rows);
    }
}
