package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import io.github.mobtalk.core.Officialdom;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterestType;
import java.util.List;

/** The officials' own content (track «чиновники», docs/officials.md): the official's desk (a block with green cloth and its point
 * of interest), the three professions that hold it — the clerk (whom a free villager becomes at a desk), the head of the
 * administration and the inspector (only the village gives those ranks, {@link OfficialsController}) — the envelope that carries
 * emeralds to them and the clerk's certificate. Registered before any world loads, on both sides. */
public final class OfficialContent {
    private OfficialContent() { }
    public static final Identifier DESK_ID = Identifier.of("mobtalk", "official_desk");
    public static final RegistryKey<PointOfInterestType> DESK_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, DESK_ID);
    public static Block DESK;
    public static Item ENVELOPE, CERTIFICATE;
    public static VillagerProfession HEAD, CLERK, INSPECTOR;
    /** Where the envelope keeps its emeralds (custom data). */
    static final String EMERALDS = "mobtalk_emeralds";
    /** The certificate's owner, the issuing official and the game day (custom data). */
    static final String HOLDER = "mobtalk_holder", ISSUER = "mobtalk_issuer", DAY = "mobtalk_day";
    private static boolean registered;

    /** The desk: a dark top with green cloth on four legs, a stack of papers and a stamp; faces the one who put it down. */
    public static final class Desk extends HorizontalFacingBlock {
        public static final MapCodec<Desk> CODEC = createCodec(Desk::new);
        private static final VoxelShape SHAPE = VoxelShapes.union(Block.createCuboidShape(0, 12, 0, 16, 15, 16),
                Block.createCuboidShape(1, 0, 1, 3, 12, 3), Block.createCuboidShape(13, 0, 1, 15, 12, 3),
                Block.createCuboidShape(1, 0, 13, 3, 12, 15), Block.createCuboidShape(13, 0, 13, 15, 12, 15));
        public Desk(Settings settings) { super(settings); setDefaultState(getStateManager().getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH)); }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return CODEC; }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
    }

    /** The envelope: right-click puts the emeralds from the other hand inside, sneak and right-click takes them out; handed to an
     * official through «Подарить» it is a bribe ({@link OfficialsController#envelope}). One emerald-filled envelope per stack. */
    public static final class Envelope extends Item {
        Envelope(Settings settings) { super(settings); }
        @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack envelope = user.getStackInHand(hand);
            Hand otherHand = hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
            ItemStack other = user.getStackInHand(otherHand);
            int inside = emeralds(envelope);
            if (user.isSneaking()) {
                if (inside <= 0) return TypedActionResult.pass(envelope);
                if (!world.isClient) {
                    set(envelope, 0);
                    user.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, inside));
                    world.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 1.2f);
                    user.sendMessage(Text.translatable("mobtalk.official.envelope.emptied", inside), true);
                }
                return TypedActionResult.success(envelope, world.isClient);
            }
            if (!other.isOf(Items.EMERALD)) {
                if (!world.isClient) user.sendMessage(Text.translatable("mobtalk.official.envelope.hint"), true);
                return TypedActionResult.pass(envelope);
            }
            if (!world.isClient) {
                int put = Math.min(other.getCount(), 999 - inside);
                ItemStack filled = envelope.getCount() > 1 ? envelope.split(1) : envelope;
                set(filled, inside + put);
                other.decrement(put);
                if (filled != envelope) user.getInventory().offerOrDrop(filled);
                world.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 0.9f);
                user.sendMessage(Text.translatable("mobtalk.official.envelope.filled", emeralds(filled)), true);
            }
            return TypedActionResult.success(envelope, world.isClient);
        }
        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            int inside = emeralds(stack);
            tooltip.add(inside > 0 ? Text.translatable("mobtalk.official.envelope.inside", inside).formatted(Formatting.GREEN)
                    : Text.translatable("mobtalk.official.envelope.empty").formatted(Formatting.GRAY));
            tooltip.add(Text.translatable("mobtalk.official.envelope.usage").formatted(Formatting.DARK_GRAY));
        }
        @Override public boolean hasGlint(ItemStack stack) { return false; }
    }

    /** The clerk's certificate: whose it is and who signed it. */
    public static final class Certificate extends Item {
        Certificate(Settings settings) { super(settings); }
        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            NbtCompound tag = data(stack);
            if (tag.contains(HOLDER)) tooltip.add(Text.translatable("mobtalk.official.certificate.holder", tag.getString(HOLDER)).formatted(Formatting.GRAY));
            if (tag.contains(ISSUER)) tooltip.add(Text.translatable("mobtalk.official.certificate.issuer", tag.getString(ISSUER), tag.getLong(DAY)).formatted(Formatting.DARK_GRAY));
        }
    }

    public static void register() {
        if (registered) return;
        registered = true;
        DESK = Registry.register(Registries.BLOCK, DESK_ID, new Desk(AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN)
                .strength(2.5f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
        Item deskItem = Registry.register(Registries.ITEM, DESK_ID, new BlockItem(DESK, new Item.Settings()));
        PointOfInterestHelper.register(DESK_ID, 1, 1, DESK);
        ENVELOPE = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "envelope"), new Envelope(new Item.Settings().maxCount(16)));
        CERTIFICATE = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "certificate"), new Certificate(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(deskItem));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> { entries.add(ENVELOPE); entries.add(CERTIFICATE); });
        // The clerk first: vanilla gives a free villager at a desk the first profession whose job site it is.
        CLERK = profession(Officialdom.Rank.CLERK, true);
        HEAD = profession(Officialdom.Rank.HEAD, false);
        INSPECTOR = profession(Officialdom.Rank.INSPECTOR, false);
        TradeOfferHelper.registerVillagerOffers(CLERK, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 1), new ItemStack(ENVELOPE, 4), 16, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(INSPECTOR, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 1), new ItemStack(ENVELOPE, 2), 12, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.INK_SAC, 6), new ItemStack(Items.EMERALD), 12, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(HEAD, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 4), new ItemStack(Items.CAKE), 4, 5, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.GOLD_INGOT, 3), new ItemStack(Items.EMERALD), 8, 5, 0.05f));
        });
    }
    private static VillagerProfession profession(Officialdom.Rank rank, boolean acquirable) {
        return Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", rank.path),
                new VillagerProfession(rank.path, entry -> entry.matchesKey(DESK_POI), acquirable ? entry -> entry.matchesKey(DESK_POI) : entry -> false,
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_LIBRARIAN));
    }

    /** The rank of this villager, null for anyone who is no official. */
    public static Officialdom.Rank rank(VillagerEntity villager) {
        if (HEAD == null) return null;
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == HEAD ? Officialdom.Rank.HEAD : profession == CLERK ? Officialdom.Rank.CLERK : profession == INSPECTOR ? Officialdom.Rank.INSPECTOR : null;
    }
    public static VillagerProfession profession(Officialdom.Rank rank) {
        return switch (rank) { case HEAD -> HEAD; case CLERK -> CLERK; case INSPECTOR -> INSPECTOR; };
    }

    // ---- The envelope's and the certificate's data ----

    static NbtCompound data(ItemStack stack) {
        NbtComponent data = stack == null ? null : stack.get(DataComponentTypes.CUSTOM_DATA);
        return data == null ? new NbtCompound() : data.copyNbt();
    }
    /** Emeralds inside an envelope; 0 for an empty one or anything else. */
    public static int emeralds(ItemStack stack) {
        if (stack == null || ENVELOPE == null || !stack.isOf(ENVELOPE)) return 0;
        NbtCompound tag = data(stack);
        return tag.contains(EMERALDS) ? Math.max(0, tag.getInt(EMERALDS)) : 0;
    }
    static void set(ItemStack envelope, int emeralds) {
        NbtCompound tag = data(envelope);
        if (emeralds > 0) tag.putInt(EMERALDS, emeralds); else tag.remove(EMERALDS);
        if (tag.isEmpty()) envelope.remove(DataComponentTypes.CUSTOM_DATA); else envelope.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
    }
    /** A filled envelope (self-tests, admin). */
    public static ItemStack envelope(int emeralds) { ItemStack stack = new ItemStack(ENVELOPE); set(stack, emeralds); return stack; }
    /** A certificate made out to {@code holder} by {@code issuer} on game day {@code day}. */
    static ItemStack certificate(String holder, String issuer, long day) {
        ItemStack stack = new ItemStack(CERTIFICATE);
        NbtCompound tag = new NbtCompound(); tag.putString(HOLDER, holder); tag.putString(ISSUER, issuer); tag.putLong(DAY, day);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }
}
