package io.github.mobtalk.client.roads;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;

/** A model part with its boxes and children, built into a vanilla model tree. Ported from NiftyCarts' {@code EasyMeshBuilder}
 * (MIT, Copyright (c) 2019 MennoMax, (c) 2023 jmb19905; third_party/nifty-carts/NOTICE.md) to Yarn names; unchanged otherwise. */
final class EasyMeshBuilder {
    private final String name;
    float x, y, z, xRot, yRot, zRot;
    private final ModelPartBuilder cubes = ModelPartBuilder.create();
    private final List<EasyMeshBuilder> children = new ArrayList<>();

    EasyMeshBuilder(String name) { this.name = name; }

    EasyMeshBuilder(String name, int u, int v) { this(name); cubes.uv(u, v); }

    EasyMeshBuilder addBox(float x, float y, float z, float width, float height, float depth) {
        cubes.cuboid(x, y, z, width, height, depth);
        return this;
    }

    EasyMeshBuilder addBox(float x, float y, float z, float width, float height, float depth, float expand) {
        cubes.cuboid(x, y, z, width, height, depth, new Dilation(expand));
        return this;
    }

    void addChild(EasyMeshBuilder child) { children.add(child); }

    void setRotationPoint(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }

    void setRotationAngles(float x, float y, float z) { xRot = x; yRot = y; zRot = z; }

    void build(ModelPartData parent) {
        ModelPartData part = parent.addChild(name, cubes, ModelTransform.of(x, y, z, xRot, yRot, zRot));
        for (EasyMeshBuilder child : children) child.build(part);
    }
}
