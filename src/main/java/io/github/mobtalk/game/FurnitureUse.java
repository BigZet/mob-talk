package io.github.mobtalk.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potions;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** What the flat's things do when clicked, beyond sitting and switching: the light switch lights the whole room, cupboards,
 * wardrobes and fridges open as storage, taps run (and fill a bottle or a bucket), the toilet flushes, the stove lights its burners
 * and the kettle whistles, the piano plays the key under the cursor, the radio plays «Маяк», the washing machines run a cycle, the
 * clock tells the time, the meter its reading, the notice board a notice, the chute takes rubbish. Server side only; the little
 * tunes run on a tick scheduler. */
final class FurnitureUse {
    private FurnitureUse() { }

    record Sound(SoundEvent event, float volume, float pitch) { }

    /** Storage rows (nine slots each) of the pieces that open. */
    static final Map<String, Integer> ROWS = Map.ofEntries(
            Map.entry("apt_wall_unit", 3), Map.entry("apt_wardrobe", 3), Map.entry("apt_sideboard", 2), Map.entry("apt_bookcase", 2),
            Map.entry("apt_chest_drawers", 2), Map.entry("apt_fridge", 2), Map.entry("apt_fridge_saratov", 2), Map.entry("apt_fridge_minsk", 3),
            Map.entry("apt_fridge_biryusa", 2), Map.entry("apt_kitchen_base_white", 1), Map.entry("apt_kitchen_base_wood", 1),
            Map.entry("apt_kitchen_jars_white", 1), Map.entry("apt_kitchen_jars_wood", 1), Map.entry("apt_kitchen_sink_white", 1),
            Map.entry("apt_kitchen_sink_wood", 1), Map.entry("apt_wall_cupboard_white", 1), Map.entry("apt_wall_cupboard_wood", 1),
            Map.entry("apt_shoe_cabinet", 1), Map.entry("apt_desk", 1), Map.entry("apt_toy_box", 1), Map.entry("apt_post_boxes", 1),
            Map.entry("apt_nightstand", 1), Map.entry("apt_dressing_table", 1), Map.entry("apt_suitcases", 1), Map.entry("apt_laundry_basket", 1));

    /** Pieces that are a lamp and a cupboard at once open below this height (pixels above their placed cell), switch above it. */
    private static final Map<String, Double> LAMP_ABOVE = Map.of("apt_nightstand", 9.5, "apt_desk", 13.0);

    private static final int NOTICES = 8;

    static int rows(String id) { return ROWS.getOrDefault(id, 0); }

