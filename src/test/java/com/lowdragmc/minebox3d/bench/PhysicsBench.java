package com.lowdragmc.minebox3d.bench;

import com.lowdragmc.minebox3d.natives.NativeLibrary;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyBatch;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.CastResult;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.MeshData;
import com.lowdragmc.minebox3d.physics.PhysicsWorld;
import com.lowdragmc.minebox3d.physics.Profile;
import com.lowdragmc.minebox3d.physics.QueryFilter;
import com.lowdragmc.minebox3d.physics.Shape;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import com.lowdragmc.minebox3d.physics.VoxelGrid;
import com.lowdragmc.minebox3d.physics.WorldDef;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.joml.Quaternionf;

/**
 * The numbers behind the performance page of the wiki and the README: {@code ./gradlew bench}, results in
 * {@code build/bench/results.json}. Every world steps the way a level does by default: 3 steps of 1/60 s, 4 substeps
 * each, per 50 ms Minecraft tick.
 */
public final class PhysicsBench {

    static final float STEP = 1 / 60f;
    static final int SUB_STEPS = 4;
    static final int STEPS_PER_TICK = 3;
    static final float GRAVITY = -32;
    /** The terrain's width in blocks, a square of 6 x 6 sections. */
    static final int TERRAIN = 96;

    static final float[] FULL = {0, 0, 0, 1, 1, 1};
    static final float[] SLAB = {0, 0, 0, 1, 0.5f, 1};
    static final float[] STAIRS = {0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0.5f, 1, 1, 1};

