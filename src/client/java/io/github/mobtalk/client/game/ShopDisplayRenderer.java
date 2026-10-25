package io.github.mobtalk.client.game;

import io.github.mobtalk.game.ShopContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/** Draws a display's goods on its tiers (as many as are left of each lane) and a price tag under each lane: «1 за 3 шт» with an
 * emerald. Positions are pixels in the north-facing model of tools/city/shop.py; the gondola shows its lanes on both sides. */
public final class ShopDisplayRenderer implements BlockEntityRenderer<ShopContent.DisplayBlockEntity> {
    /** One spot for an item: where (pixels), turned how much about Y and tipped back about X (degrees), how big (pixels). */
    record Spot(float x, float y, float z, float yaw, float pitch, float size) { }
    /** A lane's spots and where its price tag hangs (pixels, facing the front). */
    record Layout(List<Spot> spots, float tagX, float tagY, float tagZ) { }

    private final ItemRenderer items;
    private final TextRenderer text;
    private static final ItemStack EMERALD = new ItemStack(Items.EMERALD);

    public ShopDisplayRenderer(BlockEntityRendererFactory.Context context) {
        this.items = context.getItemRenderer();
        this.text = context.getTextRenderer();
    }

    static Layout layout(ShopContent.Display display, int lane, boolean back) {
        List<Spot> spots = new ArrayList<>();
        float[] across = {3f, 8f, 13f};
        switch (display) {
            case SHELF -> {
                float y = 2 + lane * 5f;
                for (float x : across) {
                    if (back) spots.add(new Spot(16 - x, y, 12, 180, 0, 4.6f)); else spots.add(new Spot(x, y, 4, 0, 0, 4.6f));
                    if (back) spots.add(new Spot(16 - x, y, 10.2f, 180, 0, 4.6f)); else spots.add(new Spot(x, y, 5.8f, 0, 0, 4.6f));
                }
                return back ? new Layout(spots, 8, y - 1.4f, 15.05f) : new Layout(spots, 8, y - 1.4f, 0.95f);
            }
            case BREAD -> {
                float y = 1.5f + lane * 5f;
                for (float x : across) { spots.add(new Spot(x, y + 0.6f, 6, 0, 35, 4.8f)); spots.add(new Spot(x, y + 1.6f, 10, 0, 35, 4.8f)); }
                return new Layout(spots, 8, y - 0.9f, 1.95f);
            }
            case FRIDGE -> {
                float y = 1.5f + lane * 4.5f;
                for (float x : across) { spots.add(new Spot(x, y, 5, 0, 0, 3.8f)); spots.add(new Spot(x, y, 8.5f, 0, 0, 3.8f)); }
                return new Layout(spots, 8, y - 0.8f, 1.5f);
            }
            case FREEZER -> {
                float x0 = lane == 0 ? 4 : 12;
                for (float dz : new float[] {4.5f, 8f, 11.5f}) for (float dx : new float[] {-2f, 2f})
                    spots.add(new Spot(x0 + dx, 4.4f + (dz - 4.5f) * 0.25f, dz, (dx > 0 ? 12 : -9), 70, 4.6f));
                return new Layout(spots, x0, 8.2f, 0.95f);
            }
            case PRODUCE -> {
                float x0 = 2.75f + lane * 5.25f;
                for (float dz : new float[] {5f, 9.5f, 13.5f}) for (float dx : new float[] {-1.2f, 1.2f})
                    spots.add(new Spot(x0 + dx, 6.6f + (dz - 5) * 0.32f, dz, dx * 9, 62, 3.6f));
                return new Layout(spots, x0, 4.4f, 0.95f);
            }
            case VEG_STALL -> { // crates on the counter, tipped towards the customer
                float x0 = 2.8f + lane * 5.2f;
                for (float dz : new float[] {3f, 6.5f, 10f}) for (float dx : new float[] {-1.2f, 1.2f})
                    spots.add(new Spot(x0 + dx, 11.2f + (dz - 3) * 0.3f, dz, dx * 9, 62, 3.6f));
                return new Layout(spots, x0, 8.2f, 0.45f);
            }
            case MEAT_STALL -> { // laid out on the tiled counter
                float x0 = 2.75f + lane * 5.25f;
                for (float dz : new float[] {3f, 6.5f}) for (float dx : new float[] {-1.3f, 1.3f})
                    spots.add(new Spot(x0 + dx, 11.05f, dz, dx * 12, 80, 4.2f));
                return new Layout(spots, x0, 8.7f, 0.45f);
            }
            case DAIRY_STALL -> { // standing in rows on the counter
                float x0 = 2.75f + lane * 5.25f;
                for (float dz : new float[] {3f, 6.5f, 10f}) for (float dx : new float[] {-1.2f, 1.2f})
                    spots.add(new Spot(x0 + dx, 10f, dz, 0, 0, 3.6f));
                return new Layout(spots, x0, 8.2f, 0.45f);
            }
            case GRANNY_TABLE -> { // a few jars and bags on the oilcloth
                float x0 = lane == 0 ? 5 : 11;
                for (float dz : new float[] {4.5f, 8f, 11.5f}) for (float dx : new float[] {-1.5f, 1.5f})
                    spots.add(new Spot(x0 + dx, 9f, dz, dx * 6, 0, 3.4f));
                return new Layout(spots, x0, 7.1f, 1.8f);
            }
        }
        return new Layout(spots, 8, 1, 1);
    }

