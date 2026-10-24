package io.github.parrotcages;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Кто распоряжается попугаями-зрителями в клетках: стример и администратор. Роли приходят тегами игрока от
 * Mob Talk ({@link #STREAMER_TAG}, {@link #ADMIN_TAG}); без него стример — тот, кого назначил Viewer Parrots
 * ({@code /vp setstreamer}, общий объект {@link #VP_STREAMER}) или хозяин одиночного мира, администратор — оператор
 * уровня 3 и выше. Жёсткой зависимости от модов нет: только строки тегов и общий реестр Fabric.
 */
public final class Roles {
	public static final String STREAMER_TAG = "mobtalk.role.streamer";
	public static final String ADMIN_TAG = "mobtalk.role.admin";
	/** {@code Supplier<UUID>} от Viewer Parrots: назначенный стример мира или null. */
	public static final String VP_STREAMER = "viewerparrots:streamer";
	public static final String VIEWER_PARROT_TYPE = "viewerparrots:viewer_parrot";

	private Roles() { }

	public static boolean isViewerParrot(Entity entity) {
		return VIEWER_PARROT_TYPE.equals(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
	}

	public static boolean isAdmin(ServerPlayerEntity player) {
		return player.getCommandTags().contains(ADMIN_TAG) || player.hasPermissionLevel(3);
	}

	public static boolean isStreamer(ServerPlayerEntity player) {
		if (player.getCommandTags().contains(STREAMER_TAG)) return true;
		UUID assigned = vpStreamer();
		if (assigned != null) return assigned.equals(player.getUuid());
		MinecraftServer server = player.getServer();
		return server != null && !server.isDedicated() && server.isHost(player.getGameProfile());
	}

	/** Может сажать попугаев-зрителей, выпускать чужих узников и ломать клетку с узником. */
	public static boolean warden(ServerPlayerEntity player) { return isStreamer(player) || isAdmin(player); }

	/** Хоть один стример в игре: без него бессрочный срок со временем кончается. */
	public static boolean streamerOnline(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) if (isStreamer(player)) return true;
		return false;
	}

	@SuppressWarnings("unchecked")
	private static UUID vpStreamer() {
		Object shared = FabricLoader.getInstance().getObjectShare().get(VP_STREAMER);
		if (!(shared instanceof Supplier<?> supplier)) return null;
		try { return ((Supplier<Object>) supplier).get() instanceof UUID uuid ? uuid : null; }
		catch (RuntimeException failure) { return null; }
	}
}
