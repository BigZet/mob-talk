package io.github.divineblock.game;

import com.google.gson.Gson;
import io.github.divineblock.core.GodLedger;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/** The god's memory saved with the world (overworld data/divineblock.dat): the ledger, the lingering curses and the
 * eternal bans, each as one JSON string. */
public final class DivineState extends PersistentState {
    private static final Type<DivineState> TYPE = new Type<>(DivineState::new, DivineState::read, null);
    private static final Gson GSON = new Gson();
    public GodLedger ledger = new GodLedger();
    public Curses.Book curses = new Curses.Book();
    /** Player UUID → the sentence of the eternal ban (see {@link Banishment}). */
    public Map<String, Banishment.Ban> banned = new HashMap<>();

    public static DivineState of(MinecraftServer server) {
        return server.getWorld(World.OVERWORLD).getPersistentStateManager().getOrCreate(TYPE, "divineblock");
    }

    private static DivineState read(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        DivineState state = new DivineState();
        state.ledger = GodLedger.fromJson(nbt.getString("ledger"));
        try {
            Curses.Book book = GSON.fromJson(nbt.getString("curses"), Curses.Book.class);
            if (book != null && book.active != null) { book.active.removeIf(curse -> curse == null || curse.type == null); state.curses = book; }
        } catch (RuntimeException broken) { state.curses = new Curses.Book(); }
        try {
            Banishment.Book bans = GSON.fromJson(nbt.getString("banned"), Banishment.Book.class);
            if (bans != null && bans.souls != null) state.banned = bans.souls;
        } catch (RuntimeException broken) { state.banned = new HashMap<>(); }
        return state;
    }

    @Override public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putString("ledger", ledger.toJson());
        nbt.putString("curses", GSON.toJson(curses));
        Banishment.Book bans = new Banishment.Book();
        bans.souls = banned;
        nbt.putString("banned", GSON.toJson(bans));
        return nbt;
    }
}
