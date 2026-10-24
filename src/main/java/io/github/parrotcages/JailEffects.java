package io.github.parrotcages;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/** Звуки, частицы и заголовки тюрьмы — только ванильные ресурсы. */
public final class JailEffects {
	private static final double TITLE_RANGE = 32;
	private static final ParticleEffect FEATHERS = new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.FEATHER));

	private JailEffects() { }

	/** Арест: дверь лязгнула, цепь, искры и «АРЕСТОВАН» на 3 секунды у всех рядом. */
	static void jailed(ServerWorld world, Vec3d at) {
		sound(world, at, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 1f, 0.9f);
		sound(world, at, SoundEvents.BLOCK_CHAIN_PLACE, 1f, 1f);
		world.spawnParticles(ParticleTypes.CRIT, at.x, at.y + 0.5, at.z, 20, 0.4, 0.4, 0.4, 0.2);
		title(world, at, Text.literal("АРЕСТОВАН").formatted(Formatting.RED, Formatting.BOLD));
	}

	/** Отказ: цепь звякнула, перья полетели. */
	static void refused(ServerWorld world, Vec3d at) {
		sound(world, at, SoundEvents.BLOCK_CHAIN_HIT, 1f, 1f);
		world.spawnParticles(FEATHERS, at.x, at.y + 0.5, at.z, 8, 0.25, 0.25, 0.25, 0.05);
	}

	/** Попытка вора: скрипнул железный люк. */
	static void escapeAttempt(ServerWorld world, Vec3d at) { sound(world, at, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 1.2f); }

	static void amnesty(ServerWorld world, Vec3d at) {
		sound(world, at, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
		world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.5, at.z, 20, 0.5, 0.5, 0.5, 0.1);
		title(world, at, Text.literal("АМНИСТИЯ").formatted(Formatting.GREEN, Formatting.BOLD));
	}

	/** Срок кончился (или клетку открыли): цепь лопнула, перья. */
	static void freed(ServerWorld world, Vec3d at) {
		sound(world, at, SoundEvents.BLOCK_CHAIN_BREAK, 1f, 1f);
		world.spawnParticles(FEATHERS, at.x, at.y + 0.5, at.z, 12, 0.3, 0.3, 0.3, 0.08);
	}

	private static void sound(ServerWorld world, Vec3d at, SoundEvent sound, float volume, float pitch) {
		world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.BLOCKS, volume, pitch);
	}

	private static void title(ServerWorld world, Vec3d at, Text text) {
		for (ServerPlayerEntity player : world.getPlayers(p -> p.squaredDistanceTo(at) <= TITLE_RANGE * TITLE_RANGE)) {
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 50, 5));
			player.networkHandler.sendPacket(new TitleS2CPacket(text));
		}
	}
}
