package io.github.parrotcages;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рисует птичью клетку целиком из угловой части: круглый поддон, тонкие вертикальные прутья,
 * купол из дуг, обручи, жёрдочку внутри и кольцо-подвес наверху. Геометрия строится один раз
 * на каждую форму и кешируется.
 */
public final class CageRenderer implements BlockEntityRenderer<ParrotCageBlockEntity> {
	private enum Part { WIRE, BASE, PERCH }
	/** Отрезок прута от {@code a} до {@code b} квадратного сечения {@code size}. */
	private record Beam(Vector3f a, Vector3f b, float size, Part part) { }
	/** Многоугольная «таблетка»: поддон. */
	private record Disc(float cx, float cz, float y0, float y1, float radius, int sides) { }
	private record Geometry(List<Beam> beams, List<Disc> discs) { }

	private static final Map<CageForm, Geometry> CACHE = new ConcurrentHashMap<>();

	public CageRenderer(BlockEntityRendererFactory.Context context) { }

	@Override
	public void render(ParrotCageBlockEntity cage, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertices, int light, int overlay) {
		if (!(cage.getCachedState().getBlock() instanceof ParrotCageBlock block)) return;
		draw(block, matrices, vertices, light, overlay);
	}

	@Override public boolean rendersOutsideBoundingBox(ParrotCageBlockEntity cage) { return true; }
	@Override public int getRenderDistance() { return 96; }

