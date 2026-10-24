package io.github.divineblock.weapon;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.Fx;
import io.github.divineblock.game.Protection;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

/** Трубы Иерихона (Joshua 6:20): blow for three seconds (the sound swells, rings run out), then the wall before the player,
 * seven blocks wide, five high and three deep, comes down as falling blocks. Only wall blocks fall (the block tag
 * {@code divineblock:jericho_breakable}: stone, bricks, planks, glass, clay and the like; never ores, chests, obsidian or
 * the mod's own blocks), only with {@code mobGriefing}, with «Трубы Иерихона ломают блоки» and «Беречь блоки» allowing, and
 * never in a church or at an altar ({@link Protection}). Otherwise the sound and the shove still come, and the player is
 * told why the wall stood. Hostiles in the band are thrown back. A minute between blasts; ten blasts in the horn. */
public final class JerichoHornItem extends Item {
    public static final TagKey<net.minecraft.block.Block> BREAKABLE = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DivineBlockMod.ID, "jericho_breakable"));
    static final int BLOW_TICKS = 60, COOLDOWN = 1200;
    static final int WIDTH = 7, HEIGHT = 5, DEPTH = 3;

    public JerichoHornItem(Settings settings) { super(settings); }

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.TOOT_HORN; }
    @Override public int getMaxUseTime(ItemStack stack, LivingEntity user) { return BLOW_TICKS; }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!DivineWeapons.allowed(player, WeaponKind.JERICHO_HORN)) return TypedActionResult.fail(stack);
        if (player.getItemCooldownManager().isCoolingDown(this)) return TypedActionResult.pass(stack);
        player.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        int blown = BLOW_TICKS - remainingUseTicks;
        if (!(world instanceof ServerWorld server)) return;
        if (blown % 20 == 1) server.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.GOAT_HORN_SOUNDS.get(Math.min(3, blown / 20)), SoundCategory.PLAYERS, 2f + blown / 30f, 0.7f + blown / 100f);
        if (blown % 10 == 0) Fx.ring(server, user.getPos(), Fx.GOLD, 2f + blown / 8f);
    }

    @Override public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!(user instanceof ServerPlayerEntity player) || !(world instanceof ServerWorld server)) return stack;
        blast(server, player);
        stack.damage(1, player, player.getActiveHand() == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        player.getItemCooldownManager().set(this, COOLDOWN);
        return stack;
    }

    /** The shout: the wall before the player falls, if anything lets it, and the foes in the band are thrown back. Returns
     * how many blocks fell. */
    public static int blast(ServerWorld world, ServerPlayerEntity player) {
        Direction facing = player.getHorizontalFacing();
        Vec3d centre = player.getPos().add(Vec3d.of(facing.getVector()).multiply(2.5));
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GOAT_HORN_SOUNDS.get(4), SoundCategory.PLAYERS, 3f, 0.8f);
        Fx.shake(world, centre, 0.8f, 30);
        Fx.ring(world, player.getPos(), Fx.GOLD, 8f);
        Fx.flash(world, player.getPos(), Fx.GOLD, 0.3f);
        for (HostileEntity foe : world.getEntitiesByClass(HostileEntity.class, new Box(BlockPos.ofFloored(centre)).expand(5), LivingEntity::isAlive))
            SacredBoss.push(foe, player.getPos(), 1.6, 0.5);
        DivineConfig config = DivineBlockMod.config();
        String why = null;
        if (!config.jerichoBreaksBlocks) why = "в настройках трубы не ломают стен";
        else if (config.protectBlocks) why = "включено «Беречь блоки»";
        else if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) why = "mobGriefing выключен";
        if (why != null) { player.sendMessage(Text.literal("Стена устояла: " + why).formatted(Formatting.GOLD), true); return 0; }
        List<BlockPos> wall = wall(world, player, facing);
        if (wall.isEmpty()) { player.sendMessage(Text.literal("Перед тобой нет стены, которая пала бы").formatted(Formatting.GOLD), true); return 0; }
        int fell = 0;
        for (BlockPos pos : wall) {
            if (Protection.spares(world, player, null, pos, config)) continue;
            BlockState state = world.getBlockState(pos);
            world.removeBlock(pos, false);
            FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, pos, state);
            falling.dropItem = false;
            falling.setHurtEntities(1f, 10);
            falling.setVelocity(Vec3d.of(facing.getVector()).multiply(0.15 + world.getRandom().nextDouble() * 0.2).add(0, 0.1, 0));
            fell++;
        }
        if (fell == 0) { player.sendMessage(Text.literal("Стена под защитой бога и устояла").formatted(Formatting.GOLD), true); return 0; }
        Fx.smoke(world, centre.add(0, 1, 0), 0xFF8A8278, 3.5f);
        Fx.impact(world, centre, Fx.GOLD, 2f);
        return fell;
    }

    /** The wall's blocks: a band WIDTH across and DEPTH deep before the player, HEIGHT high from his feet, only the breakable. */
    static List<BlockPos> wall(ServerWorld world, PlayerEntity player, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos feet = player.getBlockPos();
        Direction side = facing.rotateYClockwise();
        for (int depth = 1; depth <= DEPTH + 1; depth++)
            for (int across = -WIDTH / 2; across <= WIDTH / 2; across++)
                for (int up = 0; up < HEIGHT; up++) {
                    BlockPos pos = feet.offset(facing, depth).offset(side, across).up(up);
                    if (world.getBlockState(pos).isIn(BREAKABLE)) out.add(pos);
                }
        return out;
    }
}