    @Override public void render(ShopContent.DisplayBlockEntity display, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay) {
        BlockState state = display.getCachedState();
        Direction facing = state.contains(HorizontalFacingBlock.FACING) ? state.get(HorizontalFacingBlock.FACING) : Direction.NORTH;
        ShopContent.Display kind = display.display();
        matrices.push();
        matrices.translate(0.5, 0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation() + 180));
        matrices.translate(-0.5, 0, -0.5);
        int seed = (int) display.getPos().asLong();
        for (int lane = 0; lane < display.lanes.length; lane++) {
            ShopContent.DisplayBlockEntity.Lane goods = display.lanes[lane];
            if (goods.goods.isEmpty()) continue;
            for (int side = 0; side < (kind.twoSided ? 2 : 1); side++) {
                Layout layout = layout(kind, lane, side == 1);
                int shown = goods.stock <= 0 ? 0 : (int) Math.ceil(layout.spots().size() * goods.stock / (double) kind.capacity());
                for (int i = 0; i < shown; i++) item(matrices, consumers, light, overlay, goods.goods, layout.spots().get(i), display, seed + i);
                tag(matrices, consumers, light, kind, goods, layout, side == 1);
            }
        }
        matrices.pop();
    }

    private void item(MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay, ItemStack stack, Spot spot,
                      ShopContent.DisplayBlockEntity display, int seed) {
        boolean block = stack.getItem() instanceof BlockItem;
        float size = spot.size() / 16f * (block ? 1.7f : 1f);
        matrices.push();
        matrices.translate(spot.x() / 16f, spot.y() / 16f + (spot.pitch() >= 60 ? 0.02f : size * (block ? 0.25f : 0.5f)), spot.z() / 16f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 + spot.yaw()));
        if (spot.pitch() != 0) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-spot.pitch()));
        matrices.scale(size, size, size);
        items.renderItem(stack, ModelTransformationMode.FIXED, light, overlay, matrices, consumers, display.getWorld(), seed);
        matrices.pop();
    }

    /** The price tag: a white strip with «N за M шт» and a small emerald, on the front edge under the lane. A lane on «акция» has
     * a yellow tag and a band above it with the old price struck out; the shop's «товар дня» a red one. */
    private void tag(MatrixStack matrices, VertexConsumerProvider consumers, int light, ShopContent.Display display, ShopContent.DisplayBlockEntity.Lane lane, Layout layout, boolean back) {
        Text label = Text.translatable("mobtalk.shop.tag", lane.price, lane.lot);
        int paper = 0xFFF4F1E4, ink = 0xFF202020;
        MutableText band = null;
        if (lane.deal != 0 && lane.was > lane.price) {
            paper = lane.deal == 2 ? 0xFFD8262B : 0xFFF7D21E;
            ink = lane.deal == 2 ? 0xFFFFFFFF : 0xFF202020;
            band = Text.translatable(lane.deal == 2 ? "mobtalk.shop.tag.day" : "mobtalk.shop.tag.promo").append(" ")
                    .append(Text.literal(String.valueOf(lane.was)).formatted(Formatting.STRIKETHROUGH)); // the old price, struck out
        }
        float scale = display == ShopContent.Display.FREEZER ? 1 / 150f : 1 / 100f;
        int width = text.getWidth(label);
        matrices.push();
        matrices.translate(layout.tagX() / 16f, layout.tagY() / 16f, layout.tagZ() / 16f);
        if (back) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.scale(scale, -scale, scale);
        float left = -(width + 10) / 2f;
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        // The tag first, then the words a hair in front of it (one draw with a background loses the glyphs to the depth test).
        text.draw(Text.literal(" ".repeat(Math.max(1, (width + 12) / 4))), left, -4, 0x00000000, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL, paper, light);
        if (band != null) { // the band above: «АКЦИЯ −30%» / «ТОВАР ДНЯ −50%», smaller
            int bw = text.getWidth(band);
            matrices.push();
            matrices.translate(0, -9.5f, 0);
            matrices.scale(0.7f, 0.7f, 1);
            Matrix4f m = matrices.peek().getPositionMatrix();
            text.draw(Text.literal(" ".repeat(Math.max(1, (bw + 6) / 4))), -(bw + 6) / 2f, -4, 0x00000000, false, m, consumers, TextRenderer.TextLayerType.NORMAL, lane.deal == 2 ? 0xFFF7D21E : 0xFFD8262B, light);
            matrices.translate(0, 0, 0.6f);
            text.draw(band, -bw / 2f, -4, lane.deal == 2 ? 0xFF8A1010 : 0xFFFFFFFF, false, matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL, 0, light);
            matrices.pop();
        }
        matrices.push();
        matrices.translate(0, 0, 0.6f);
        text.draw(label, left + 10, -4, ink, false, matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL, 0, light);
        matrices.pop();
        matrices.push();
        matrices.translate(left + 5, 0.5f, 0.8f);
        matrices.scale(9, -9, 0.01f);
        items.renderItem(EMERALD, ModelTransformationMode.GUI, light, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, matrices, consumers,
                MinecraftClient.getInstance().world, 0);
        matrices.pop();
        matrices.pop();
    }
}