    private final Map<String, Object> results = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/bench");
        boolean quick = args.length > 1 && args[1].equals("quick");
        NativeLibrary.load();
        new PhysicsBench().run(out, quick);
    }

    private void run(Path out, boolean quick) throws IOException {
        results.put("machine", machine());
        results.put("settings", Map.of("stepsPerTick", STEPS_PER_TICK, "subSteps", SUB_STEPS, "step", STEP));

        log("warm-up");
        rain(800, 1, 120);

        int[] counts = quick ? new int[] {500, 2000} : new int[] {500, 1000, 2000, 4000, 8000};
        int[] workers = quick ? new int[] {1, 4} : new int[] {1, 2, 4, 8};
        int ticks = quick ? 100 : 300;
        List<Object> rain = new ArrayList<>();
        for (int w : workers) {
            for (int n : counts) {
                log("rain " + n + " bodies, " + w + " thread(s)");
                rain.add(rain(n, w, ticks));
            }
        }
        results.put("rain", rain);

        log("terrain edits");
        results.put("terrain", terrainEdits(quick ? 50 : 400));
        log("kinematic driving");
        results.put("kinematic", kinematic(quick ? 200 : 1000, quick ? 50 : 300));
        log("ray casts");
        results.put("rays", rays(quick ? 20_000 : 200_000));

        Files.createDirectories(out);
        Files.writeString(out.resolve("results.json"), Json.write(results) + "\n");
        log("wrote " + out.resolve("results.json").toAbsolutePath());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rain: bodies dropped onto rolling terrain, falling, hitting and piling up

    private Map<String, Object> rain(int count, int workers, int ticks) {
        try (PhysicsWorld world = PhysicsWorld.create(
                        new WorldDef().gravity(0, GRAVITY, 0).workerCount(workers));
                VoxelGrid grid = terrain(world)) {
            dropBodies(world, count, new Random(42));
            Profile profile = new Profile();
            double[] tickMs = new double[ticks];
            double[] nativeMs = new double[ticks];
            long awake = 0;
            for (int t = 0; t < ticks; t++) {
                double nativeSum = 0;
                long start = System.nanoTime();
                for (int s = 0; s < STEPS_PER_TICK; s++) {
                    world.step(STEP, SUB_STEPS);
                    nativeSum += world.profile(profile).step;
                }
                tickMs[t] = (System.nanoTime() - start) / 1e6;
                nativeMs[t] = nativeSum;
                awake += world.awakeBodyCount();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("bodies", count);
            r.put("threads", workers);
            r.put("ticks", ticks);
            r.put("tickMs", stats(tickMs));
            r.put("nativeStepMs", stats(nativeMs));
            r.put("meanAwake", awake / (double) ticks);
            r.put("sections", grid.sectionCount());
            return r;
        }
    }

    /** Rolling hills of full blocks, with slabs and stairs where the ground steps up by one. */
    static VoxelGrid terrain(PhysicsWorld world) {
        VoxelGrid grid = world.createVoxelGrid(new ShapeDef());
        int material = grid.addMaterial(new SurfaceMaterial().friction(0.6f));
        int full = grid.addCollider(FULL, 1, material);
        int slab = grid.addCollider(SLAB, 1, material);
        int stairs = grid.addCollider(STAIRS, 2, material);
        int sections = TERRAIN / VoxelGrid.SECTION_SIZE;
        for (int sx = 0; sx < sections; sx++) {
            for (int sz = 0; sz < sections; sz++) {
                for (int sy = 0; sy < 2; sy++) {
                    short[] cells = new short[VoxelGrid.SECTION_CELLS];
                    boolean any = false;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int wx = sx * 16 + x, wz = sz * 16 + z;
                            int h = height(wx, wz);
                            for (int y = 0; y < 16; y++) {
                                int wy = sy * 16 + y;
                                int id = wy < h ? full : wy == h ? cap(wx, wz, h, slab, stairs) : 0;
                                cells[x + 16 * z + 256 * y] = (short) id;
                                any |= id != 0;
                            }
                        }
                    }
                    if (any) {
                        grid.setSection(sx, sy, sz, cells);
                    }
                }
            }
        }
        return grid;
    }

    static int height(int x, int z) {
        double h = 9 + 4 * Math.sin(x / 9.0) + 3 * Math.cos(z / 7.0) + 2 * Math.sin((x + z) / 13.0);
        return (int) Math.floor(h);
    }

    /** What sits on the ground at a column: a slab or a stair on one in five steps up, else nothing. */
    private static int cap(int x, int z, int h, int slab, int stairs) {
        boolean stepUp = height(x + 1, z) > h || height(x - 1, z) > h || height(x, z + 1) > h || height(x, z - 1) > h;
        if (!stepUp || Math.floorMod(x * 31 + z * 17, 5) != 0) {
            return 0;
        }
        return Math.floorMod(x + z, 2) == 0 ? slab : stairs;
    }

    /** A third each of boxes, spheres and capsules, in layers over the terrain's middle, set spinning a little. */
    private static void dropBodies(PhysicsWorld world, int count, Random random) {
        ShapeDef shape = new ShapeDef().density(500).friction(0.6f);
        int side = 64;
        double spacing = 1.25;
        int perRow = (int) (side / spacing);
        for (int i = 0; i < count; i++) {
            int layer = i / (perRow * perRow);
            int row = (i / perRow) % perRow, column = i % perRow;
            double x = 16 + column * spacing, z = 16 + row * spacing, y = 24 + layer * spacing;
            Body body = world.createBody(new BodyDef()
                    .type(BodyType.DYNAMIC)
                    .position(x, y, z)
                    .linearVelocity((float) random.nextGaussian(), 0, (float) random.nextGaussian())
                    .angularVelocity((float) random.nextGaussian(), (float) random.nextGaussian(), (float)
                            random.nextGaussian()));
            body.createShape(
                    shape,
                    switch (i % 3) {
                        case 0 -> Geometry.Box.of(0.4f, 0.4f, 0.4f);
                        case 1 -> new Geometry.Sphere(0, 0, 0, 0.4f);
                        default -> new Geometry.Capsule(0, -0.3f, 0, 0, 0.3f, 0, 0.3f);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Terrain edits: what a section and a block change cost, against rebuilding the section as a triangle mesh

    private Map<String, Object> terrainEdits(int rounds) {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
                VoxelGrid grid = world.createVoxelGrid(new ShapeDef())) {
            int material = grid.addMaterial(new SurfaceMaterial());
            int full = grid.addCollider(FULL, 1, material);
            short[] cells = new short[VoxelGrid.SECTION_CELLS];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int h = height(x + 40, z + 40) - 2;
                    for (int y = 0; y < Math.min(h, 16); y++) {
                        cells[x + 16 * z + 256 * y] = (short) full;
                    }
                }
            }
            float[] mesh = exposedFaces(cells);
            int[] indices = new int[mesh.length / 3];
            Body ground = world.createBody(new BodyDef());
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i;
            }

            double[] section = new double[rounds];
            double[] edit = new double[rounds];
            double[] meshBuild = new double[rounds];
            int[] oneEdit = new int[4];
            for (int r = 0; r < rounds; r++) {
                long start = System.nanoTime();
                grid.setSection(r % 8, 0, 0, cells);
                section[r] = (System.nanoTime() - start) / 1e3;

                oneEdit[0] = 3 + r % 10;
                oneEdit[1] = 12;
                oneEdit[2] = 5;
                oneEdit[3] = r % 2 == 0 ? full : 0;
                start = System.nanoTime();
                grid.setCells(oneEdit, 1);
                edit[r] = (System.nanoTime() - start) / 1e3;

                start = System.nanoTime();
                MeshData data = MeshData.create(mesh, indices, true);
                Shape shape = ground.createShape(new ShapeDef(), Geometry.Mesh.of(data));
                meshBuild[r] = (System.nanoTime() - start) / 1e3;
                shape.destroy(false);
                data.close();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sectionReplaceUs", stats(section));
            r.put("blockEditUs", stats(edit));
            r.put("meshRebuildUs", stats(meshBuild));
            r.put("meshTriangles", indices.length / 3);
            return r;
        }
    }

    /** Two triangles per block face with air beside it, the plainest mesh of the section (no merging). */
    private static float[] exposedFaces(short[] cells) {
        List<Float> v = new ArrayList<>();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (cells[x + 16 * z + 256 * y] == 0) {
                        continue;
                    }
                    for (int[] d : dirs) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        boolean inside = nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16;
                        if (inside && cells[nx + 16 * nz + 256 * ny] != 0) {
                            continue;
                        }
                        face(v, x, y, z, d);
                    }
                }
            }
        }
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    /** The face of cell (x, y, z) facing d, counter-clockwise seen from outside. */
    private static void face(List<Float> v, int x, int y, int z, int[] d) {
        float[][] corners = new float[4][];
        int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        float plane = d[axis] > 0 ? 1 : 0;
        int[][] uv = d[axis] > 0
                ? new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}
                : new int[][] {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (int i = 0; i < 4; i++) {
            float[] p = new float[3];
            p[axis] = plane;
            p[u] = uv[i][0];
            p[w] = uv[i][1];
            corners[i] = new float[] {x + p[0], y + p[1], z + p[2]};
        }
        for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
            v.add(corners[i][0]);
            v.add(corners[i][1]);
            v.add(corners[i][2]);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Kinematic driving: moving bodies along a path one call each, or all in one call

    private Map<String, Object> kinematic(int count, int rounds) {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0))) {
            Body[] bodies = new Body[count];
            for (int i = 0; i < count; i++) {
                bodies[i] =
                        world.createBody(new BodyDef().type(BodyType.KINEMATIC).position(i * 2.0, 4, 0));
                bodies[i].createShape(new ShapeDef(), Geometry.Box.of(0.3f, 0.9f, 0.3f));
            }
            BodyBatch batch = new BodyBatch(bodies);
            double[] transforms = new double[count * BodyBatch.TRANSFORM];
            Quaternionf rotation = new Quaternionf();
            double[] single = new double[rounds];
            double[] batched = new double[rounds];
            for (int r = 0; r < rounds; r++) {
                double offset = Math.sin(r * 0.1);
                long start = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    bodies[i].setTargetTransform(i * 2.0 + offset, 4, 0, rotation, STEP);
                }
                single[r] = (System.nanoTime() - start) / 1e3;
                world.step(STEP, SUB_STEPS);

                for (int i = 0; i < count; i++) {
                    int o = i * BodyBatch.TRANSFORM;
                    transforms[o] = i * 2.0 - offset;
                    transforms[o + 1] = 4;
                    transforms[o + 2] = 0;
                    transforms[o + 6] = 1;
                }
                start = System.nanoTime();
                batch.setTargetTransforms(transforms, STEP);
                batched[r] = (System.nanoTime() - start) / 1e3;
                world.step(STEP, SUB_STEPS);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("bodies", count);
            r.put("oneCallEachUs", stats(single));
            r.put("batchUs", stats(batched));
            return r;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ray casts: the closest hit along 48 m rays over the terrain with a few thousand bodies resting on it

    private Map<String, Object> rays(int count) {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, GRAVITY, 0));
                VoxelGrid grid = terrain(world)) {
            dropBodies(world, 2000, new Random(7));
            for (int t = 0; t < 200; t++) {
                for (int s = 0; s < STEPS_PER_TICK; s++) {
                    world.step(STEP, SUB_STEPS);
                }
            }
            Random random = new Random(1);
            CastResult result = new CastResult();
            for (int i = 0; i < count / 10; i++) {
                cast(world, random, result);
            }
            int hits = 0;
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                hits += cast(world, random, result) ? 1 : 0;
            }
            double us = (System.nanoTime() - start) / 1e3 / count;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rays", count);
            r.put("bodies", world.bodyCount());
            r.put("usPerRay", us);
            r.put("hitFraction", hits / (double) count);
            return r;
        }
    }

    private static boolean cast(PhysicsWorld world, Random random, CastResult result) {
        double x = 8 + random.nextDouble() * (TERRAIN - 16), z = 8 + random.nextDouble() * (TERRAIN - 16);
        float dx = (float) random.nextGaussian() * 8, dz = (float) random.nextGaussian() * 8;
        return world.castRayClosest(x, 40, z, dx, -48, dz, QueryFilter.ALL, result);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static Map<String, Object> stats(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mean", sum / values.length);
        r.put("p50", sorted[sorted.length / 2]);
        r.put("p95", sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)]);
        r.put("max", sorted[sorted.length - 1]);
        return r;
    }

    private static Map<String, Object> machine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        m.put("cpu", System.getProperty("minebox3d.bench.cpu", "unknown"));
        m.put("hardwareThreads", Runtime.getRuntime().availableProcessors());
        m.put(
                "jvmArgs",
                ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                        .filter(a -> a.startsWith("-Xm"))
                        .toList());
        return m;
    }

    private static void log(String message) {
        System.out.println("[bench] " + message);
    }

    /** Enough JSON for maps, lists, strings and numbers. */
    static final class Json {
        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, "");
            return out.toString();
        }

        private static void write(Object value, StringBuilder out, String indent) {
            if (value instanceof Map<?, ?> map) {
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.append(indent).append("  \"").append(e.getKey()).append("\": ");
                    write(e.getValue(), out, indent + "  ");
                    out.append(++i < map.size() ? ",\n" : "\n");
                }
                out.append(indent).append('}');
            } else if (value instanceof List<?> list) {
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    out.append(indent).append("  ");
                    write(list.get(i), out, indent + "  ");
                    out.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                out.append(indent).append(']');
            } else if (value instanceof Double d) {
                out.append(String.format(Locale.ROOT, "%.4f", d));
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"')
                        .append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
        }
    }
}
