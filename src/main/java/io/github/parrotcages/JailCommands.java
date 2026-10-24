package io.github.parrotcages;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * {@code /parrotcages jail <попугай> extend <мин> | forever | release} — кнопки строки тюремщику — и
 * {@code /parrotcages jail list}. Право — у стримера, администратора и того, кто посадил птицу.
 */
final class JailCommands {
	private JailCommands() { }

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
				CommandManager.literal("parrotcages").then(CommandManager.literal("jail")
						.then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
						.then(CommandManager.argument("parrot", StringArgumentType.word())
								.then(CommandManager.literal("extend")
										.then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 60))
												.executes(ctx -> extend(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
								.then(CommandManager.literal("forever").executes(ctx -> extend(ctx, 0)))
								.then(CommandManager.literal("release").executes(JailCommands::release))))));
	}

	private static UUID parrot(CommandContext<ServerCommandSource> ctx) {
		try { return UUID.fromString(StringArgumentType.getString(ctx, "parrot")); }
		catch (IllegalArgumentException invalid) { return null; }
	}

	/** Узник и право на него; null с ошибкой в чат, если нельзя. */
	private static ParrotCageBlockEntity allowed(CommandContext<ServerCommandSource> ctx, UUID parrot) {
		ServerCommandSource source = ctx.getSource();
		ParrotCageBlockEntity cage = parrot == null ? null : Jail.cageOf(parrot);
		if (cage == null) { source.sendError(Text.literal("Этот попугай уже не в тюрьме")); return null; }
		ServerPlayerEntity player = source.getPlayer();
		boolean console = player == null && source.hasPermissionLevel(3);
		if (!console && (player == null || !cage.mayFree(player))) {
			source.sendError(Text.translatable("parrotcages.message.streamer_lock"));
			return null;
		}
		return cage;
	}

	private static int extend(CommandContext<ServerCommandSource> ctx, int minutes) {
		UUID parrot = parrot(ctx);
		if (allowed(ctx, parrot) == null) return 0;
		Jail.extend(parrot, minutes);
		ParrotCageBlockEntity cage = Jail.cageOf(parrot);
		long now = cage == null || cage.getWorld() == null ? 0 : cage.getWorld().getTime();
		String left = cage == null ? "" : Jail.clock(cage.releaseAt(), now);
		ctx.getSource().sendFeedback(() -> Text.literal(minutes == 0 ? "Срок теперь бессрочный" : "Срок продлён, осталось " + left), false);
		return 1;
	}

	private static int release(CommandContext<ServerCommandSource> ctx) {
		UUID parrot = parrot(ctx);
		if (allowed(ctx, parrot) == null) return 0;
		boolean done = Jail.release(parrot, "hands", ctx.getSource().getPlayer());
		ctx.getSource().sendFeedback(() -> Text.literal(done ? "Попугай отпущен" : "Попугая сейчас нет у клетки"), false);
		return done ? 1 : 0;
	}

	private static int list(ServerCommandSource source) {
		var prisoners = Jail.prisoners();
		if (prisoners.isEmpty()) { source.sendFeedback(() -> Text.literal("В клетках нет попугаев зрителей"), false); return 1; }
		for (Jail.Prisoner prisoner : prisoners) {
			ParrotCageBlockEntity cage = Jail.cageOf(prisoner.parrot());
			long now = cage == null || cage.getWorld() == null ? 0 : cage.getWorld().getTime();
			source.sendFeedback(() -> Text.literal(prisoner.name() + " · " + Jail.clock(prisoner.releaseAt(), now)
					+ (prisoner.jailerName().isBlank() ? "" : " · посадил " + prisoner.jailerName())), false);
		}
		return prisoners.size();
	}
}
