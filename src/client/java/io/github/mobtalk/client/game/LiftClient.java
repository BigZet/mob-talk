package io.github.mobtalk.client.game;

import io.github.mobtalk.game.LiftBlocks;
import io.github.mobtalk.game.LiftCabin;
import io.github.mobtalk.game.Lifts;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;

/** The lift on the client: the cabin's and the landing doors' renderers (models from tools/city2/lift.py), the motor's hum while
 * the cabin runs, and the control panel screen. */
public final class LiftClient {
    private LiftClient() { }

    private static final String DIR = "block/city2/lift/";
    private static final String[] PARTS = {"cabin_lower", "cabin_upper", "cabin_leaf", "cabin_leaf_r", "door_leaf"};
    private static final Set<Integer> HUMMING = new HashSet<>();

    private static Identifier model(String name) { return Identifier.of("mobtalk", DIR + name); }
    private static Identifier model(String name, LiftBlocks.Style style) { return model(name + style.suffix()); }
    private static Identifier panel(int floors, LiftBlocks.Style style) { return model("cabin_panel_" + MathHelper.clamp(floors, 2, 17), style); }

    public static void register() {
        Lifts.register();
        ModelLoadingPlugin.register(ctx -> {
            for (LiftBlocks.Style style : LiftBlocks.Style.values()) {
                for (String part : PARTS) ctx.addModels(model(part, style));
                for (int n = 2; n <= 17; n++) ctx.addModels(panel(n, style));
            }
        });
        EntityRendererRegistry.register(Lifts.CABIN, CabinRenderer::new);
        BlockEntityRendererFactories.register(Lifts.DOOR_ENTITY, DoorRenderer::new);
        Lifts.openPanel = cabin -> MinecraftClient.getInstance().setScreen(new PanelScreen(cabin));
        ClientTickEvents.END_WORLD_TICK.register(world -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            for (Entity e : world.getEntities()) {
                if (e instanceof LiftCabin cabin && cabin.moving() && !HUMMING.contains(cabin.getId()) && cabin.squaredDistanceTo(client.player) < 48 * 48) {
                    HUMMING.add(cabin.getId());
                    client.getSoundManager().play(new Hum(cabin));
                }
            }
        });
    }

    static BakedModel baked(Identifier id) {
        return MinecraftClient.getInstance().getBakedModelManager().getModel(id);
    }

    static void draw(MatrixStack matrices, VertexConsumerProvider consumers, Identifier id, int light) {
        MinecraftClient.getInstance().getBlockRenderManager().getModelRenderer().render(matrices.peek(),
                consumers.getBuffer(TexturedRenderLayers.getEntityCutout()), null, baked(id), 1f, 1f, 1f, light, OverlayTexture.DEFAULT_UV);
    }

    /** The y rotation a blockstate gives a model facing north, for the matrix. */
    static float yaw(Direction facing) {
        return -switch (facing) { case EAST -> 90f; case SOUTH -> 180f; case WEST -> 270f; default -> 0f; };
    }

    /** The cabin: its body in two parts (a block model reaches 2 blocks at most), the panel for its number of floors, two leaves
     * sliding apart. Lit by its own lamp: never darker than block light 13. */
    static final class CabinRenderer extends EntityRenderer<LiftCabin> {
        CabinRenderer(EntityRendererFactory.Context ctx) { super(ctx); }

        @Override public Identifier getTexture(LiftCabin entity) { return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE; }

        @Override public void render(LiftCabin cabin, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
            int lit = LightmapTextureManager.pack(Math.max(13, LightmapTextureManager.getBlockLightCoordinates(light)),
                    LightmapTextureManager.getSkyLightCoordinates(light));
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw(cabin.facing())));
            matrices.translate(-1, 0, -1);
            LiftBlocks.Style style = cabin.style();
            draw(matrices, consumers, model("cabin_lower", style), lit);
            draw(matrices, consumers, panel(cabin.floors(), style), lit);
            matrices.push();
            matrices.translate(0, 2, 0);
            draw(matrices, consumers, model("cabin_upper", style), lit);
            matrices.pop();
            float open = MathHelper.lerp(tickDelta, cabin.prevDoorProgress, cabin.doorProgress);
            open = open * open * (3 - 2 * open);   // ease in and out
            for (int side = 0; side < 2; side++) {
                matrices.push();
                float x = side == 0 ? 8 - 7 * open : 16 + 7 * open;
                matrices.translate(x / 16f, 0, 1 / 16f);
                draw(matrices, consumers, model(side == 0 ? "cabin_leaf" : "cabin_leaf_r", style), lit);
                matrices.pop();
            }
            matrices.pop();
            super.render(cabin, yaw, tickDelta, matrices, consumers, light);
        }
    }

    /** A landing door column's leaf: slides 7 px behind the fixed panel. */
    static final class DoorRenderer implements BlockEntityRenderer<LiftBlocks.DoorEntity> {
        DoorRenderer(BlockEntityRendererFactory.Context ctx) { }

        @Override public void render(LiftBlocks.DoorEntity door, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay) {
            BlockState state = door.getCachedState();
            if (!state.isOf(Lifts.DOOR) || state.get(LiftBlocks.DoorBlock.HALF) != DoubleBlockHalf.LOWER) return;
            float open = MathHelper.lerp(tickDelta, door.prevProgress, door.progress);
            open = open * open * (3 - 2 * open);
            boolean left = state.get(LiftBlocks.DoorBlock.SIDE) == LiftBlocks.Side.LEFT;
            matrices.push();
            matrices.translate(0.5, 0, 0.5);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw(state.get(LiftBlocks.DoorBlock.FACING))));
            matrices.translate(-0.5, 0, -0.5);
            matrices.translate((left ? 8 - 7 * open : 7 * open) / 16f, 0, 3 / 16f);
            draw(matrices, consumers, model("door_leaf", state.get(LiftBlocks.STYLE)), light);
            matrices.pop();
        }

        @Override public boolean rendersOutsideBoundingBox(LiftBlocks.DoorEntity door) { return true; }
    }

    /** The motor's hum, heard from the cabin while it runs; fades in and out. */
    static final class Hum extends MovingSoundInstance {
        private final LiftCabin cabin;
        private int fade;

        Hum(LiftCabin cabin) {
            super(Lifts.MOTOR, SoundCategory.BLOCKS, Random.create());
            this.cabin = cabin;
            repeat = true;
            repeatDelay = 0;
            volume = 0.01f;
            x = cabin.getX();
            y = cabin.getY() + 2.5;
            z = cabin.getZ();
        }

        @Override public boolean shouldAlwaysPlay() { return true; }

        @Override public void tick() {
            if (cabin.isRemoved()) {
                HUMMING.remove(cabin.getId());
                setDone();
                return;
            }
            x = cabin.getX();
            y = cabin.getY() + 2.5;
            z = cabin.getZ();
            fade = cabin.moving() ? Math.min(10, fade + 1) : fade - 1;
            volume = 0.55f * Math.max(0, fade) / 10f;
            pitch = 0.9f + 0.1f * Math.max(0, fade) / 10f;
            if (fade < 0) {
                HUMMING.remove(cabin.getId());
                setDone();
            }
        }
    }

    /** The control panel up close: a button for every floor (lit while called), СТОП and ВЫЗОВ. textures/gui/lift_panel.png. */
    static final class PanelScreen extends Screen {
        private static final int W = 96, H = 200;
        private final LiftCabin cabin;
        private final Identifier sheet;
        private int left, top;

        PanelScreen(LiftCabin cabin) {
            super(Text.translatable("mobtalk.lift.title"));
            this.cabin = cabin;
            this.sheet = Identifier.of("mobtalk", "textures/gui/lift_panel" + cabin.style().suffix() + ".png");
        }

        @Override public boolean shouldPause() { return false; }

        @Override protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
        }

        /** Button i (a floor, or STOP/BELL): its top-left corner on screen. */
        private int[] at(int button) {
            if (button == Lifts.STOP_BUTTON) return new int[] {left + 20, top + 176};
            if (button == Lifts.BELL_BUTTON) return new int[] {left + 60, top + 176};
            int n = cabin.floors(), cols = n <= 6 ? 1 : n <= 12 ? 2 : 3, rows = (n + cols - 1) / cols;
            int col = button / rows, row = rows - 1 - button % rows;
            int colW = cols == 1 ? 0 : cols == 2 ? 40 : 28;
            int x0 = cols == 1 ? left + 46 : cols == 2 ? left + 28 : left + 22;
            int step = Math.min(22, 120 / rows);
            return new int[] {x0 + col * colW, top + 40 + row * step + (step - 16) / 2};
        }

        private int hovered(double mx, double my) {
            for (int b = Lifts.BELL_BUTTON; b < cabin.floors(); b++) {
                int[] p = at(b);
                if (mx >= p[0] && mx < p[0] + 16 && my >= p[1] && my < p[1] + 16) return b;
            }
            return Integer.MIN_VALUE;
        }

        @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, width, height, 0x60000000);
        }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            if (cabin.isRemoved() || client == null || client.player == null || !cabin.contains(client.player)) {
                close();
                return;
            }
            context.drawTexture(sheet, left, top, 0, 0, W, H, 256, 256);
            int n = cabin.floors(), calls = cabin.calls(), burnt = Math.floorMod(cabin.getUuid().hashCode(), n);
            int hover = hovered(mouseX, mouseY);
            for (int b = 0; b < n; b++) {
                int[] p = at(b);
                int u = (calls >> b & 1) != 0 ? 16 : b == burnt ? 32 : 0;
                context.drawTexture(sheet, p[0], p[1], u, 208, 16, 16, 256, 256);
                String label = String.valueOf(b + 1);
                context.drawText(textRenderer, label, p[0] - 3 - textRenderer.getWidth(label), p[1] + 4, 0xFF26262A, false);
                if (b == hover) context.drawTexture(sheet, p[0], p[1], 80, 208, 16, 16, 256, 256);
            }
            for (int b : List.of(Lifts.STOP_BUTTON, Lifts.BELL_BUTTON)) {
                int[] p = at(b);
                context.drawTexture(sheet, p[0], p[1], b == Lifts.STOP_BUTTON ? 48 : 64, 208, 16, 16, 256, 256);
                Text label = Text.translatable(b == Lifts.STOP_BUTTON ? "mobtalk.lift.stop" : "mobtalk.lift.bell");
                context.drawText(textRenderer, label, p[0] + 8 - textRenderer.getWidth(label) / 2, p[1] - 10, 0xFF26262A, false);
                if (b == hover) context.drawTexture(sheet, p[0], p[1], 80, 208, 16, 16, 256, 256);
            }
            Text floor = Text.translatable("mobtalk.lift.floor", cabin.floor() + 1).append(cabin.moving() ? (cabin.targetY() >= cabin.getY() ? " ▲" : " ▼") : "");
            context.drawText(textRenderer, floor, left + W + 8, top + 16, 0xFFFFFFFF, true);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int b = hovered(mouseX, mouseY);
            if (b != Integer.MIN_VALUE && button == 0) {
                ClientPlayNetworking.send(new Lifts.PressC2S(cabin.getId(), b));
                if (b >= 0) close();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
