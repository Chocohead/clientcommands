package net.earthcomputer.clientcommands.render;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RenderQueue {
    private static int tickCounter = 0;
    private static final List<AddQueueEntry> addQueue = new ArrayList<>();
    private static final EnumMap<Layer, Map<Object, Shape>> queue = new EnumMap<>(Layer.class);

    public static void add(Layer layer, Object key, Shape shape, int life) {
        addQueue.add(new AddQueueEntry(layer, key, shape, life));
    }

    public static void addCuboid(Layer layer, Object key, String name, Vec3d from, Vec3d to, int color, int life) {
        add(layer, key, new Cuboid(name, from, to, color), life);
    }

    public static void addCuboid(Layer layer, Object key, String name, Box cuboid, int color, int life) {
        add(layer, key, new Cuboid(name, cuboid, color), life);
    }

    public static void addLine(Layer layer, Object key, String name, Vec3d from, Vec3d to, int color, int life) {
        add(layer, key, new Line(name, from, to, color), life);
    }

    private static void doAdd(AddQueueEntry entry) {
        Map<Object, Shape> shapes = queue.computeIfAbsent(entry.layer, k -> new LinkedHashMap<>());
        Shape oldShape = shapes.get(entry.key);
        if (oldShape != null) {
            entry.shape.prevPos = oldShape.prevPos;
        } else {
            entry.shape.prevPos = entry.shape.getPos();
        }
        entry.shape.deathTime = tickCounter + entry.life;
        shapes.put(entry.key, entry.shape);
    }

    public static void tick() {
        queue.values().forEach(shapes -> shapes.values().forEach(shape -> shape.prevPos = shape.getPos()));
        tickCounter++;
        for (AddQueueEntry entry : addQueue) {
            doAdd(entry);
        }
        addQueue.clear();
        for (Map<Object, Shape> shapes : queue.values()) {
            Iterator<Shape> itr = shapes.values().iterator();
            while (itr.hasNext()) {
                Shape shape = itr.next();
                if (tickCounter == shape.deathTime) {
                    itr.remove();
                }
                shape.tick();
            }
        }
    }

    public static void render(Layer layer, MatrixStack matrixStack, VertexConsumerProvider.Immediate vertexConsumerProvider, float delta) {
        if (!queue.containsKey(layer)) return;
        queue.get(layer).values().forEach(shape -> {
        	System.out.println("Drawing ".concat(shape.getName()));
        	shape.render(matrixStack, vertexConsumerProvider, delta);
        });
    }

    public enum Layer {
        ON_TOP
    }

    private static class AddQueueEntry {
        private final Layer layer;
        private final Object key;
        private final Shape shape;
        private final int life;

        private AddQueueEntry(Layer layer, Object key, Shape shape, int life) {
            this.layer = layer;
            this.key = key;
            this.shape = shape;
            this.life = life;
        }
    }
}
