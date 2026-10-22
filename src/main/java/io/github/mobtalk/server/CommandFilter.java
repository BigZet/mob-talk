package io.github.mobtalk.server;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.github.mobtalk.MobTalkMod;
import net.minecraft.server.command.ServerCommandSource;
import java.util.Locale;

/** Фильтр команд модов по уровням ({@link PermissionsImpl}): прячет корни и подкоманды из дерева, которое уходит игроку, и отказывает
 * при выполнении понятной строкой. Зовётся из {@code CommandFilterMixin} ({@code CommandManager.makeTreeForSource} и {@code execute}),
 * поэтому видит команды всех модов, когда бы они ни зарегистрировались. Ванильные команды проходят как есть. */
public final class CommandFilter {
    private CommandFilter() { }

    /** Корень и первая подкоманда строки команды (без «/»); подкоманды может не быть. */
    public static String[] split(String command) {
        String line = command == null ? "" : command.strip();
        if (line.startsWith("/")) line = line.substring(1);
        String[] words = line.split("\\s+", 3);
        String root = words.length > 0 ? words[0].toLowerCase(Locale.ROOT) : "";
        String sub = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : null;
        return new String[]{root, sub};
    }

    /** Почему источнику нельзя эту команду, или null, если можно. */
    public static String refusal(ServerCommandSource source, String command) {
        String[] parts = split(command);
        if (!PermissionsImpl.modRoot(parts[0])) return null;
        Permissions permissions = MobTalkMod.permissions();
        if (permissions instanceof PermissionsImpl impl) return impl.mayRun(source, parts[0], parts[1]) ? null : impl.refusal(parts[0], parts[1]);
        return permissions.mayRun(source, parts[0]) ? null : PermissionsImpl.REFUSAL;
    }

    /** Показывать ли узел {@code child} под {@code parent} в дереве команд этого источника: корень мода — по уровню корня,
     * подкоманда корня мода — по уровню «корень подкоманда». Глубже не фильтруем. */
    public static boolean visible(ServerCommandSource source, CommandNode<ServerCommandSource> parent, CommandNode<ServerCommandSource> child) {
        if (!(child instanceof LiteralCommandNode<ServerCommandSource>)) return true;
        Permissions permissions = MobTalkMod.permissions();
        if (parent instanceof RootCommandNode<ServerCommandSource>) {
            if (!PermissionsImpl.modRoot(child.getName())) return true;
            if (!(permissions instanceof PermissionsImpl impl)) return permissions.mayRun(source, child.getName());
            // Корень остаётся в дереве, если разрешена хоть одна его подкоманда (у стримера «divineblock list» под админским корнем).
            if (impl.mayRun(source, child.getName(), null)) return true;
            for (CommandNode<ServerCommandSource> sub : child.getChildren())
                if (sub instanceof LiteralCommandNode<ServerCommandSource> && impl.mayRun(source, child.getName(), sub.getName())) return true;
            return false;
        }
        if (parent instanceof LiteralCommandNode<ServerCommandSource> && PermissionsImpl.modRoot(parent.getName()) && permissions instanceof PermissionsImpl impl)
            return impl.mayRun(source, parent.getName(), child.getName());
        return true;
    }
}
