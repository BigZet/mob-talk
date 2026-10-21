package io.github.rusflora.client;

import net.fabricmc.api.ClientModInitializer;

/** Client side: the hogweed's render layers and its self-test. */
public final class RusFloraClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        HogweedClient.register();
    }
}
