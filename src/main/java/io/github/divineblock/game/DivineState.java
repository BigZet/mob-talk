package io.github.divineblock.game;

import io.github.divineblock.core.GodLedger;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/** The god's memory saved with the world (overworld data/divineblock.dat) as one JSON string. */
public final class DivineState extends PersistentState {
    private static final Type<DivineState> TYPE = new Type<>(DivineState::new, DivineState::read, null);
    public GodLedger ledger = new GodLedger();

    public static DivineState of(MinecraftServer server) {
        return server.getWorld(World.OVERWORLD).getPersistentStateManager().getOrCreate(TYPE, "divineblock");
    }

    private static DivineState read(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        DivineState state = new DivineState();
        state.ledger = GodLedger.fromJson(nbt.getString("ledger"));
        return state;
    }

    @Override public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putString("ledger", ledger.toJson());
        return nbt;
    }
}
