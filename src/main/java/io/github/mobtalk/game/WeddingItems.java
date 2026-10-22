package io.github.mobtalk.game;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Свои предметы мода (трек L): помолвочное и обручальное кольца (крафт на верстаке) и свадебный наряд — венок, фата с венком,
 * венец; наряд носится на голове у жителя и у игрока (ванильный слой головы рисует его поверх). Старые миры колец не требуют:
 * алмаз и золото годятся как запасное кольцо. */
public final class WeddingItems {
    private WeddingItems() { }
    /** Метка на вещах, которые свадьба дала жителю на время: цветы в руке снимаются после службы, наряд — после срока. */
    static final String MARK = "mobtalk_wedding", OUTFIT = "mobtalk_outfit";
    public static Item ENGAGEMENT_RING, WEDDING_RING, FLOWER_WREATH, BRIDAL_VEIL, WEDDING_CROWN;
    private static boolean registered;

    /** Свадебный наряд: надевается на голову правым кликом или в слот шлема. */
    static final class Attire extends Item implements Equipment {
        Attire(Settings settings) { super(settings); }
        @Override public EquipmentSlot getSlotType() { return EquipmentSlot.HEAD; }
        @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) { return equipAndSwap(this, world, user, hand); }
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ENGAGEMENT_RING = item("engagement_ring", new Item(new Item.Settings().maxCount(16).rarity(Rarity.UNCOMMON)));
        WEDDING_RING = item("wedding_ring", new Item(new Item.Settings().maxCount(16).rarity(Rarity.UNCOMMON)));
        FLOWER_WREATH = item("flower_wreath", new Attire(new Item.Settings().maxCount(1)));
        BRIDAL_VEIL = item("bridal_veil", new Attire(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON)));
        WEDDING_CROWN = item("wedding_crown", new Attire(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(ENGAGEMENT_RING); entries.add(WEDDING_RING);
            entries.add(FLOWER_WREATH); entries.add(BRIDAL_VEIL); entries.add(WEDDING_CROWN);
        });
    }
    private static Item item(String name, Item item) { return Registry.register(Registries.ITEM, Identifier.of("mobtalk", name), item); }

    /** Вещь со свадебной меткой: {@code until} — игровой тик, когда её снять (0 — сразу после службы). */
    static ItemStack marked(Item item, long until) { return marked(item, until, ""); }
    /** Трек M: {@code outfit} — праздничный наряд человека-жителя под этим убором ("bride", "groom", "bridesmaid", "groomsman"). */
    static ItemStack marked(Item item, long until, String outfit) {
        ItemStack stack = new ItemStack(item);
        NbtCompound tag = new NbtCompound(); tag.putLong(MARK, until);
        if (!outfit.isEmpty()) tag.putString(OUTFIT, outfit);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }
    /** Тик, до которого вещь со свадебной меткой остаётся; −1 — вещь не свадебная. */
    static long markedUntil(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return -1;
        NbtCompound tag = data.copyNbt();
        return tag.contains(MARK) ? tag.getLong(MARK) : -1;
    }
    /** Трек M: какой наряд надеть человеку-жителю с этим убором на голове; "" — убор не свадебный. Без метки (убор надели руками
     * или он с прошлых версий) — по самому убору: фата — невеста, венец — жених, венок — подружка. */
    public static String outfit(ItemStack head) {
        if (head == null || head.isEmpty() || !attire(head.getItem())) return "";
        NbtComponent data = head.get(DataComponentTypes.CUSTOM_DATA);
        if (data != null) { NbtCompound tag = data.copyNbt(); if (tag.contains(OUTFIT)) return tag.getString(OUTFIT); }
        return head.isOf(BRIDAL_VEIL) ? "bride" : head.isOf(WEDDING_CROWN) ? "groom" : "bridesmaid";
    }
    public static boolean attire(Item item) { return item == FLOWER_WREATH || item == BRIDAL_VEIL || item == WEDDING_CROWN; }
}
