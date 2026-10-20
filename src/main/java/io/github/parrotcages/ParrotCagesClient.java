package io.github.parrotcages;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public final class ParrotCagesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRendererFactories.register(ParrotCages.CAGE_BLOCK_ENTITY, CageRenderer::new);
		for (ParrotCageBlock cage : ParrotCages.cages()) {
			// Предмет — та же клетка, уменьшенная до одного блока.
			BuiltinItemRendererRegistry.INSTANCE.register(cage.asItem(), (stack, mode, matrices, vertices, light, overlay) -> {
				float scale = 1f / Math.max(cage.width(), cage.height());
				matrices.push();
				matrices.translate((1 - cage.width() * scale) / 2, 0, (1 - cage.width() * scale) / 2);
				matrices.scale(scale, scale, scale);
				CageRenderer.draw(cage, matrices, vertices, light, overlay);
				matrices.pop();
			});
		}
		SelfTest.install();
	}
}
