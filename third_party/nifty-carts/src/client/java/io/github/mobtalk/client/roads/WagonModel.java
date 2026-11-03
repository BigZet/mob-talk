package io.github.mobtalk.client.roads;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.util.math.MathHelper;

/** The covered wagon (a «крытая повозка», the carriage of the roads): four wheels on two axles, a plank body with posts and a wool
 * roof on hoops. Ported from NiftyCarts' {@code WagonModel} (MIT, third_party/nifty-carts/NOTICE.md) to Yarn names; the chests are
 * left out and the roof always has its sides rolled up. */
final class WagonModel extends CartModel {
    static final int RIM = 10;
    private static final float Z_FIGHTING_EPSILON = 0.001f;

    private final ModelPart roof;

    WagonModel(ModelPart root, ModelPart roofRoot) {
        super(root, 2, RIM);
        roof = roofRoot.getChild("roof");
        roof.getChild("roofUnfurled").visible = false;
        roof.getChild("roofPartUnfurled").visible = true; // the sides rolled up: the travellers inside are seen
        roof.getChild("roofFurled").visible = false;
    }

    ModelPart roof() { return roof; }

    static TexturedModelData createRoofLayer() {
        float axleLength = 35;

        ModelData def = new ModelData();
        EasyMeshBuilder roof = new EasyMeshBuilder("roof", 0, 0);
        roof.setRotationAngles(0, MathHelper.HALF_PI, 0);

        EasyMeshBuilder roofTop = new EasyMeshBuilder("roofTop", 0, 0);
        roofTop.setRotationPoint(0,-25,0);
        roofTop.setRotationAngles(MathHelper.HALF_PI, 0, 0);
        roofTop.addBox(-32f - 2 * Z_FIGHTING_EPSILON, (-axleLength / 2.0f) + MathHelper.sqrt(2) * 8,MathHelper.sqrt(2) * 8 - 2, 24f, 35f - MathHelper.sqrt(2) * 16, 2);
        roofTop.addBox(8f + 2 * Z_FIGHTING_EPSILON, (-axleLength / 2.0f) + MathHelper.sqrt(2) * 8,MathHelper.sqrt(2) * 8 - 2, 24f, 35f - MathHelper.sqrt(2) * 16, 2);
        roofTop.addBox(-8, (-axleLength / 2.0f) + 1.0f + MathHelper.sqrt(112.5f), MathHelper.sqrt(112.5f) - 1, 16, 33f - 2 * MathHelper.sqrt(112.5f), 1);
        roof.addChild(roofTop);

        EasyMeshBuilder roofUnfurled = new EasyMeshBuilder("roofUnfurled", 0, 0);
        roofUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-25,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON, 24, 15, 2);
        roofUnfurled.addBox(-8,-25,(-axleLength / 2.0f) + 1f, 16, 15, 1);
        roofUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-25,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON, 24, 15, 2);

        roofUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-25,(axleLength / 2.0f) - 2 + Z_FIGHTING_EPSILON, 24, 15, 2);
        roofUnfurled.addBox(-8,-25,(axleLength / 2.0f) - 2f, 16, 15, 1);
        roofUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-25,(axleLength / 2.0f) - 2 + Z_FIGHTING_EPSILON, 24, 15, 2);

        EasyMeshBuilder roofBackFlaps = new EasyMeshBuilder("roofBackFlaps", 0, 0);
        roofBackFlaps.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofBackFlaps.addBox((-axleLength / 2.0f), -25, -33, 10, 15, 1);
        roofBackFlaps.addBox((axleLength / 2.0f) - 10, -25, -33 + Z_FIGHTING_EPSILON, 10, 15, 1);
        roofBackFlaps.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, -33 - 2 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 10, 1);
        roofUnfurled.addChild(roofBackFlaps);

        EasyMeshBuilder roofBackFlapsAngledLeft = new EasyMeshBuilder("roofBackFlapsAngledLeft", 0, 0);
        roofBackFlapsAngledLeft.setRotationPoint((-axleLength / 2.0f), -25, 0);
        roofBackFlapsAngledLeft.setRotationAngles(0,0,MathHelper.HALF_PI / 2.0f);
        roofBackFlapsAngledLeft.addBox(0,-16f,-33f - Z_FIGHTING_EPSILON, 10, 16, 1);
        roofBackFlaps.addChild(roofBackFlapsAngledLeft);

        EasyMeshBuilder roofBackFlapsAngledRight = new EasyMeshBuilder("roofBackFlapsAngledRight", 0, 0);
        roofBackFlapsAngledRight.setRotationPoint((axleLength / 2.0f), -25, 0);
        roofBackFlapsAngledRight.setRotationAngles(0,0,-MathHelper.HALF_PI / 2.0f);
        roofBackFlapsAngledRight.addBox(-10,-16f,-33f, 10, 16, 1);
        roofBackFlaps.addChild(roofBackFlapsAngledRight);

        EasyMeshBuilder roofFrontFlaps = new EasyMeshBuilder("roofFrontFlaps", 0, 0);
        roofFrontFlaps.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofFrontFlaps.addBox((-axleLength / 2.0f), -25, 32, 10, 15, 1);
        roofFrontFlaps.addBox((axleLength / 2.0f) - 10, -25, 32 - Z_FIGHTING_EPSILON, 10, 15, 1);
        roofFrontFlaps.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, 32 + 2 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 10, 1);
        roofUnfurled.addChild(roofFrontFlaps);

        EasyMeshBuilder roofFrontFlapsAngledLeft = new EasyMeshBuilder("roofFrontFlapsAngledLeft", 0, 0);
        roofFrontFlapsAngledLeft.setRotationPoint((-axleLength / 2.0f), -25, 0);
        roofFrontFlapsAngledLeft.setRotationAngles(0,0,MathHelper.HALF_PI / 2.0f);
        roofFrontFlapsAngledLeft.addBox(0,-16f,32f + Z_FIGHTING_EPSILON, 10, 16, 1);
        roofFrontFlaps.addChild(roofFrontFlapsAngledLeft);

        EasyMeshBuilder roofFrontFlapsAngledRight = new EasyMeshBuilder("roofFrontFlapsAngledRight", 0, 0);
        roofFrontFlapsAngledRight.setRotationPoint((axleLength / 2.0f), -25, 0);
        roofFrontFlapsAngledRight.setRotationAngles(0,0,-MathHelper.HALF_PI / 2.0f);
        roofFrontFlapsAngledRight.addBox(-10,-16f,32f, 10, 16, 1);
        roofFrontFlaps.addChild(roofFrontFlapsAngledRight);

        EasyMeshBuilder roofLeftSlopeMiddle = new EasyMeshBuilder("roofLeftSlopeMiddle", 0, 0);
        roofLeftSlopeMiddle.setRotationPoint(0, -25, (axleLength / 2.0f) - 1);
        roofLeftSlopeMiddle.setRotationAngles(MathHelper.HALF_PI / 2f, 0, 0);
        roofLeftSlopeMiddle.addBox(-8, -15, -1f, 16, 15, 1);
        roofUnfurled.addChild(roofLeftSlopeMiddle);

        EasyMeshBuilder roofLeftSlopeOuter = new EasyMeshBuilder("roofLeftSlopeOuter", 0, 0);
        roofLeftSlopeOuter.setRotationPoint(0, -25, (axleLength / 2.0f));
        roofLeftSlopeOuter.setRotationAngles(MathHelper.HALF_PI / 2f, 0, 0);
        roofLeftSlopeOuter.addBox(-32, -16, -2f, 24, 16, 2);
        roofLeftSlopeOuter.addBox(8, -16, -2f, 24, 16, 2);
        roofUnfurled.addChild(roofLeftSlopeOuter);

        EasyMeshBuilder roofRightSlopeMiddle = new EasyMeshBuilder("roofRightSlopeMiddle", 0, 0);
        roofRightSlopeMiddle.setRotationPoint(0, -25, (-axleLength / 2.0f) + 1);
        roofRightSlopeMiddle.setRotationAngles(-MathHelper.HALF_PI / 2f, 0, 0);
        roofRightSlopeMiddle.addBox(-8, -15, 0f, 16, 15, 1);
        roofUnfurled.addChild(roofRightSlopeMiddle);

        EasyMeshBuilder roofRightSlopeOuter = new EasyMeshBuilder("roofRightSlopeOuter", 0, 0);
        roofRightSlopeOuter.setRotationPoint(0, -25, (-axleLength / 2.0f));
        roofRightSlopeOuter.setRotationAngles(-MathHelper.HALF_PI / 2f, 0, 0);
        roofRightSlopeOuter.addBox(-32, -16, 0f, 24, 16, 2);
        roofRightSlopeOuter.addBox(8, -16, 0f, 24, 16, 2);
        roofUnfurled.addChild(roofRightSlopeOuter);

        roof.addChild(roofUnfurled);

        EasyMeshBuilder roofPartUnfurled = new EasyMeshBuilder("roofPartUnfurled", 0, 0);
        roofPartUnfurled.addChild(roofLeftSlopeMiddle);
        roofPartUnfurled.addChild(roofLeftSlopeOuter);
        roofPartUnfurled.addChild(roofRightSlopeMiddle);
        roofPartUnfurled.addChild(roofRightSlopeOuter);

        roofPartUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-25,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON, 24, 7, 2);
        roofPartUnfurled.addBox(-8,-25,(-axleLength / 2.0f) + 1f, 16, 7, 1);
        roofPartUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-25,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON, 24, 7, 2);

        roofPartUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-20,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON - 1, 24, 2, 1);
        roofPartUnfurled.addBox(-8,-20,(-axleLength / 2.0f), 16, 2, 1);
        roofPartUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-20,(-axleLength / 2.0f) - Z_FIGHTING_EPSILON - 1, 24, 2, 1);

        roofPartUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-25,(axleLength / 2.0f) - 2 + Z_FIGHTING_EPSILON, 24, 7, 2);
        roofPartUnfurled.addBox(-8,-25,(axleLength / 2.0f) - 2f, 16, 7, 1);
        roofPartUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-25,(axleLength / 2.0f) - 2 + Z_FIGHTING_EPSILON, 24, 7, 2);

        roofPartUnfurled.addBox(-32f - 2 * Z_FIGHTING_EPSILON,-20,(axleLength / 2.0f) + Z_FIGHTING_EPSILON, 24, 2, 1);
        roofPartUnfurled.addBox(-8,-20,(axleLength / 2.0f) - 1f, 16, 2, 1);
        roofPartUnfurled.addBox(8f + 2 * Z_FIGHTING_EPSILON,-20,(axleLength / 2.0f) + Z_FIGHTING_EPSILON, 24, 2, 1);

        EasyMeshBuilder roofBackFlapsPartUnfurled = new EasyMeshBuilder("roofBackFlapsPartUnfurled", 0, 0);
        roofBackFlapsPartUnfurled.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofBackFlapsPartUnfurled.addBox((-axleLength / 2.0f), -25, -33, 10, 7, 1);
        roofBackFlapsPartUnfurled.addBox((axleLength / 2.0f) - 10, -25, -33 + Z_FIGHTING_EPSILON, 10, 7, 1);
        roofBackFlapsPartUnfurled.addBox((-axleLength / 2.0f), -20, -34, 10, 2, 1);
        roofBackFlapsPartUnfurled.addBox((axleLength / 2.0f) - 10, -20, -34 + Z_FIGHTING_EPSILON, 10, 2, 1);
        roofBackFlapsPartUnfurled.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, -33 - 2 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 10, 1);
        roofPartUnfurled.addChild(roofBackFlapsPartUnfurled);

        roofBackFlapsPartUnfurled.addChild(roofBackFlapsAngledLeft);
        roofBackFlapsPartUnfurled.addChild(roofBackFlapsAngledRight);

        EasyMeshBuilder roofFrontFlapsPartUnfurled = new EasyMeshBuilder("roofFrontFlapsPartUnfurled", 0, 0);
        roofFrontFlapsPartUnfurled.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofFrontFlapsPartUnfurled.addBox((-axleLength / 2.0f), -25, 32, 10, 7, 1);
        roofFrontFlapsPartUnfurled.addBox((axleLength / 2.0f) - 10, -25, 32 - Z_FIGHTING_EPSILON, 10, 7, 1);
        roofFrontFlapsPartUnfurled.addBox((-axleLength / 2.0f), -20, 33, 10, 2, 1);
        roofFrontFlapsPartUnfurled.addBox((axleLength / 2.0f) - 10, -20, 33 - Z_FIGHTING_EPSILON, 10, 2, 1);
        roofFrontFlapsPartUnfurled.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, 32 + 2 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 10, 1);
        roofPartUnfurled.addChild(roofFrontFlapsPartUnfurled);

        roofFrontFlapsPartUnfurled.addChild(roofFrontFlapsAngledLeft);
        roofFrontFlapsPartUnfurled.addChild(roofFrontFlapsAngledRight);

        roof.addChild(roofPartUnfurled);

        EasyMeshBuilder furled = new EasyMeshBuilder("roofFurled", 0, 0);

        EasyMeshBuilder roofLeftSlopeMiddleFurled = new EasyMeshBuilder("roofLeftSlopeMiddleFurled", 0, 0);
        roofLeftSlopeMiddleFurled.setRotationPoint(0, -25, (axleLength / 2.0f) - 1);
        roofLeftSlopeMiddleFurled.setRotationAngles(MathHelper.HALF_PI / 2f, 0, 0);
        roofLeftSlopeMiddleFurled.addBox(-8, -15, -1f, 16, 5, 1);
        roofLeftSlopeMiddleFurled.addBox(-8 ,-13, 0f, 16, 3, 1);
        furled.addChild(roofLeftSlopeMiddleFurled);

        EasyMeshBuilder roofLeftSlopeOuterFurled = new EasyMeshBuilder("roofLeftSlopeOuterFurled", 0, 0);
        roofLeftSlopeOuterFurled.setRotationPoint(0, -25, (axleLength / 2.0f));
        roofLeftSlopeOuterFurled.setRotationAngles(MathHelper.HALF_PI / 2f, 0, 0);
        roofLeftSlopeOuterFurled.addBox(-32, -16, -2f, 24, 6, 2);
        roofLeftSlopeOuterFurled.addBox(8, -16, -2f, 24, 6, 2);
        roofLeftSlopeOuterFurled.addBox(-32, -14, 0f, 24, 4, 1);
        roofLeftSlopeOuterFurled.addBox(8, -14, 0f, 24, 4, 1);
        furled.addChild(roofLeftSlopeOuterFurled);

        EasyMeshBuilder roofRightSlopeMiddleFurled = new EasyMeshBuilder("roofRightSlopeMiddleFurled", 0, 0);
        roofRightSlopeMiddleFurled.setRotationPoint(0, -25, (-axleLength / 2.0f) + 1);
        roofRightSlopeMiddleFurled.setRotationAngles(-MathHelper.HALF_PI / 2f, 0, 0);
        roofRightSlopeMiddleFurled.addBox(-8, -15, 0f, 16, 5, 1);
        roofRightSlopeMiddleFurled.addBox(-8, -13, -1f, 16, 3, 1);
        furled.addChild(roofRightSlopeMiddleFurled);

        EasyMeshBuilder roofRightSlopeOuterFurled = new EasyMeshBuilder("roofRightSlopeOuterFurled", 0, 0);
        roofRightSlopeOuterFurled.setRotationPoint(0, -25, (-axleLength / 2.0f));
        roofRightSlopeOuterFurled.setRotationAngles(-MathHelper.HALF_PI / 2f, 0, 0);
        roofRightSlopeOuterFurled.addBox(-32, -16, 0f, 24, 6, 2);
        roofRightSlopeOuterFurled.addBox(8, -16, 0f, 24, 6, 2);
        roofRightSlopeOuterFurled.addBox(-32, -14, -1f, 24, 4, 1);
        roofRightSlopeOuterFurled.addBox(8, -14, -1f, 24, 4, 1);
        furled.addChild(roofRightSlopeOuterFurled);

        EasyMeshBuilder roofBackFlapsFurled = new EasyMeshBuilder("roofBackFlapsFurled", 0, 0);
        roofBackFlapsFurled.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofBackFlapsFurled.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, -33 - 1 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 5, 1);
        roofBackFlapsFurled.addBox((-axleLength / 2.0f) - 3f + MathHelper.sqrt(2) * 8, -23 - MathHelper.sqrt(2) * 8 + Z_FIGHTING_EPSILON, -34 - 2 * Z_FIGHTING_EPSILON, 35f + 6f - MathHelper.sqrt(2) * 16, 3, 2);
        furled.addChild(roofBackFlapsFurled);

        EasyMeshBuilder roofFrontFlapsFurled = new EasyMeshBuilder("roofFrontFlapsFurled", 0, 0);
        roofFrontFlapsFurled.setRotationAngles(0, MathHelper.HALF_PI, 0);
        roofFrontFlapsFurled.addBox((-axleLength / 2.0f) + MathHelper.sqrt(2) * 8, -25 - MathHelper.sqrt(2) * 8, 32 + 1 * Z_FIGHTING_EPSILON, 35f - MathHelper.sqrt(2) * 16, 5, 1);
        roofFrontFlapsFurled.addBox((-axleLength / 2.0f) - 3f + MathHelper.sqrt(2) * 8, -23 - MathHelper.sqrt(2) * 8 + Z_FIGHTING_EPSILON, 32 + 2 * Z_FIGHTING_EPSILON, 35f + 6f - MathHelper.sqrt(2) * 16, 3, 2);
        furled.addChild(roofFrontFlapsFurled);

        roof.addChild(furled);

        roof.build(def.getRoot());

        return TexturedModelData.of(def, 16, 16);
    }

    static TexturedModelData createLayer() {
        int rimLength = 10;
        float axleLength = 35;
        float axleDist = 40;
        ModelData def = CartModel.createDefinition(rimLength, axleLength, 2, axleDist);

        EasyMeshBuilder axle0 = new EasyMeshBuilder("axle_0", 0, 60);
        axle0.addBox(-axleLength / 2.0f, -1.0F, -1.0F - (axleDist / 2f), MathHelper.ceil(axleLength / 2.0f), 2, 2);
        axle0.addBox((-axleLength / 2.0f) + MathHelper.ceil(axleLength / 2.0f), -1.0F, -1.0F - (axleDist / 2f), MathHelper.floor(axleLength / 2.0f), 2, 2);

        EasyMeshBuilder axle1 = new EasyMeshBuilder("axle_1", 0, 60);
        axle1.addBox(-axleLength / 2.0f, -1.0F, -1.0F + (axleDist / 2f), MathHelper.ceil(axleLength / 2.0f), 2, 2);
        axle1.addBox((-axleLength / 2.0f) + MathHelper.ceil(axleLength / 2.0f), -1.0F, -1.0F + (axleDist / 2f), MathHelper.floor(axleLength / 2.0f), 2, 2);

        EasyMeshBuilder base = new EasyMeshBuilder("base", 0, 0);
        base.addBox((-axleLength / 2.0f) + 2, -2.0f, -1.0f - (axleDist / 2f) - 10, (float) Math.ceil((axleLength - 4.0f) / 2.0f), 1, ((axleDist + 22) / 2) - 1);
        base.addBox((float) ((-axleLength / 2.0f) + 2 + Math.ceil((axleLength - 4.0f) / 2.0f)), -2.0f, -1.0f - (axleDist / 2f) - 10, (float) Math.floor((axleLength - 4) / 2.0f), 1, ((axleDist + 22) / 2) - 1);
        base.addBox((-axleLength / 2.0f) + 2, -2.0f, -1, (float) Math.ceil((axleLength - 4.0f) / 2.0f), 1, ((axleDist + 22) / 2) + 1);
        base.addBox((float) ((-axleLength / 2.0f) + 2 + Math.ceil((axleLength - 4.0f) / 2.0f)), -2.0f, -1, (float) Math.floor((axleLength - 4) / 2.0f), 1, ((axleDist + 22) / 2) + 1);

        EasyMeshBuilder walls = new EasyMeshBuilder("walls", 0, 0);

        EasyMeshBuilder rightWalls = new EasyMeshBuilder("rightWalls", 0, 2);
        rightWalls.setRotationAngles(0, MathHelper.HALF_PI, 0);

        rightWalls.addBox(-32,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        rightWalls.addBox(-16,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        rightWalls.addBox(0,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        rightWalls.addBox(16,-10,(axleLength / 2.0f) - 4, 16, 8, 2);

        EasyMeshBuilder leftWalls = new EasyMeshBuilder("leftWalls", 0, 2);
        leftWalls.setRotationAngles(0, -MathHelper.HALF_PI, 0);

        leftWalls.addBox(-32,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        leftWalls.addBox(-16,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        leftWalls.addBox(0,-10,(axleLength / 2.0f) - 4, 16, 8, 2);
        leftWalls.addBox(16,-10,(axleLength / 2.0f) - 4, 16, 8, 2);

        EasyMeshBuilder wallBeams = new EasyMeshBuilder("wallBeams", 0, 3);
        wallBeams.setRotationAngles(0, MathHelper.HALF_PI, 0);

        wallBeams.addBox(-32, -10, (-axleLength / 2.0f) + 1, 4, 8, 1);
        wallBeams.addBox(-12, -10, (-axleLength / 2.0f) + 1, 4, 8, 1);
        wallBeams.addBox(8, -10, (-axleLength / 2.0f) + 1, 4, 8, 1);
        wallBeams.addBox(28, -10, (-axleLength / 2.0f) + 1, 4, 8, 1);

        wallBeams.addBox(-32, -10, (axleLength / 2.0f) - 2, 4, 8, 1);
        wallBeams.addBox(-12, -10, (axleLength / 2.0f) - 2, 4, 8, 1);
        wallBeams.addBox(8, -10, (axleLength / 2.0f) - 2, 4, 8, 1);
        wallBeams.addBox(28, -10, (axleLength / 2.0f) - 2, 4, 8, 1);

        walls.addChild(wallBeams);
        walls.addChild(leftWalls);
        walls.addChild(rightWalls);

        EasyMeshBuilder beams = new EasyMeshBuilder("beams", 0, 32);

        beams.addBox((-axleLength / 2.0f) + 2, -25, -32f - 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((-axleLength / 2.0f) + 2, -25, -12 + 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((-axleLength / 2.0f) + 2, -25, 8 - 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((-axleLength / 2.0f) + 2, -25, 28 + 2 * Z_FIGHTING_EPSILON, 1, 15, 4);

        beams.addBox((axleLength / 2.0f) - 3, -25, -32f - 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((axleLength / 2.0f) - 3, -25, -12 + 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((axleLength / 2.0f) - 3, -25, 8 - 2 * Z_FIGHTING_EPSILON, 1, 15, 4);
        beams.addBox((axleLength / 2.0f) - 3, -25, 28 + 2 * Z_FIGHTING_EPSILON, 1, 15, 4);

        walls.addChild(beams);

        EasyMeshBuilder backAndFrontWalls = new EasyMeshBuilder("backAndFront", 0, 3);

        backAndFrontWalls.addBox((-axleLength / 2.0f) + 4, -10, -32, axleLength - 8, 8, 1);
        backAndFrontWalls.addBox((-axleLength / 2.0f) + 4, -10, 31, 8, 8, 1);
        backAndFrontWalls.addBox((axleLength / 2.0f) - 12, -10, 31, 8, 8, 1);

        walls.addChild(backAndFrontWalls);

        EasyMeshBuilder rotating = new EasyMeshBuilder("rotating", 0, 0);
        rotating.setRotationPoint(0, -13, -20);
        rotating.addBox(-8.5f, 0, -35, 1, 2, 36);
        rotating.addBox(7.5f, 0, -35, 1, 2, 36);
        rotating.build(def.getRoot());

        EasyMeshBuilder wallLeft = new EasyMeshBuilder("beamsAngledLeft", 0, 32);
        wallLeft.setRotationPoint((axleLength / 2.0f) - 2, -25, 0);
        wallLeft.setRotationAngles(0, 0, -MathHelper.HALF_PI / 2f);
        wallLeft.addBox(-1, -14, -32f - Z_FIGHTING_EPSILON, 1, 14, 4);
        wallLeft.addBox(-1, -14, -12f + Z_FIGHTING_EPSILON, 1, 14, 4);
        wallLeft.addBox(-1, -14, 8f - Z_FIGHTING_EPSILON, 1, 14, 4);
        wallLeft.addBox(-1, -14, 28f + Z_FIGHTING_EPSILON, 1, 14, 4);
        walls.addChild(wallLeft);

        EasyMeshBuilder wallRight = new EasyMeshBuilder("beamsAngledRight", 0, 32);
        wallRight.setRotationPoint((-axleLength / 2.0f) + 2, -25, 0);
        wallRight.setRotationAngles(0, 0, MathHelper.HALF_PI / 2f);
        wallRight.addBox(0, -14, -32f - Z_FIGHTING_EPSILON, 1, 14, 4);
        wallRight.addBox(0, -14, -12f + Z_FIGHTING_EPSILON, 1, 14, 4);
        wallRight.addBox(0, -14, 8f - Z_FIGHTING_EPSILON, 1, 14, 4);
        wallRight.addBox(0, -14, 28f + Z_FIGHTING_EPSILON, 1, 14, 4);
        walls.addChild(wallRight);

        EasyMeshBuilder wallTop = new EasyMeshBuilder("beamsTop", 3, 32);
        wallTop.setRotationPoint(0, -25, 0);
        wallTop.setRotationAngles(MathHelper.HALF_PI, MathHelper.HALF_PI, 0);
        wallTop.addBox(-32, (-axleLength / 2.0f) + 2 + MathHelper.sqrt(2) * 7, (float) (Math.sqrt(2.0f) * 7.0f - 1.0f), 4, 31f - MathHelper.sqrt(2) * 14, 1);
        wallTop.addBox(-12, (-axleLength / 2.0f) + 2 + MathHelper.sqrt(2) * 7, (float) (Math.sqrt(2.0f) * 7.0f - 1.0f), 4, 31f - MathHelper.sqrt(2) * 14, 1);
        wallTop.addBox(8, (-axleLength / 2.0f) + 2 + MathHelper.sqrt(2) * 7, (float) (Math.sqrt(2.0f) * 7.0f - 1.0f), 4, 31f - MathHelper.sqrt(2) * 14, 1);
        wallTop.addBox(28, (-axleLength / 2.0f) + 2 + MathHelper.sqrt(2) * 7, (float) (Math.sqrt(2.0f) * 7.0f - 1.0f), 4, 31f - MathHelper.sqrt(2) * 14, 1);
        walls.addChild(wallTop);

        EasyMeshBuilder body = CartModel.createBody(rimLength);
        body.addChild(axle0);
        body.addChild(axle1);
        body.addChild(base);
        body.addChild(walls);
        body.build(def.getRoot());

        return TexturedModelData.of(def, 64, 64);
    }
}