	/** Общая отрисовка для блока в мире и для предмета (его масштабирует вызывающий). */
	static void draw(ParrotCageBlock block, MatrixStack matrices, VertexConsumerProvider vertices, int light, int overlay) {
		Geometry geometry = CACHE.computeIfAbsent(block.form(), CageRenderer::build);
		var atlas = MinecraftClient.getInstance().getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
		Sprite metal = atlas.apply(ParrotCages.id(block.golden() ? "block/cage_gold" : "block/cage_iron"));
		Sprite base = atlas.apply(ParrotCages.id(block.golden() ? "block/cage_base_gold" : "block/cage_base_wood"));
		VertexConsumer buffer = vertices.getBuffer(RenderLayer.getEntityCutoutNoCull(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
		MatrixStack.Entry entry = matrices.peek();
		for (Disc disc : geometry.discs) disc(buffer, entry, disc, base, metal, light, overlay);
		for (Beam beam : geometry.beams) beam(buffer, entry, beam, beam.part == Part.WIRE ? metal : base, light, overlay);
	}

	// --- Построение формы ------------------------------------------------------

	private static Geometry build(CageForm form) {
		List<Beam> beams = new ArrayList<>();
		List<Disc> discs = new ArrayList<>();
		float cx = (float) form.centerX(), cz = (float) form.centerZ();
		float r = (float) form.radius(), floor = (float) form.floor();
		float wallTop = (float) form.wallTop(), apex = (float) form.apex();
		int width = form.width();
		float wire = width == 1 ? 0.022f : 0.027f;
		float hoop = wire * 1.7f;
		int count = width == 1 ? 14 : width == 2 ? 22 : 30;
		int ring = 48;

		// Поддон с бортиком и ободом.
		discs.add(new Disc(cx, cz, 0, floor * 0.55f, r + 0.04f, ring));
		discs.add(new Disc(cx, cz, floor * 0.55f, floor, r + 0.01f, ring));

		// Вертикальные прутья и дуги купола, сходящиеся к макушке.
		int arc = 10;
		for (int i = 0; i < count; i++) {
			double angle = Math.PI * 2 * i / count;
			float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
			beams.add(new Beam(new Vector3f(cx + dx * r, floor, cz + dz * r), new Vector3f(cx + dx * r, wallTop, cz + dz * r), wire, Part.WIRE));
			Vector3f previous = new Vector3f(cx + dx * r, wallTop, cz + dz * r);
			for (int k = 1; k <= arc; k++) {
				double theta = Math.PI / 2 * k / arc;
				float rr = (float) (r * Math.cos(theta)), y = (float) (wallTop + (apex - wallTop) * Math.sin(theta));
				Vector3f next = new Vector3f(cx + dx * rr, y, cz + dz * rr);
				beams.add(new Beam(previous, next, wire, Part.WIRE));
				previous = next;
			}
		}

		// Обручи: у поддона, по середине стенок, на переходе в купол и на куполе.
		hoop(beams, cx, cz, r, floor + 0.02f, ring, hoop);
		hoop(beams, cx, cz, r, floor + (wallTop - floor) * 0.5f, ring, wire * 1.2f);
		hoop(beams, cx, cz, r, wallTop, ring, hoop);
		for (double theta : new double[] {Math.PI / 6, Math.PI / 3}) {
			hoop(beams, cx, cz, (float) (r * Math.cos(theta)), (float) (wallTop + (apex - wallTop) * Math.sin(theta)), ring, wire * 1.2f);
		}

		// Макушка и кольцо-подвес.
		float knob = width == 1 ? 0.05f : 0.08f;
		beams.add(new Beam(new Vector3f(cx, apex - 0.01f, cz), new Vector3f(cx, apex + knob, cz), hoop * 1.6f, Part.WIRE));
		float loop = width == 1 ? 0.06f : 0.1f;
		float loopY = apex + knob + loop;
		int loopSides = 16;
		for (int i = 0; i < loopSides; i++) {
			double a0 = Math.PI * 2 * i / loopSides, a1 = Math.PI * 2 * (i + 1) / loopSides;
			beams.add(new Beam(new Vector3f(cx + (float) Math.cos(a0) * loop, loopY + (float) Math.sin(a0) * loop, cz),
					new Vector3f(cx + (float) Math.cos(a1) * loop, loopY + (float) Math.sin(a1) * loop, cz), wire * 1.3f, Part.WIRE));
		}

		// Жёрдочка поперёк клетки на трети высоты стенок.
		float perchY = floor + (wallTop - floor) * (width == 1 ? 0.62f : 0.45f);
		beams.add(new Beam(new Vector3f(cx - r, perchY, cz), new Vector3f(cx + r, perchY, cz), width == 1 ? 0.05f : 0.07f, Part.PERCH));

		// Дверца спереди (сторона −Z): рамка из двух горизонталей и петли.
		float doorHalf = (float) Math.PI / count * 1.5f;
		float doorBottom = floor + 0.06f, doorTop = floor + (wallTop - floor) * 0.7f;
		for (float y : new float[] {doorBottom, doorTop}) {
			double a0 = -Math.PI / 2 - doorHalf, a1 = -Math.PI / 2 + doorHalf;
			int steps = 4;
			for (int k = 0; k < steps; k++) {
				double p0 = a0 + (a1 - a0) * k / steps, p1 = a0 + (a1 - a0) * (k + 1) / steps;
				float rr = r + hoop;
				beams.add(new Beam(new Vector3f(cx + (float) Math.cos(p0) * rr, y, cz + (float) Math.sin(p0) * rr),
						new Vector3f(cx + (float) Math.cos(p1) * rr, y, cz + (float) Math.sin(p1) * rr), hoop, Part.WIRE));
			}
		}
		return new Geometry(beams, discs);
	}

	private static void hoop(List<Beam> beams, float cx, float cz, float radius, float y, int sides, float size) {
		for (int i = 0; i < sides; i++) {
			double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
			beams.add(new Beam(new Vector3f(cx + (float) Math.cos(a0) * radius, y, cz + (float) Math.sin(a0) * radius),
					new Vector3f(cx + (float) Math.cos(a1) * radius, y, cz + (float) Math.sin(a1) * radius), size, Part.WIRE));
		}
	}

	// --- Вершины ---------------------------------------------------------------

	private static void beam(VertexConsumer buffer, MatrixStack.Entry entry, Beam beam, Sprite sprite, int light, int overlay) {
		Vector3f dir = new Vector3f(beam.b).sub(beam.a);
		float length = dir.length();
		if (length < 1e-5f) return;
		dir.div(length);
		Vector3f helper = Math.abs(dir.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
		Vector3f u = new Vector3f(dir).cross(helper).normalize().mul(beam.size / 2);
		Vector3f v = new Vector3f(dir).cross(u).normalize().mul(beam.size / 2);
		Vector3f[] corners = {
				new Vector3f(u).add(v), new Vector3f(u).sub(v), new Vector3f(u).negate().sub(v), new Vector3f(u).negate().add(v)};
		float u0 = sprite.getFrameU(0), u1 = sprite.getFrameU(1);
		float v0 = sprite.getFrameV(0), v1 = sprite.getFrameV(Math.min(1, Math.max(0.15f, length)));
		for (int i = 0; i < 4; i++) {
			Vector3f c0 = corners[i], c1 = corners[(i + 1) % 4];
			Vector3f normal = new Vector3f(c0).add(c1).normalize();
			quad(buffer, entry, normal,
					new Vector3f(beam.a).add(c0), new Vector3f(beam.a).add(c1), new Vector3f(beam.b).add(c1), new Vector3f(beam.b).add(c0),
					u0, v0, u1, v1, light, overlay);
		}
		Vector3f back = new Vector3f(dir).negate();
		float uc = sprite.getFrameU(0.3f), vc = sprite.getFrameV(0.3f);
		quad(buffer, entry, back, new Vector3f(beam.a).add(corners[3]), new Vector3f(beam.a).add(corners[2]),
				new Vector3f(beam.a).add(corners[1]), new Vector3f(beam.a).add(corners[0]), uc, vc, uc, vc, light, overlay);
		quad(buffer, entry, dir, new Vector3f(beam.b).add(corners[0]), new Vector3f(beam.b).add(corners[1]),
				new Vector3f(beam.b).add(corners[2]), new Vector3f(beam.b).add(corners[3]), uc, vc, uc, vc, light, overlay);
	}

	private static void disc(VertexConsumer buffer, MatrixStack.Entry entry, Disc disc, Sprite top, Sprite rim, int light, int overlay) {
		Vector3f up = new Vector3f(0, 1, 0), down = new Vector3f(0, -1, 0);
		for (int i = 0; i < disc.sides; i++) {
			double a0 = Math.PI * 2 * i / disc.sides, a1 = Math.PI * 2 * (i + 1) / disc.sides;
			float x0 = disc.cx + (float) Math.cos(a0) * disc.radius, z0 = disc.cz + (float) Math.sin(a0) * disc.radius;
			float x1 = disc.cx + (float) Math.cos(a1) * disc.radius, z1 = disc.cz + (float) Math.sin(a1) * disc.radius;
			// Верх и низ — веером к центру; текстура проецируется сверху, чтобы поддон был кругом на квадрате.
			float s = 2 * disc.radius;
			float tu0 = top.getFrameU((x0 - disc.cx) / s + 0.5f), tv0 = top.getFrameV((z0 - disc.cz) / s + 0.5f);
			float tu1 = top.getFrameU((x1 - disc.cx) / s + 0.5f), tv1 = top.getFrameV((z1 - disc.cz) / s + 0.5f);
			float tuc = top.getFrameU(0.5f), tvc = top.getFrameV(0.5f);
			vertex(buffer, entry, disc.cx, disc.y1, disc.cz, tuc, tvc, up, light, overlay);
			vertex(buffer, entry, x1, disc.y1, z1, tu1, tv1, up, light, overlay);
			vertex(buffer, entry, x0, disc.y1, z0, tu0, tv0, up, light, overlay);
			vertex(buffer, entry, x0, disc.y1, z0, tu0, tv0, up, light, overlay);
			vertex(buffer, entry, disc.cx, disc.y0, disc.cz, tuc, tvc, down, light, overlay);
			vertex(buffer, entry, x0, disc.y0, z0, tu0, tv0, down, light, overlay);
			vertex(buffer, entry, x1, disc.y0, z1, tu1, tv1, down, light, overlay);
			vertex(buffer, entry, x1, disc.y0, z1, tu1, tv1, down, light, overlay);
			// Бортик — тёмный край текстуры дна.
			Vector3f side = new Vector3f((float) Math.cos((a0 + a1) / 2), 0, (float) Math.sin((a0 + a1) / 2));
			float ru0 = top.getFrameU(0.02f), ru1 = top.getFrameU(0.08f), rv0 = top.getFrameV(0.3f), rv1 = top.getFrameV(0.7f);
			quad(buffer, entry, side, new Vector3f(x0, disc.y0, z0), new Vector3f(x0, disc.y1, z0), new Vector3f(x1, disc.y1, z1),
					new Vector3f(x1, disc.y0, z1), ru0, rv0, ru1, rv1, light, overlay);
		}
	}

	private static void quad(VertexConsumer buffer, MatrixStack.Entry entry, Vector3f normal, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
			float u0, float v0, float u1, float v1, int light, int overlay) {
		vertex(buffer, entry, p0.x, p0.y, p0.z, u0, v0, normal, light, overlay);
		vertex(buffer, entry, p1.x, p1.y, p1.z, u1, v0, normal, light, overlay);
		vertex(buffer, entry, p2.x, p2.y, p2.z, u1, v1, normal, light, overlay);
		vertex(buffer, entry, p3.x, p3.y, p3.z, u0, v1, normal, light, overlay);
	}

	private static void vertex(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, float z, float u, float v,
			Vector3f normal, int light, int overlay) {
		buffer.vertex(entry, x, y, z).color(0xFFFFFFFF).texture(u, v).overlay(overlay).light(light)
				.normal(entry, normal.x, normal.y, normal.z);
	}
}
