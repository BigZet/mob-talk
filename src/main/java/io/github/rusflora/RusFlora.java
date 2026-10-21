package io.github.rusflora;

import io.github.rusflora.hogweed.Hogweed;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the plants of the Russian middle belt: Sosnowsky's hogweed. Everything lives on the common side, so the mod
 * works on a server as well. */
public final class RusFlora implements ModInitializer {
    public static final String ID = "rusflora";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static Identifier id(String path) { return Identifier.of(ID, path); }

    @Override public void onInitialize() {
        Hogweed.register();
        LOG.info("Русская флора загружена");
    }
}