    static Sound doorSound(BlockState state, boolean open) {
        String id = state.getBlock() instanceof FurnitureBlocks.Furniture f ? f.f.id() : "";
        if (id.startsWith("apt_fridge")) return new Sound(open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE, 0.45f, 1.35f);
        if (id.equals("apt_post_boxes")) return new Sound(open ? SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN : SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 0.4f, 1.4f);
        if (id.equals("apt_suitcases") || id.equals("apt_laundry_basket") || id.equals("apt_toy_box"))
            return new Sound(SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), 0.6f, open ? 1.1f : 0.9f);
        return new Sound(open ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_BARREL_CLOSE, 0.45f, 1.15f);
    }

    /** A click with an empty hand (or one the item did not take); null leaves it to the default: switch the lamp or sit down. */
    static ActionResult use(FurnitureBlocks.Furniture block, BlockState state, World world, BlockPos pos, BlockPos cell, PlayerEntity player,
                            BlockHitResult hit) {
        String id = block.f.id();
        double above = (hit.getPos().y - pos.getY()) * 16;
        if (rows(id) > 0 && above <= LAMP_ABOVE.getOrDefault(id, 1e9)) {
            if (world.isClient) return ActionResult.SUCCESS;
            if (world.getBlockEntity(pos) instanceof FurnitureStorage storage) player.openHandledScreen(storage);
            return ActionResult.CONSUME;
        }
        if (!(world instanceof ServerWorld sw)) return special(id, player) ? ActionResult.SUCCESS : null;
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        switch (id) {
            case "apt_switch" -> {
                boolean on = !state.get(FurnitureBlocks.LIT);
                block.setLit(world, pos, state, on);
                roomLights(world, pos, on);
            }
            case "apt_kitchen_sink_white", "apt_kitchen_sink_wood", "apt_washbasin", "apt_bathtub" -> tap(sw, pos, facing, id);
            case "apt_toilet" -> {
                if (!player.isSneaking()) return null;
                flush(sw, pos);
            }
            case "apt_stove", "apt_stove_kettle" -> stove(sw, pos, id.endsWith("kettle"));
            case "apt_piano" -> piano(sw, pos, facing, hit.getPos());
            case "apt_wall_radio" -> mayak(sw, pos);
            case "apt_radiola", "apt_tv", "apt_gas_heater" -> {
                boolean on = !state.get(FurnitureBlocks.LIT);
                block.setLit(world, pos, state, on);
                if (on) switchedOn(sw, pos, id, facing);
            }
            case "apt_wall_phone" -> phone(sw, pos);
            case "apt_wall_clock" -> clock(sw, pos, player);
            case "apt_electric_meter" -> player.sendMessage(Text.translatable("mobtalk.furniture.meter", meter(sw, pos)), true);
            case "apt_notice_board" -> player.sendMessage(Text.translatable("mobtalk.furniture.notice." + sw.random.nextInt(NOTICES)), true);
            case "apt_sewing_machine" -> sewing(sw, pos);
            case "apt_washer", "apt_washer_malyutka", "apt_washer_sibir" -> wash(sw, pos, id);
            case "apt_crib" -> lullaby(sw, pos);
            case "apt_ironing_board" -> steam(sw, pos);
            case "apt_chute" -> player.sendMessage(Text.translatable("mobtalk.furniture.chute"), true);
            default -> { return null; }
        }
        return ActionResult.CONSUME;
    }

    /** The client cannot see what the server will do; it only needs to know the click is taken (swing the arm, no second action). */
    private static boolean special(String id, PlayerEntity player) {
        return switch (id) {
            case "apt_toilet" -> player.isSneaking();
            case "apt_switch", "apt_kitchen_sink_white", "apt_kitchen_sink_wood", "apt_washbasin", "apt_bathtub", "apt_stove", "apt_stove_kettle",
                 "apt_piano", "apt_wall_radio", "apt_radiola", "apt_tv", "apt_gas_heater", "apt_wall_phone", "apt_wall_clock", "apt_electric_meter",
                 "apt_notice_board", "apt_sewing_machine", "apt_washer", "apt_washer_malyutka", "apt_washer_sibir", "apt_crib",
                 "apt_ironing_board", "apt_chute" -> true;
            default -> false;
        };
    }

    /** A click holding something: a bottle or a bucket under the tap, rubbish down the chute, water for a plant. */
    static ItemActionResult useWithItem(FurnitureBlocks.Furniture block, World world, BlockPos pos, PlayerEntity player, Hand hand, ItemStack stack) {
        String id = block.f.id();
        boolean tap = id.startsWith("apt_kitchen_sink") || id.equals("apt_washbasin") || id.equals("apt_bathtub");
        if (tap && (stack.isOf(Items.GLASS_BOTTLE) || stack.isOf(Items.BUCKET))) {
            if (world instanceof ServerWorld sw) {
                ItemStack full = stack.isOf(Items.BUCKET) ? new ItemStack(Items.WATER_BUCKET)
                        : PotionContentsComponent.createStack(Items.POTION, Potions.WATER);
                player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, full));
                sw.playSound(null, pos, stack.isOf(Items.BUCKET) ? SoundEvents.ITEM_BUCKET_FILL : SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8f, 1f);
                tap(sw, pos, world.getBlockState(pos).get(Properties.HORIZONTAL_FACING), id);
            }
            return ItemActionResult.success(world.isClient);
        }
        if (id.equals("apt_chute") && !stack.isEmpty()) {
            if (world instanceof ServerWorld sw) {
                player.sendMessage(Text.translatable("mobtalk.furniture.chute_gone", stack.getName()), true);
                player.setStackInHand(hand, ItemStack.EMPTY);
                sw.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.6f, 0.8f);
                later(sw, 6, () -> sw.playSound(null, pos, SoundEvents.BLOCK_CHAIN_FALL, SoundCategory.BLOCKS, 0.9f, 0.6f));
                later(sw, 16, () -> sw.playSound(null, pos.down(6), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.25f, 0.5f));
                later(sw, 10, () -> sw.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.BLOCKS, 0.6f, 0.8f));
            }
            return ItemActionResult.success(world.isClient);
        }
        if ((id.equals("apt_ficus") || id.equals("apt_aloe")) && (stack.isOf(Items.WATER_BUCKET) || stack.isOf(Items.POTION))) {
            if (world instanceof ServerWorld sw) {
                if (stack.isOf(Items.POTION)) player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
                sw.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.7f, 1f);
                burst(sw, ParticleTypes.HAPPY_VILLAGER, Vec3d.ofCenter(pos).add(0, 0.6, 0), 8, 0.3);
                burst(sw, ParticleTypes.SPLASH, Vec3d.ofCenter(pos).add(0, -0.1, 0), 10, 0.2);
            }
            return ItemActionResult.success(world.isClient);
        }
        return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // ---------------------------------------------------------------- the light switch

    /** Every ceiling light of the room the switch is in goes on or off: the room is the air the switch can reach without passing a
     * wall, a door, a doorway, a window or a floor, within eight blocks of it. */
    static int roomLights(World world, BlockPos sw, boolean on) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(sw);
        seen.add(sw.asLong());
        int lamps = 0;
        while (!queue.isEmpty() && seen.size() < 4000) {
            BlockPos p = queue.poll();
            BlockState s = world.getBlockState(p);
            if (s.getBlock() instanceof FurnitureBlocks.Furniture f && f.f.toggle() && f.f.mount() == FurnitureBlocks.Mount.CEILING) {
                if (s.get(FurnitureBlocks.LIT) != on) f.setLitQuietly(world, p, s, on);
                lamps++;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.offset(d);
                if (Math.abs(n.getX() - sw.getX()) > 8 || Math.abs(n.getZ() - sw.getZ()) > 8 || Math.abs(n.getY() - sw.getY()) > 3) continue;
                if (!seen.add(n.asLong()) || !open(world, n)) continue;
                // a one-block gap in a wall is a doorway: the next room has its own switch
                if (d.getAxis().isHorizontal() && !open(world, n.offset(d.rotateYClockwise())) && !open(world, n.offset(d.rotateYCounterclockwise())))
                    continue;
                queue.add(n);
            }
        }
        return lamps;
    }

    /** Air, or something the light passes in a room: our furniture, beds and curtains, anything you walk through. */
    private static boolean open(World world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.getBlock() instanceof FurnitureBlocks.Furniture || s.getBlock() instanceof FurnitureBlocks.Part
                || s.getBlock() instanceof FurnitureBlocks.Curtain || s.getBlock() instanceof FurnitureBlocks.Bed
                || s.getCollisionShape(world, p).isEmpty();
    }

    // ---------------------------------------------------------------- effects

    private static void burst(ServerWorld w, ParticleEffect type, Vec3d at, int count, double spread) {
        w.spawnParticles(type, at.x, at.y, at.z, count, spread, spread * 0.5, spread, 0.02);
    }

    /** Where a piece's front middle is, {@code up} pixels high and {@code in} pixels back from its front edge. */
    private static Vec3d front(BlockPos pos, Direction facing, double up, double in) {
        Vec3d c = Vec3d.ofBottomCenter(pos);
        double out = 0.5 - in / 16;
        return c.add(facing.getOffsetX() * out, up / 16, facing.getOffsetZ() * out);
    }

    private static void tap(ServerWorld w, BlockPos pos, Direction facing, String id) {
        // where each tap's spout ends, in the model's pixels: height, depth from the front
        Vec3d spout = switch (id) {
            case "apt_washbasin" -> front(pos, facing, 15, 12);
            case "apt_bathtub" -> front(pos, facing, 14, 3);
            default -> front(pos, facing, 17.5, 9.5);
        };
        w.playSound(null, pos, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.6f, 1.6f);
        later(w, 25, () -> w.playSound(null, pos, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.5f, 1.7f));
        for (int i = 0; i < 12; i++)
            later(w, i * 5, () -> {
                w.spawnParticles(ParticleTypes.FALLING_WATER, spout.x, spout.y, spout.z, 6, 0.03, 0.02, 0.03, 0);
                w.spawnParticles(ParticleTypes.SPLASH, spout.x, spout.y - 0.25, spout.z, 4, 0.1, 0.01, 0.1, 0.02);
            });
    }

    private static void flush(ServerWorld w, BlockPos pos) {
        w.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 0.8f, 0.7f);
        later(w, 6, () -> w.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundCategory.BLOCKS, 0.7f, 1.2f));
        later(w, 30, () -> w.playSound(null, pos, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.4f, 1.8f));
        for (int i = 0; i < 6; i++)
            later(w, i * 4, () -> burst(w, ParticleTypes.BUBBLE_POP, Vec3d.ofBottomCenter(pos).add(0, 0.45, 0), 5, 0.12));
    }

    private static void stove(ServerWorld w, BlockPos pos, boolean kettle) {
        w.playSound(null, pos, SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 0.6f, 1.2f);
        Vec3d top = Vec3d.ofBottomCenter(pos).add(0, 15.2 / 16, 0);
        for (int i = 0; i < 8; i++)
            later(w, 2 + i * 5, () -> {
                for (double dx : new double[] {-0.22, 0.22}) for (double dz : new double[] {-0.22, 0.22})
                    w.spawnParticles(ParticleTypes.SMALL_FLAME, top.x + dx, top.y, top.z + dz, 1, 0.03, 0, 0.03, 0.002);
            });
        if (!kettle) return;
        // the kettle comes to the boil: steam from the spout, then the whistle rising
        for (int i = 0; i < 10; i++)
            later(w, 30 + i * 4, () -> w.spawnParticles(ParticleTypes.WHITE_SMOKE, top.x + 0.12, top.y + 0.42, top.z, 2, 0.02, 0.02, 0.02, 0.01));
        int[] rise = {12, 14, 16, 17, 19, 19, 19};
        for (int i = 0; i < rise.length; i++) {
            int n = rise[i];
            later(w, 40 + i * 4, () -> note(w, pos.up(), SoundEvents.BLOCK_NOTE_BLOCK_FLUTE, n, 0.6f));
        }
    }

    private static void piano(ServerWorld w, BlockPos pos, Direction facing, Vec3d hit) {
        // the keyboard runs across the front, low notes on the player's left; two octaves over the piece's width
        Direction right = facing.rotateYCounterclockwise();
        BlockState state = w.getBlockState(pos);
        int width = state.getBlock() instanceof FurnitureBlocks.Furniture f ? Math.max(1, f.f.cells().length) : 1;
        Vec3d c = Vec3d.ofCenter(pos);
        double along = (hit.x - c.x) * right.getOffsetX() + (hit.z - c.z) * right.getOffsetZ(); // -0.5 .. width-0.5 to the right
        double t = Math.max(0, Math.min(1, (along + 0.5) / width));
        int n = (int) Math.round(t * 24);
        note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_HARP, n, 0.9f);
        w.spawnParticles(ParticleTypes.NOTE, hit.x, pos.getY() + 1.2, hit.z, 0, n / 24.0, 0, 0, 1);
    }

    /** «Подмосковные вечера» on the vibraphone: Радио «Маяк»'s call sign. */
    private static void mayak(ServerWorld w, BlockPos pos) {
        int[][] tune = {{3, 0}, {6, 6}, {10, 6}, {6, 6}, {8, 6}, {6, 6}, {5, 12}, {10, 6}, {8, 6}, {3, 6}};
        play(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, tune, 0.8f);
    }

    private static void switchedOn(ServerWorld w, BlockPos pos, String id, Direction facing) {
        switch (id) {
            case "apt_radiola" -> later(w, 10, () -> mayak(w, pos));
            case "apt_tv" -> {
                // the warm-up hum, then the time signal pips
                w.playSound(null, pos, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.25f, 2f);
                play(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_BIT, new int[][] {{18, 20}, {18, 10}, {18, 10}, {18, 10}, {18, 10}, {22, 10}}, 0.5f);
            }
            case "apt_gas_heater" -> {
                w.playSound(null, pos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.5f, 1.4f);
                Vec3d flame = front(pos, facing, 3, 4);
                for (int i = 0; i < 6; i++)
                    later(w, i * 3, () -> w.spawnParticles(ParticleTypes.FLAME, flame.x, flame.y, flame.z, 3, 0.08, 0.02, 0.02, 0.005));
            }
            default -> { }
        }
    }

    private static void phone(ServerWorld w, BlockPos pos) {
        // the dial turns back with its clicks for each digit, then the long tone in the receiver
        int t = 0;
        for (int digit : new int[] {2, 7, 4, 1, 5}) {
            for (int i = 0; i < digit; i++) { later(w, t, () -> note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 20, 0.4f)); t += 2; }
            t += 8;
        }
        for (int i = 0; i < 3; i++) later(w, t + i * 24, () -> note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_BIT, 9, 0.35f));
    }

    private static void clock(ServerWorld w, BlockPos pos, PlayerEntity player) {
        long t = w.getTimeOfDay() % 24000;
        int h = (int) ((t / 1000 + 6) % 24), m = (int) (t % 1000 * 60 / 1000);
        player.sendMessage(Text.translatable("mobtalk.furniture.clock", String.format("%d:%02d", h, m)), true);
        for (int i = 0; i < 4; i++) later(w, i * 10, () -> note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 6, 0.3f));
    }

    private static String meter(ServerWorld w, BlockPos pos) {
        long base = Math.floorMod(pos.asLong() * 2654435761L, 60000L) + 1000;
        double kwh = base + w.getTime() / 1200.0;
        return String.format("%07.1f", kwh);
    }

    private static void sewing(ServerWorld w, BlockPos pos) {
        for (int i = 0; i < 24; i++) later(w, i * 2, () -> note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 22, 0.35f));
        w.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.15f, 2f);
    }

    private static final Set<String> BUSY = new HashSet<>();

    private static void wash(ServerWorld w, BlockPos pos, String id) {
        String key = w.getRegistryKey().getValue() + "@" + pos.asLong();
        if (!BUSY.add(key)) return;
        Vec3d drum = Vec3d.ofBottomCenter(pos).add(0, id.equals("apt_washer_malyutka") ? 0.8 : 0.55, 0);
        w.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 0.6f, 1.2f);
        int steps = 10;
        for (int i = 0; i < steps; i++) {
            int k = i;
            later(w, 10 + i * 12, () -> {
                w.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.BLOCKS, 0.5f, k < steps - 3 ? 1f : 1.6f);
                burst(w, ParticleTypes.BUBBLE_POP, drum, 6, 0.2);
                if (k % 3 == 0) burst(w, ParticleTypes.SPLASH, drum.add(0, 0.3, 0), 6, 0.2);
            });
        }
        later(w, 10 + steps * 12, () -> {
            note(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL, 15, 0.6f);
            BUSY.remove(key);
        });
    }

    /** «Спят усталые игрушки» on the music box hung over the crib. */
    private static void lullaby(ServerWorld w, BlockPos pos) {
        int[][] tune = {{13, 0}, {10, 6}, {10, 6}, {13, 6}, {10, 6}, {10, 6}, {15, 6}, {13, 6}, {11, 6}, {10, 6}, {8, 12}};
        play(w, pos, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, tune, 0.5f);
    }

    private static void steam(ServerWorld w, BlockPos pos) {
        w.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.3f, 1.6f);
        Vec3d iron = Vec3d.ofBottomCenter(pos).add(0, 1.0, 0);
        w.spawnParticles(ParticleTypes.WHITE_SMOKE, iron.x, iron.y, iron.z, 8, 0.08, 0.05, 0.08, 0.01);
    }

    // ---------------------------------------------------------------- notes and the scheduler

    /** A note block note: {@code n} semitones above F♯3, 0..24. */
    private static void note(ServerWorld w, BlockPos pos, RegistryEntry<SoundEvent> instrument, int n, float volume) {
        float pitch = (float) Math.pow(2, (n - 12) / 12.0);
        w.playSound(null, pos, instrument.value(), SoundCategory.RECORDS, volume, pitch);
    }

    /** A tune: {semitone, ticks after the previous note}. */
    private static void play(ServerWorld w, BlockPos pos, RegistryEntry<SoundEvent> instrument, int[][] tune, float volume) {
        int t = 0;
        for (int[] step : tune) {
            t += step[1];
            int n = step[0];
            later(w, t, () -> {
                note(w, pos, instrument, n, volume);
                w.spawnParticles(ParticleTypes.NOTE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 0, n / 24.0, 0, 0, 1);
            });
        }
    }

    private record Job(ServerWorld world, long at, Runnable run) { }

    private static final List<Job> JOBS = new ArrayList<>();
    private static boolean ticking;

    private static void later(ServerWorld w, int ticks, Runnable run) {
        if (ticks <= 0) { run.run(); return; }
        JOBS.add(new Job(w, w.getServer().getTicks() + ticks, run));
    }

    static void register() {
        if (ticking) return;
        ticking = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (JOBS.isEmpty()) return;
            int now = server.getTicks();
            List<Job> due = new ArrayList<>();
            JOBS.removeIf(j -> { if (j.at <= now) { due.add(j); return true; } return false; });
            due.forEach(j -> j.run.run());
        });
    }
}
