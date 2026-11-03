package io.github.mobtalk.client;

import io.github.mobtalk.net.MobLabelS2C;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Родные игрока (жена, муж, дети) на карте JourneyMap: сердечко с подписью «жена Светлана». Загружает его только JourneyMap
 * (точка входа {@code journeymap}); без JourneyMap класс не трогается. Место — где родню видели последний раз: вне прогрузки
 * метка остаётся там, где её видели. Только клиент. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapKin implements IClientPlugin {
    private static final Identifier ICON = Identifier.of("mobtalk", "textures/map/kin.png");
    private static final int PERIOD = 20, RANGE = 128;
    private record Seen(String text, BlockPos pos, RegistryKey<World> world) { }
    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<UUID, MarkerOverlay> shown = new HashMap<>();
    private IClientAPI api;
    private long ticks;

    @Override public String getModId() { return "mobtalk"; }

    @Override public void initialize(IClientAPI api) {
        this.api = api;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (++ticks % PERIOD != 0) return;
        MobTalkClient owner = MobTalkClient.active;
        if (owner == null || client.world == null || client.player == null) {
            if (!shown.isEmpty()) { api.removeAll(getModId()); shown.clear(); seen.clear(); }
            return;
        }
        for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class, client.player.getBoundingBox().expand(RANGE), Entity::isAlive)) {
            MobLabelS2C label = owner.views.label(mob.getUuid());
            if (label == null || label.kin().isBlank() || label.name().isBlank()) continue;
            seen.put(mob.getUuid(), new Seen(label.kin() + " " + label.name(), mob.getBlockPos(), client.world.getRegistryKey()));
        }
        for (Map.Entry<UUID, Seen> entry : seen.entrySet()) {
            Seen s = entry.getValue();
            MarkerOverlay marker = shown.get(entry.getKey());
            if (marker != null && marker.getPoint().equals(s.pos()) && s.text().equals(marker.getLabel()) && s.world().equals(marker.getDimension())) continue;
            if (marker != null) api.remove(marker);
            marker = new MarkerOverlay(getModId(), s.pos(), new MapImage(ICON, 16, 16).centerAnchors().setDisplayWidth(14).setDisplayHeight(14));
            marker.setDimension(s.world()).setLabel(s.text()).setTitle(s.text()).setOverlayGroupName("Семья");
            try { api.show(marker); shown.put(entry.getKey(), marker); } catch (Exception failed) { shown.remove(entry.getKey()); }
        }
    }
}
