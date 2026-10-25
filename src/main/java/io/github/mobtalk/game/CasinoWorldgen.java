package io.github.mobtalk.game;

import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.game.mixin.CasinoPoolAccessor;
import io.github.mobtalk.server.ServerConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.Identifier;
import java.util.List;

/** The casino joins the house pools of the five vanilla village kinds (data/mobtalk/structure/casino, from tools/casino/building.py),
 * with the weight from the settings (vanilla houses weigh 2 to 4 each): a street that draws it builds the casino with its door on the
 * street, like any house. Applied when the server starts, before any chunk is generated; a pool is changed once per server. */
public final class CasinoWorldgen {
    private CasinoWorldgen() { }
    public static final List<String> KINDS = List.of("plains", "desert", "savanna", "snowy", "taiga");

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> apply(server.getRegistryManager(), ServerConfig.get()));
    }

    static void apply(DynamicRegistryManager registries, MobTalkConfig config) {
        int weight = config.casino ? config.casinoWeight : 0;
        if (weight <= 0) return;
        Registry<StructurePool> pools = registries.get(RegistryKeys.TEMPLATE_POOL);
        int done = 0;
        for (String kind : KINDS) {
            StructurePool pool = pools.get(Identifier.ofVanilla("village/" + kind + "/houses"));
            if (pool == null) continue;
            ObjectArrayList<StructurePoolElement> elements = ((CasinoPoolAccessor) pool).mobtalk$casinoElements();
            String location = "mobtalk:casino/" + kind;
            if (elements.stream().anyMatch(e -> e.toString().contains(location))) continue;
            StructurePoolElement casino = StructurePoolElement.ofLegacySingle(location).apply(StructurePool.Projection.RIGID);
            for (int i = 0; i < weight; i++) elements.add(casino);
            done++;
        }
        MobTalkMod.LOGGER.info("Mob Talk: казино добавлено в {} видов деревень, вес {}", done, weight);
    }
}
