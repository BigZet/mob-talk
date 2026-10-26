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
    public static Item ENVELOPE, CERTIFICATE, PASSPORT, LEDGER, CIVIL_CERTIFICATE, SPRAVKA;
    public static VillagerProfession HEAD, CLERK, INSPECTOR;
    /** Where the envelope keeps its emeralds (custom data). */
    static final String EMERALDS = "mobtalk_emeralds";
    /** The certificate's owner, the issuing official and the game day (custom data). */
    static final String HOLDER = "mobtalk_holder", ISSUER = "mobtalk_issuer", DAY = "mobtalk_day";
    /** The passport's holder UUID, series and number, and registration (custom data, with {@link #HOLDER}, {@link #ISSUER}, {@link #DAY}). */
    static final String HOLDER_ID = "mobtalk_holder_id", NUMBER = "mobtalk_number", PLACE = "mobtalk_place";
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

    /** The passport: holder, series and number, registration, who issued it and when; its tooltip shows the holder's face. */
    public static final class Passport extends Item {
        Passport(Settings settings) { super(settings); }
        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            NbtCompound tag = data(stack);
            if (!tag.contains(HOLDER)) { tooltip.add(Text.translatable("mobtalk.passport.blank").formatted(Formatting.DARK_GRAY)); return; }
            tooltip.add(Text.translatable("mobtalk.passport.holder", tag.getString(HOLDER)).formatted(Formatting.GRAY));
            tooltip.add(Text.translatable("mobtalk.passport.number", tag.getString(NUMBER)).formatted(Formatting.GRAY));
            if (!tag.getString(PLACE).isBlank()) tooltip.add(Text.translatable("mobtalk.passport.place", tag.getString(PLACE)).formatted(Formatting.GRAY));
            tooltip.add(Text.translatable("mobtalk.passport.issued", tag.getString(ISSUER), tag.getLong(DAY)).formatted(Formatting.DARK_GRAY));
        }
        @Override public java.util.Optional<net.minecraft.item.tooltip.TooltipData> getTooltipData(ItemStack stack) {
            NbtCompound tag = data(stack);
            if (!tag.contains(HOLDER_ID)) return java.util.Optional.empty();
            try { return java.util.Optional.of(new Face(java.util.UUID.fromString(tag.getString(HOLDER_ID)), tag.getString(HOLDER))); }
            catch (IllegalArgumentException e) { return java.util.Optional.empty(); }
        }
    }
    /** A civil-status certificate or a справка ({@link CivilOffice}): the form's title, the blank's number, the printed lines and who
     * issued it on which day. The look follows the paper (custom model data: the colour of the certificate, the permit's red band). */
    public static final class OfficialPaper extends Item {
        OfficialPaper(Settings settings) { super(settings); }
        @Override public Text getName(ItemStack stack) {
            String title = data(stack).getString(TITLE);
            return title.isBlank() ? super.getName(stack) : Text.literal(Character.toUpperCase(title.charAt(0)) + title.substring(1));
        }
        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            NbtCompound tag = data(stack);
            if (!tag.contains(TITLE)) { tooltip.add(Text.translatable("mobtalk.civil.blank").formatted(Formatting.DARK_GRAY)); return; }
            if (!tag.getString(NUMBER).isBlank()) tooltip.add(Text.literal(tag.getString(NUMBER)).formatted(Formatting.DARK_RED));
            net.minecraft.nbt.NbtList lines = tag.getList(LINES, net.minecraft.nbt.NbtElement.STRING_TYPE);
            for (int i = 0; i < lines.size(); i++) tooltip.add(Text.literal(lines.getString(i)).formatted(Formatting.GRAY));
            if (tag.getInt(ACT) > 0) tooltip.add(Text.translatable("mobtalk.civil.act", tag.getInt(ACT)).formatted(Formatting.DARK_GRAY));
            tooltip.add(Text.translatable("mobtalk.civil.issued", tag.getString(ISSUER), tag.getLong(DAY)).formatted(Formatting.DARK_GRAY));
        }
    }
    /** The paper's custom data keys (with {@link #NUMBER}, {@link #ISSUER}, {@link #DAY}, {@link #HOLDER_ID}). */
    static final String TITLE = "mobtalk_title", PAPER = "mobtalk_paper", LINES = "mobtalk_lines", ACT = "mobtalk_act";
    /** A paper made out to {@code holder}: its kind, title, blank number, act number (0 for none), printed lines, issuer and day. */
    static ItemStack paper(io.github.mobtalk.core.CivilRegistry.Paper paper, java.util.UUID holder, String number, int act, List<String> lines, String issuer, long day) {
        ItemStack stack = new ItemStack(paper.certificate ? CIVIL_CERTIFICATE : SPRAVKA);
        NbtCompound tag = new NbtCompound();
        tag.putString(PAPER, paper.name()); tag.putString(TITLE, paper.title); tag.putString(NUMBER, number); tag.putInt(ACT, act);
        tag.putString(HOLDER_ID, holder.toString()); tag.putString(ISSUER, issuer); tag.putLong(DAY, day);
        net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
        for (String line : lines) list.add(net.minecraft.nbt.NbtString.of(line));
        tag.put(LINES, list);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        int look = switch (paper) {
            case BIRTH_CERTIFICATE -> 1; case MARRIAGE_CERTIFICATE -> 2; case DIVORCE_CERTIFICATE -> 3; case DEATH_CERTIFICATE -> 4;
            case BUILD_PERMIT -> 1; case NO_RECORD -> 2; default -> 0;
        };
        if (look > 0) stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new net.minecraft.component.type.CustomModelDataComponent(look));
        return stack;
    }
    /** Which paper this stack is, or null. */
    public static io.github.mobtalk.core.CivilRegistry.Paper paperOf(ItemStack stack) {
        if (stack == null || !(stack.isOf(CIVIL_CERTIFICATE) || stack.isOf(SPRAVKA))) return null;
        return io.github.mobtalk.core.CivilRegistry.Paper.of(data(stack).getString(PAPER));
    }

    /** The head's black ledger from the dacha's safe: whose, which village, how many envelopes. */
    public static final class Ledger extends Item {
        Ledger(Settings settings) { super(settings); }
        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            LedgerData d = ledgerData(stack);
            if (d == null) return;
            tooltip.add(Text.translatable("mobtalk.graft.ledger.tooltip", d.headName(), d.count()).formatted(Formatting.GRAY));
            tooltip.add(Text.translatable("mobtalk.graft.ledger.usage").formatted(Formatting.DARK_GRAY));
        }
    }
    public record LedgerData(java.util.UUID head, String headName, String village, int count) { }

    /** The holder's face in the passport's tooltip (drawn on the client from the skin). */
    public record Face(java.util.UUID id, String name) implements net.minecraft.item.tooltip.TooltipData { }

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
        LEDGER = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "black_ledger"), new Ledger(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));
        PASSPORT = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "passport"), new Passport(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON)));
        CIVIL_CERTIFICATE = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "civil_certificate"), new OfficialPaper(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON)));
        SPRAVKA = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "spravka"), new OfficialPaper(new Item.Settings().maxCount(1)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> { entries.add(ENVELOPE); entries.add(CERTIFICATE); entries.add(CIVIL_CERTIFICATE); entries.add(SPRAVKA); });
        // Shift+right-click an official: a visit to the office (the passport); the client answers too, so no trades open.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (hand != Hand.MAIN_HAND || !player.isSneaking() || !(entity instanceof VillagerEntity villager) || rank(villager) == null || villager.isBaby()) return net.minecraft.util.ActionResult.PASS;
            if (world.isClient) return net.minecraft.util.ActionResult.SUCCESS;
            MobGameBridge bridge = MobGameBridge.forEntity(villager);
            if (bridge == null || !(player instanceof net.minecraft.server.network.ServerPlayerEntity sp) || !(world instanceof net.minecraft.server.world.ServerWorld sw)) return net.minecraft.util.ActionResult.PASS;
            return bridge.passports.visit(sw, sp, villager) ? net.minecraft.util.ActionResult.SUCCESS : net.minecraft.util.ActionResult.PASS;
        });
        CivilOffice.registerCommand(p -> { MobGameBridge b = MobGameBridge.forEntity(p); return b == null ? null : b.civil; });
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
    /** A passport made out to the player. */
    static ItemStack passport(java.util.UUID holderId, String holder, String number, String place, String issuer, long day) {
        ItemStack stack = new ItemStack(PASSPORT);
        NbtCompound tag = new NbtCompound();
        tag.putString(HOLDER, holder); tag.putString(HOLDER_ID, holderId.toString()); tag.putString(NUMBER, number);
        tag.putString(PLACE, place == null ? "" : place); tag.putString(ISSUER, issuer); tag.putLong(DAY, day);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }
    /** Whether the player carries his own passport. */
    public static boolean hasPassport(PlayerEntity player) {
        if (PASSPORT == null || player == null) return false;
        String id = player.getUuidAsString();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(PASSPORT) && id.equals(data(stack).getString(HOLDER_ID))) return true;
        }
        return false;
    }
    /** The holder UUID written in a passport, or "" for anything else. */
    public static String passportHolder(ItemStack stack) { return stack != null && PASSPORT != null && stack.isOf(PASSPORT) ? data(stack).getString(HOLDER_ID) : ""; }
    /** The head's black ledger. */
    static ItemStack ledger(java.util.UUID head, String headName, String village, int count) {
        ItemStack stack = new ItemStack(LEDGER);
        NbtCompound tag = new NbtCompound();
        tag.putString(HOLDER_ID, head.toString()); tag.putString(HOLDER, headName); tag.putString(PLACE, village); tag.putInt(EMERALDS, count);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }
    public static LedgerData ledgerData(ItemStack stack) {
        if (stack == null || LEDGER == null || !stack.isOf(LEDGER)) return null;
        NbtCompound tag = data(stack);
        try { return new LedgerData(java.util.UUID.fromString(tag.getString(HOLDER_ID)), tag.getString(HOLDER), tag.getString(PLACE), tag.getInt(EMERALDS)); }
        catch (IllegalArgumentException e) { return null; }
    }
}
