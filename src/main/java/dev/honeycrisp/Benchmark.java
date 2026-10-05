package dev.honeycrisp;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * Reproducible FPS benchmark, enabled with -Dhoneycrisp.bench=<seconds>. Once a world is loaded it
 * pins the time to noon, stands the player on the surface facing a fixed direction, warms up, records
 * every frame time, writes a summary to honeycrisp-bench.txt and quits. Works on any backend.
 */
public final class Benchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SECONDS = Integer.getInteger("honeycrisp.bench", 0);
    private static final int SETTLE_TICKS = 100;
    private static final int SCENE_X = 5000;
    private static final int SCENE_Z = 5000;
    private static final long WARMUP_NS = 10_000_000_000L;

    private static int ticksInWorld;
    private static long measureStart;
    private static long lastFrame;
    private static long[] frameTimes = new long[1 << 16];
    private static int frames;
    private static boolean done;
    private static boolean shot;
    private static boolean shot2;
    private static int lastSequenceTick;
    private static boolean flatWorldOpened;

    private Benchmark() {
    }

    public static boolean enabled() {
        return SECONDS > 0;
    }

    /**
     * -Dhoneycrisp.autoTurn=<degrees per second> keeps turning the player and -Dhoneycrisp.autoWalk=true holds the
     * forward key, to load a world the way a player looking around does. Independent of the benchmark itself.
     */
    private static final float AUTO_TURN = Float.parseFloat(System.getProperty("honeycrisp.autoTurn", "0"));
    private static final boolean AUTO_WALK = Boolean.getBoolean("honeycrisp.autoWalk");
    private static long lastAutoTurn;

    public static boolean autoMoves() {
        return AUTO_TURN != 0.0F || AUTO_WALK;
    }

    public static void autoMove(final Minecraft minecraft) {
        long now = System.nanoTime();
        if (minecraft.player != null && minecraft.gui.screen() == null) {
            if (lastAutoTurn != 0L) {
                // Entity.turn takes mouse units of 0.15 degrees.
                minecraft.player.turn((now - lastAutoTurn) / 1e9 * AUTO_TURN / 0.15, 0.0);
            }
            if (AUTO_WALK) {
                minecraft.options.keyUp.setDown(true);
            }
        }
        lastAutoTurn = now;
    }

    private static net.minecraft.client.gui.screens.@org.jspecify.annotations.Nullable Screen answeredPrompt;

    /**
     * Unattended runs (the benchmark, autoTurn/autoWalk) open the world without a backup when Minecraft asks whether
     * to make one first, as it does for a world of an older version or with experimental settings (the flat test
     * worlds); nobody is there to press the button, and the run would sit on that screen.
     */
    public static void skipBackupPrompt(final Minecraft minecraft) {
        if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen prompt && prompt != answeredPrompt) {
            answeredPrompt = prompt;
            LOGGER.info("Honeycrisp bench: opening the world without the backup Minecraft asked about");
            ((dev.honeycrisp.mixin.BackupConfirmScreenAccessor)prompt).honeycrisp$onProceed().proceed(false, false);
        }
    }

    public static void onTick(final Minecraft minecraft) {
        // -Dhoneycrisp.benchFlatWorld=<name>: from the title screen, open that save, creating it first as a creative
        // superflat world (no water, no structures) if it does not exist yet.
        String flatWorld = System.getProperty("honeycrisp.benchFlatWorld");
        if (flatWorld != null && !flatWorldOpened && minecraft.level == null
            && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
            flatWorldOpened = true;
            if (minecraft.getLevelSource().levelExists(flatWorld)) {
                minecraft.createWorldOpenFlows().openWorld(flatWorld, () -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
            } else {
                minecraft.createWorldOpenFlows().createFreshLevel(
                    flatWorld,
                    new net.minecraft.world.level.LevelSettings(
                        flatWorld, net.minecraft.world.level.GameType.CREATIVE,
                        new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
                        true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT
                    ),
                    net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed().withStructures(false),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createTestWorldDimensions,
                    new net.minecraft.client.gui.screens.TitleScreen()
                );
            }
        }
        if (done || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticksInWorld++;
        boolean sceneMode = Boolean.getBoolean("honeycrisp.benchScene");
        if (sceneMode && ticksInWorld == SETTLE_TICKS / 2 && minecraft.getSingleplayerServer() != null) {
            // The scene lives at fixed far-off coordinates so runs don't build on top of each other or wherever the
            // last run left the player; move there first so the chunks are loaded when the scene is built.
            MinecraftServer server = minecraft.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "tp @a " + SCENE_X + " 230 " + SCENE_Z
            ));
        }
        if (sceneMode && ticksInWorld == SETTLE_TICKS + 60 && minecraft.getSingleplayerServer() != null) {
            // Light sources placed into freshly generated chunks can keep stale block light; re-placing them once the
            // chunks are settled makes the light engine propagate it properly.
            MinecraftServer server = minecraft.getSingleplayerServer();
            int x = SCENE_X;
            int z = SCENE_Z;
            int by = 200;
            String[] relight = {
                "setblock " + (x - 3) + " " + (by + 1) + " " + (z - 9) + " air", "setblock " + (x - 3) + " " + (by + 1) + " " + (z - 9) + " torch",
                "setblock " + (x + 3) + " " + (by + 1) + " " + (z - 13) + " air", "setblock " + (x + 3) + " " + (by + 1) + " " + (z - 13) + " torch",
                "setblock " + (x - 8) + " " + (by + 1) + " " + (z - 2) + " air", "setblock " + (x - 8) + " " + (by + 1) + " " + (z - 2) + " lantern"
            };
            server.execute(() -> {
                for (String command : relight) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                }
            });
        }
        if (sceneMode && (ticksInWorld == SETTLE_TICKS + 120 || ticksInWorld == SETTLE_TICKS + 140) && minecraft.getSingleplayerServer() != null) {
            // Chunk meshes built before the light finished propagating keep stale vertex light; swapping the floor
            // out and back forces them to be rebuilt once the light is settled.
            MinecraftServer server = minecraft.getSingleplayerServer();
            String block = ticksInWorld == SETTLE_TICKS + 120 ? "light_gray_concrete" : "white_concrete";
            String command = "fill " + (SCENE_X - 24) + " 200 " + (SCENE_Z - 24) + " " + (SCENE_X + 24) + " 200 " + (SCENE_Z + 24) + " " + block
                + " replace " + (ticksInWorld == SETTLE_TICKS + 120 ? "white_concrete" : "light_gray_concrete");
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
        }
        if (ticksInWorld == SETTLE_TICKS) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            int x = sceneMode ? SCENE_X : minecraft.player.getBlockX();
            int z = sceneMode ? SCENE_Z : minecraft.player.getBlockZ();
            int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
            if (server != null && sceneMode) {
                // Shadow test scene in the sky: a white floor, pillars, and an overhang.
                int by = 200;
                String[] scene = {
                    "time set " + Integer.getInteger("honeycrisp.benchTime", 6000), "weather " + System.getProperty("honeycrisp.benchWeather", "clear"), "gamerule advance_time false",
                    "gamerule doDaylightCycle false", "gamemode spectator @a",
                    "fill " + (x - 24) + " " + by + " " + (z - 24) + " " + (x + 24) + " " + by + " " + (z + 24) + " white_concrete",
                    "fill " + (x - 6) + " " + (by + 1) + " " + (z - 6) + " " + (x - 5) + " " + (by + 10) + " " + (z - 5) + " stone_bricks",
                    "fill " + (x + 5) + " " + (by + 1) + " " + (z - 8) + " " + (x + 6) + " " + (by + 6) + " " + (z - 7) + " oak_planks",
                    "fill " + (x - 2) + " " + (by + 6) + " " + (z + 2) + " " + (x + 6) + " " + (by + 6) + " " + (z + 10) + " glass",
                    "fill " + (x - 10) + " " + (by + 5) + " " + (z + 4) + " " + (x - 4) + " " + (by + 5) + " " + (z + 10) + " oak_leaves",
                    "fill " + (x + 8) + " " + (by + 1) + " " + (z - 16) + " " + (x + 22) + " " + (by + 1) + " " + (z - 2) + " white_concrete",
                    "fill " + (x + 9) + " " + (by + 1) + " " + (z - 15) + " " + (x + 21) + " " + (by + 1) + " " + (z - 3) + " water",
                    "fill " + (x + 12) + " " + (by + 2) + " " + (z - 9) + " " + (x + 13) + " " + (by + 5) + " " + (z - 8) + " oak_log",
                    // Stained glass and a leaf canopy, to check light passing through them.
                    "fill " + (x + 10) + " " + (by + 6) + " " + (z + 2) + " " + (x + 16) + " " + (by + 6) + " " + (z + 8) + " red_stained_glass",
                    "fill " + (x + 17) + " " + (by + 6) + " " + (z + 2) + " " + (x + 22) + " " + (by + 6) + " " + (z + 8) + " cyan_stained_glass",
                    "fill " + (x - 22) + " " + (by + 5) + " " + (z + 2) + " " + (x - 12) + " " + (by + 6) + " " + (z + 12) + " oak_leaves",
                    "fill " + (x - 22) + " " + (by + 1) + " " + (z + 14) + " " + (x - 12) + " " + (by + 1) + " " + (z + 20) + " short_grass",
                    // A flower bed near the camera (flowers need grass under them).
                    "fill " + (x - 6) + " " + by + " " + (z - 20) + " " + (x + 6) + " " + by + " " + (z - 17) + " grass_block",
                    "fill " + (x - 6) + " " + (by + 1) + " " + (z - 20) + " " + (x - 1) + " " + (by + 1) + " " + (z - 17) + " poppy",
                    "fill " + (x + 1) + " " + (by + 1) + " " + (z - 20) + " " + (x + 6) + " " + (by + 1) + " " + (z - 17) + " dandelion",
                    // Metals and a bell, for the sun highlight.
                    "setblock " + (x - 3) + " " + (by + 1) + " " + (z - 15) + " gold_block",
                    "setblock " + (x - 1) + " " + (by + 1) + " " + (z - 15) + " iron_block",
                    "setblock " + (x + 1) + " " + (by + 1) + " " + (z - 15) + " copper_block",
                    "setblock " + (x + 3) + " " + (by + 1) + " " + (z - 15) + " diamond_block",
                    "setblock " + (x + 5) + " " + (by + 1) + " " + (z - 15) + " bell",
                    "setblock " + (x + 7) + " " + (by + 1) + " " + (z - 15) + " yellow_wool",
                    // Ice next to the water, to compare the two.
                    "fill " + (x - 5) + " " + (by - 2) + " " + (z - 25) + " " + (x + 5) + " " + (by - 1) + " " + (z - 20) + " white_concrete",
                    "fill " + (x - 4) + " " + (by - 1) + " " + (z - 24) + " " + (x + 4) + " " + (by - 1) + " " + (z - 21) + " water",
                    "fill " + (x - 4) + " " + by + " " + (z - 24) + " " + (x + 4) + " " + by + " " + (z - 21) + " ice",
                    // Torches and a lantern for checking block light.
                    "setblock " + (x - 3) + " " + (by + 1) + " " + (z - 9) + " torch",
                    "setblock " + (x + 3) + " " + (by + 1) + " " + (z - 13) + " torch",
                    "setblock " + (x - 8) + " " + (by + 1) + " " + (z - 2) + " lantern",
                    "summon cow " + (x - 10) + " " + (by + 1) + " " + (z - 10) + " {NoAI:1b}",
                    "summon villager " + (x - 2) + " " + (by + 1) + " " + (z - 12) + " {NoAI:1b}",
                    "summon armor_stand " + (x + 2) + " " + (by + 1) + " " + (z - 6) + " {NoGravity:1b}",
                    "summon iron_golem " + (x - 14) + " " + (by + 1) + " " + (z - 4) + " {NoAI:1b}",
                    "tp @a " + x + " " + (by + 14) + " " + (z - 22) + " " + Integer.getInteger("honeycrisp.benchYaw", 0) + " "
                        + Integer.getInteger("honeycrisp.benchPitch", 35)
                };
                server.execute(() -> {
                    for (String command : scene) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                    }
                    if (System.getProperty("honeycrisp.benchWater") != null) {
                        buildWaterScene(server, x, z);
                    }
                    if (Boolean.getBoolean("honeycrisp.benchGlass")) buildGlassScene(server, x, by, z);
                    if (Boolean.getBoolean("honeycrisp.benchFoliage")) buildFoliageScene(server, x, by, z);
                    if (Boolean.getBoolean("honeycrisp.benchTrees")) buildTreeShadowScene(server, x, by, z);
                    if (Boolean.getBoolean("honeycrisp.benchFog")) buildFogScene(server, x, by, z);
                    if (Boolean.getBoolean("honeycrisp.benchReflection")) {
                        buildReflectionScene(server, x, z);
                    }
                });
            } else if (server != null && System.getProperty("honeycrisp.benchX") != null) {
                // -Dhoneycrisp.benchX/benchZ: stand on the ground at that spot, under any tree canopy.
                int bx = Integer.getInteger("honeycrisp.benchX", x);
                int bz = Integer.getInteger("honeycrisp.benchZ", z);
                server.execute(() -> {
                    // Ground under the canopy, but never inside leaves: spiral out until feet, head and the block above are air.
                    var world = server.overworld();
                    int px = bx;
                    int pz = bz;
                    int by = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                    search:
                    for (int r = 0; r <= 24; r++) {
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                    continue;
                                }
                                int cy = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx + dx, bz + dz);
                                boolean clear = true;
                                for (int h = 0; h < 3; h++) {
                                    clear &= world.getBlockState(new net.minecraft.core.BlockPos(bx + dx, cy + h, bz + dz)).isAir();
                                }
                                if (clear) {
                                    px = bx + dx;
                                    pz = bz + dz;
                                    by = cy;
                                    break search;
                                }
                            }
                        }
                    }
                    LOGGER.info("Honeycrisp bench: ground spot {} {} {}", px, by, pz);
                    final int fx = px;
                    final int fz = pz;
                    String[] commands = {
                        "time set " + Integer.getInteger("honeycrisp.benchTime", 6000), "weather " + System.getProperty("honeycrisp.benchWeather", "clear"),
                        // Holding an item needs a body: survival then (on its feet, never flying), so the player's own shadow can be checked.
                        "gamerule advance_time false", "gamerule advance_weather false",
                        System.getProperty("honeycrisp.benchItem") != null ? "gamemode survival @a" : "gamemode spectator @a",
                        "tp @a " + (fx + 0.5) + " " + (by + 0.2) + " " + (fz + 0.5) + " " + Integer.getInteger("honeycrisp.benchYaw", 135) + " " + Integer.getInteger("honeycrisp.benchPitch", 8)
                    };
                    for (String command : commands) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                    }
                });
            } else if (server != null) {
                // -Dhoneycrisp.benchPos=x,y,z: hover exactly there instead (in spectator mode: also in mid-air, under
                // water or in a cave). -Dhoneycrisp.benchRun=<command>;<command>: then run these at the player.
                String exact = System.getProperty("honeycrisp.benchPos");
                java.util.List<String> list = new java.util.ArrayList<>(java.util.List.of(
                    "time set " + Integer.getInteger("honeycrisp.benchTime", 6000), "weather " + System.getProperty("honeycrisp.benchWeather", "clear"), "gamerule advance_time false", "gamerule doDaylightCycle false",
                    "gamerule advance_weather false", exact != null ? "gamemode spectator @a" : "gamemode creative @a",
                    "tp @a " + (exact != null ? exact.replace(',', ' ') : x + " " + y + " " + z) + " " + Integer.getInteger("honeycrisp.benchYaw", 135) + " " + Integer.getInteger("honeycrisp.benchPitch", 8)
                ));
                for (String command : System.getProperty("honeycrisp.benchRun", "").split(";")) {
                    if (!command.isBlank()) {
                        list.add("execute as @p at @s run " + command.trim());
                    }
                }
                String[] commands = list.toArray(String[]::new);
                server.execute(() -> {
                    for (String command : commands) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                    }
                });
            }
            measureStart = System.nanoTime() + WARMUP_NS;
            LOGGER.info("Honeycrisp bench: positioned at {} {} {}, warming up", x, y, z);
        }
        // -Dhoneycrisp.benchItem=<item id>: hold that item in the main hand.
        if (System.getProperty("honeycrisp.benchItem") != null && ticksInWorld == SETTLE_TICKS + 30 && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "item replace entity @a weapon.mainhand with " + System.getProperty("honeycrisp.benchItem")
            ));
        }
        // -Dhoneycrisp.benchThirdPerson=true: view from behind, to compare the player's shadow with the first-person one.
        if (Boolean.getBoolean("honeycrisp.benchThirdPerson") && ticksInWorld == SETTLE_TICKS + 30) {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        }
        // -Dhoneycrisp.benchLightning=true: keep a lightning bolt standing a little ahead of the camera.
        if (Boolean.getBoolean("honeycrisp.benchLightning") && ticksInWorld > SETTLE_TICKS + 20 && ticksInWorld % 4 == 0
            && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "execute at @a run summon lightning_bolt ^-6 ^ ^18"
            ));
        }
        // -Dhoneycrisp.benchBreak=true (with benchScene): a small floor beside the main scene with a torch, where blocks
        // are broken over and over next to unbroken copies of themselves, to compare block-break debris with its block.
        int breakX = SCENE_X + 40;
        if (Boolean.getBoolean("honeycrisp.benchBreak") && sceneMode && ticksInWorld == SETTLE_TICKS + 30 && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            String[] scene = {
                "fill " + (breakX - 8) + " 240 " + (SCENE_Z - 8) + " " + (breakX + 8) + " 250 " + (SCENE_Z + 8) + " air",
                "fill " + (breakX - 8) + " 239 " + (SCENE_Z - 8) + " " + (breakX + 8) + " 239 " + (SCENE_Z + 8) + " smooth_stone",
                // A roofed corner (left of the camera) lit only by a torch, the planks out in the open.
                "fill " + (breakX + 2) + " 243 " + SCENE_Z + " " + (breakX + 8) + " 243 " + (SCENE_Z + 8) + " stone_bricks",
                "fill " + (breakX + 2) + " 240 " + (SCENE_Z + 8) + " " + (breakX + 8) + " 242 " + (SCENE_Z + 8) + " stone_bricks",
                "fill " + (breakX + 8) + " 240 " + SCENE_Z + " " + (breakX + 8) + " 242 " + (SCENE_Z + 8) + " stone_bricks",
                "setblock " + (breakX + 4) + " 240 " + (SCENE_Z + 4) + " torch",
                "setblock " + (breakX - 2) + " 240 " + (SCENE_Z + 4) + " oak_planks",
                "setblock " + (breakX + 3) + " 240 " + (SCENE_Z + 4) + " red_wool",
                "kill @e[type=!player]",
                "tp @a " + (breakX + 0.5) + " 240.4 " + (SCENE_Z - 1.0) + " 0 22"
            };
            server.execute(() -> {
                for (String command : scene) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                }
            });
        }
        if (Boolean.getBoolean("honeycrisp.benchBreak") && sceneMode && ticksInWorld > SETTLE_TICKS + 40 && ticksInWorld % 8 < 2
            && minecraft.getSingleplayerServer() != null) {
            // Placed, broken on the next tick, then left as air for a while, as when a player mines it.
            MinecraftServer server = minecraft.getSingleplayerServer();
            boolean place = ticksInWorld % 8 == 0;
            String[] spots = {(breakX - 2) + " 240 " + (SCENE_Z + 3), (breakX + 3) + " 240 " + (SCENE_Z + 3)};
            String[] blocks = {"oak_planks", "red_wool"};
            server.execute(() -> {
                for (int i = 0; i < spots.length; i++) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                        "setblock " + spots[i] + (place ? " " + blocks[i] : " air destroy"));
                }
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "kill @e[type=item]");
            });
        }
        // -Dhoneycrisp.benchTorch=true (with benchScene): a dry platform beside the main scene with torches and a
        // campfire, seen from close by, for checking their flame particles.
        if (Boolean.getBoolean("honeycrisp.benchTorch") && sceneMode && ticksInWorld == SETTLE_TICKS + 30 && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            int tx = SCENE_X - 60;
            String[] scene = {
                "fill " + (tx - 8) + " 200 " + (SCENE_Z - 8) + " " + (tx + 8) + " 212 " + (SCENE_Z + 8) + " air",
                "fill " + (tx - 8) + " 199 " + (SCENE_Z - 8) + " " + (tx + 8) + " 199 " + (SCENE_Z + 8) + " smooth_stone",
                "setblock " + tx + " 200 " + (SCENE_Z + 2) + " torch",
                "setblock " + (tx - 1) + " 200 " + (SCENE_Z + 2) + " soul_torch",
                "setblock " + (tx + 2) + " 200 " + (SCENE_Z + 3) + " campfire",
                "tp @a " + (tx + 0.5) + " 200 " + (SCENE_Z + 0.5) + " 0 20"
            };
            server.execute(() -> {
                for (String command : scene) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
                }
            });
        }
        // Let the normal interpolated day cycle run after positioning, for moving-shadow regression checks.
        if (ticksInWorld == SETTLE_TICKS + 40 && Boolean.getBoolean("honeycrisp.benchDayCycle") && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "gamerule advance_time true"
            ));
        }
        // Real footsteps and jumps exercise camera motion; benchMove's teleports do not.
        // Scene fixtures use the clear z+6 lane; location tests keep their chosen ground spot.
        if (Boolean.getBoolean("honeycrisp.benchWalk") || Boolean.getBoolean("honeycrisp.benchJump")) {
            if (ticksInWorld == SETTLE_TICKS + 160 && minecraft.getSingleplayerServer() != null) {
                MinecraftServer server = minecraft.getSingleplayerServer();
                server.execute(() -> {
                    var source = server.createCommandSourceStack().withSuppressedOutput();
                    server.getCommands().performPrefixedCommand(source, "gamemode adventure @a");
                    if (sceneMode) {
                        server.getCommands().performPrefixedCommand(source, "tp @a " + (SCENE_X - 18) + " 201 " + (SCENE_Z + 6) + " -90 "
                            + (Boolean.getBoolean("honeycrisp.benchTrees") ? 25 : 0));
                    }
                });
            }
            boolean walking = ticksInWorld > SETTLE_TICKS + 180 && ticksInWorld < SETTLE_TICKS + 340;
            minecraft.options.keyUp.setDown(walking && Boolean.getBoolean("honeycrisp.benchWalk"));
            minecraft.options.keySprint.setDown(walking && Boolean.getBoolean("honeycrisp.benchSprint"));
            minecraft.options.keyJump.setDown(walking && Boolean.getBoolean("honeycrisp.benchJump"));
            if (walking && ticksInWorld % 40 == 0) {
                LOGGER.info("Honeycrisp bench: walking position={} grounded={} sprinting={} velocity={}", minecraft.player.position(),
                    minecraft.player.onGround(), minecraft.player.isSprinting(), minecraft.player.getDeltaMovement());
            }
        }
        // -Dhoneycrisp.benchMove=<blocks per second>: walk the camera along +X after settling, for motion tests.
        float move = Float.parseFloat(System.getProperty("honeycrisp.benchMove", "0"));
        if (move != 0.0F && ticksInWorld > SETTLE_TICKS + 40 && minecraft.getSingleplayerServer() != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "execute as @a at @s run tp @s ~" + (move / 20.0F) + " ~ ~"
            ));
        }
        if (ticksInWorld % 100 == 0) {
            LOGGER.info(
                "Honeycrisp bench: limit={} reason={} screen={}", minecraft.getFramerateLimitTracker().getFramerateLimit(),
                minecraft.getFramerateLimitTracker().getThrottleReason(), minecraft.gui.screen()
            );
        }
    }

    /** A clean sprint lane under separate persistent canopies, without leftovers from other fixtures. */
    private static void buildTreeShadowScene(final MinecraftServer server, final int x, final int y, final int z) {
        var source = server.createCommandSourceStack().withSuppressedOutput();
        for (int dy = 1; dy <= 64; dy += 4) {
            server.getCommands().performPrefixedCommand(source, "fill " + (x - 32) + " " + (y + dy) + " " + (z - 24)
                + " " + (x + 64) + " " + (y + dy + 3) + " " + (z + 24) + " air");
        }
        server.getCommands().performPrefixedCommand(source, "fill " + (x - 32) + " " + y + " " + (z - 24)
            + " " + (x + 64) + " " + y + " " + (z + 24) + " white_concrete");
        for (int dx = -16; dx <= 48; dx += 12) {
            server.getCommands().performPrefixedCommand(source, "fill " + (x + dx) + " " + (y + 1) + " " + (z + 1)
                + " " + (x + dx) + " " + (y + 7) + " " + (z + 1) + " oak_log");
            server.getCommands().performPrefixedCommand(source, "fill " + (x + dx - 4) + " " + (y + 6) + " " + (z - 2)
                + " " + (x + dx + 4) + " " + (y + 8) + " " + (z + 10) + " oak_leaves[persistent=true]");
        }
        server.getCommands().performPrefixedCommand(source, "kill @e[type=!player]");
    }

    /** Foreground tree outside the pool, plus a real reflector on the far bank. Use a disposable save. */
    private static void buildReflectionScene(final MinecraftServer server, final int x, final int z) {
        var source = server.createCommandSourceStack().withSuppressedOutput();
        String[] scene = {
            "fill " + (x - 24) + " 201 " + (z - 24) + " " + (x + 40) + " 210 " + (z + 24) + " air",
            "fill " + (x - 24) + " 211 " + (z - 24) + " " + (x + 40) + " 220 " + (z + 24) + " air",
            "fill " + (x - 12) + " 200 " + (z - 24) + " " + (x + 40) + " 201 " + (z + 24) + " stone_bricks",
            "fill " + (x - 11) + " 201 " + (z - 23) + " " + (x + 39) + " 201 " + (z + 23) + " water",
            "fill " + (x - 15) + " 201 " + (z - 3) + " " + (x - 14) + " 226 " + (z - 2) + " oak_log",
            "fill " + (x - 18) + " 222 " + (z - 5) + " " + (x - 8) + " 225 " + (z + 3) + " oak_leaves[persistent=true]",
            "fill " + (x - 15) + " 222 " + (z - 3) + " " + (x - 7) + " 223 " + (z - 2) + " oak_log",
            "fill " + (x + 25) + " 202 " + (z + 1) + " " + (x + 27) + " 223 " + (z + 3) + " red_concrete",
            "fill " + (x + 25) + " 212 " + (z + 1) + " " + (x + 27) + " 214 " + (z + 3) + " white_concrete",
            "kill @e[type=!player]", "effect clear @a", "gamemode spectator @a",
            "tp @a " + (x - 18) + " 215 " + z + " -90 30"
        };
        for (String command : scene) server.getCommands().performPrefixedCommand(source, command);
        // -PbenchEyeY=<height>: look across the water from that eye height at a striped far bank, past a dirt block
        // floating two blocks above the water just ahead (a foreground block hiding what the water behind it reflects).
        String eye = System.getProperty("honeycrisp.benchEyeY");
        if (eye != null) {
            for (int dz = -24; dz < 24; dz += 4) {
                server.getCommands().performPrefixedCommand(source, "fill " + (x + 40) + " 202 " + (z + dz) + " " + (x + 40) + " 209 " + (z + dz + 3)
                    + ((dz / 4 & 1) == 0 ? " lime_concrete" : " orange_concrete"));
            }
            server.getCommands().performPrefixedCommand(source, "setblock " + (x - 4) + " 203 " + z + " dirt");
            server.getCommands().performPrefixedCommand(source, "tp @a " + (x - 8) + " " + (Double.parseDouble(eye) - 1.62) + " " + (z + 0.5) + " -90 "
                + Integer.getInteger("honeycrisp.benchPitch", 5));
        }
    }

    /** Isolated water tank with sun-blocking slats, submerged pillars and a tiled floor. Use a disposable save. */
    private static void buildWaterScene(final MinecraftServer server, final int x, final int z) {
        var source = server.createCommandSourceStack().withSuppressedOutput();
        String[] tank = {
            "fill " + (x - 24) + " 201 " + (z - 24) + " " + (x + 24) + " 213 " + (z + 24) + " stone_bricks hollow",
            "fill " + (x - 24) + " 213 " + (z - 24) + " " + (x + 24) + " 213 " + (z + 24) + " air",
            "fill " + (x - 23) + " 201 " + (z - 23) + " " + (x + 23) + " 211 " + (z + 23) + " water",
            "fill " + (x - 23) + " 200 " + (z - 23) + " " + (x + 23) + " 200 " + (z + 23) + " sand",
            "fill " + (x + 1) + " 201 " + (z - 6) + " " + (x + 2) + " 209 " + (z - 5) + " stone_bricks",
            "fill " + (x + 8) + " 201 " + (z + 4) + " " + (x + 9) + " 214 " + (z + 5) + " stone_bricks",
            "gamemode creative @a",
            "effect give @a water_breathing infinite 0 true",
            "kill @e[type=!player]",
            "tp @a " + (x - 16) + " 204 " + z + " -90 " + Integer.getInteger("honeycrisp.benchPitch", -12)
        };
        for (String command : tank) server.getCommands().performPrefixedCommand(source, command);
        if ("open".equals(System.getProperty("honeycrisp.benchWater"))) {
            // Open-sky tank with a beach rising out of the deep end: depth gradient, shoreline and sun glitter.
            // Sandstone, because sand over the floating scene would fall out of the tank.
            server.getCommands().performPrefixedCommand(source, "fill " + (x - 23) + " 200 " + (z - 23) + " " + (x + 23) + " 200 " + (z + 23)
                + " smooth_sandstone");
            for (int i = 0; i <= 11; i++) {
                server.getCommands().performPrefixedCommand(source, "fill " + (x + 12 + i) + " 201 " + (z - 23) + " "
                    + (x + 12 + i) + " " + (201 + i) + " " + (z + 23) + " smooth_sandstone");
            }
            server.getCommands().performPrefixedCommand(source, "gamemode spectator @a");
            server.getCommands().performPrefixedCommand(source, "tp @a " + (x + Integer.getInteger("honeycrisp.benchCamX", -20)) + " "
                + Integer.getInteger("honeycrisp.benchCamY", 216) + " " + z + " " + Integer.getInteger("honeycrisp.benchYaw", -90) + " "
                + Integer.getInteger("honeycrisp.benchPitch", 20));
            return;
        }
        for (int dx = -12; dx <= 20; dx += 8) {
            server.getCommands().performPrefixedCommand(source, "fill " + (x + dx) + " 213 " + (z - 24) + " "
                + (x + dx + 2) + " 213 " + (z + 24) + " oak_planks");
            server.getCommands().performPrefixedCommand(source, "fill " + (x + dx) + " 200 " + (z - 23) + " "
                + (x + dx) + " 200 " + (z + 23) + " prismarine");
        }
        if ("covered".equals(System.getProperty("honeycrisp.benchWater"))) {
            server.getCommands().performPrefixedCommand(source, "fill " + (x - 24) + " 213 " + (z - 24) + " "
                + (x + 24) + " 213 " + (z + 24) + " stone_bricks");
        }
        if ("surface".equals(System.getProperty("honeycrisp.benchWater"))) {
            server.getCommands().performPrefixedCommand(source, "gamemode spectator @a");
            server.getCommands().performPrefixedCommand(source, "tp @a " + (x - 16) + " 214 " + z + " -90 25");
        }
    }

    public static void onFrame(final Minecraft minecraft) {
        if (done || measureStart == 0L) {
            return;
        }
        long now = System.nanoTime();
        if (Boolean.getBoolean("honeycrisp.benchSequence") && ticksInWorld > SETTLE_TICKS + 180
            && ticksInWorld < SETTLE_TICKS + 300 && ticksInWorld >= lastSequenceTick + 2) {
            lastSequenceTick = ticksInWorld;
            LOGGER.info("Honeycrisp bench: sequence tick={} position={}", ticksInWorld, minecraft.player.position());
            Screenshot.grab(minecraft, false);
        }
        if (now >= measureStart) {
            if (lastFrame != 0L) {
                if (frames == frameTimes.length) {
                    frameTimes = Arrays.copyOf(frameTimes, frames * 2);
                }
                frameTimes[frames++] = now - lastFrame;
            }
            // -Dhoneycrisp.benchShot=true saves a screenshot two seconds before the run ends (the readback is asynchronous).
            if (!shot && Boolean.getBoolean("honeycrisp.benchShot") && now - measureStart >= Math.max(0L, SECONDS - 2L) * 1_000_000_000L) {
                shot = true;
                float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                LOGGER.info(
                    "Honeycrisp bench: screenshot at {} yaw={} pitch={} sun angle={} rain={}", minecraft.player.position(), minecraft.player.getYRot(),
                    minecraft.player.getXRot(),
                    minecraft.gameRenderer.mainCamera().attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, partialTick),
                    minecraft.level.getRainLevel(partialTick)
                );
                if (Boolean.getBoolean("honeycrisp.benchScene")) {
                    var block = minecraft.level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
                    var sky = minecraft.level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
                    StringBuilder sb = new StringBuilder();
                    for (int d = 0; d <= 6; d += 2) {
                        var pos = new net.minecraft.core.BlockPos(SCENE_X - 3 + d, 201, SCENE_Z - 9);
                        sb.append(" d=").append(d).append(" block=").append(block.getLightValue(pos)).append(" sky=").append(sky.getLightValue(pos))
                            .append(" state=").append(minecraft.level.getBlockState(pos).getBlock().getDescriptionId());
                    }
                    LOGGER.info("Honeycrisp bench: light near torch{}", sb);
                }
                Screenshot.grab(minecraft, false);
            } else if (!shot2 && Boolean.getBoolean("honeycrisp.benchShot2") && now - measureStart >= Math.max(0L, SECONDS - 3L) * 1_000_000_000L) {
                shot2 = true;
                Screenshot.grab(minecraft, false);
            }
            if (now - measureStart >= SECONDS * 1_000_000_000L) {
                finish(minecraft);
                return;
            }
        }
        lastFrame = now;
    }

    /** A garden beside the platform for waving plants: grass, two-block plants, crops, sugar cane, leaves and vines. */
    private static void buildFoliageScene(final MinecraftServer server, final int x, final int y, final int z) {
        int gx = x + 30;
        var c = new java.util.ArrayList<String>();
        c.add("fill " + (gx - 2) + " " + (y + 1) + " " + (z - 16) + " " + (gx + 18) + " " + (y + 12) + " " + (z + 10) + " air");
        c.add("fill " + (gx - 2) + " " + y + " " + (z - 16) + " " + (gx + 18) + " " + y + " " + (z + 10) + " grass_block");
        c.add("fill " + gx + " " + (y + 1) + " " + (z - 6) + " " + (gx + 16) + " " + (y + 1) + " " + (z - 6) + " short_grass");
        c.add("fill " + gx + " " + (y + 1) + " " + (z - 4) + " " + (gx + 16) + " " + (y + 1) + " " + (z - 4) + " tall_grass[half=lower]");
        c.add("fill " + gx + " " + (y + 2) + " " + (z - 4) + " " + (gx + 16) + " " + (y + 2) + " " + (z - 4) + " tall_grass[half=upper]");
        String[] tall = {"sunflower", "rose_bush", "lilac", "peony", "large_fern"};
        for (int i = 0; i < tall.length; i++) {
            c.add("setblock " + (gx + 1 + i * 3) + " " + (y + 1) + " " + (z - 2) + " " + tall[i] + "[half=lower]");
            c.add("setblock " + (gx + 1 + i * 3) + " " + (y + 2) + " " + (z - 2) + " " + tall[i] + "[half=upper]");
        }
        c.add("fill " + gx + " " + y + " " + z + " " + (gx + 7) + " " + y + " " + z + " farmland[moisture=7]");
        c.add("fill " + gx + " " + (y + 1) + " " + z + " " + (gx + 7) + " " + (y + 1) + " " + z + " wheat[age=7]");
        c.add("fill " + (gx + 9) + " " + (y + 1) + " " + z + " " + (gx + 16) + " " + (y + 1) + " " + z + " poppy");
        c.add("fill " + gx + " " + y + " " + (z + 3) + " " + (gx + 4) + " " + y + " " + (z + 3) + " water");
        c.add("fill " + gx + " " + (y + 1) + " " + (z + 2) + " " + (gx + 4) + " " + (y + 3) + " " + (z + 2) + " sugar_cane");
        c.add("fill " + (gx + 10) + " " + (y + 1) + " " + (z + 4) + " " + (gx + 10) + " " + (y + 4) + " " + (z + 4) + " oak_log");
        c.add("fill " + (gx + 8) + " " + (y + 4) + " " + (z + 2) + " " + (gx + 12) + " " + (y + 6) + " " + (z + 6) + " oak_leaves[persistent=true] replace air");
        c.add("fill " + (gx + 8) + " " + (y + 2) + " " + (z + 1) + " " + (gx + 12) + " " + (y + 3) + " " + (z + 1) + " vine[south=true]");
        c.add("kill @e[type=item]");
        c.add("tp @a " + (gx + 8.5) + " " + (y + 2.6) + " " + (z - 9.5) + " " + Integer.getInteger("honeycrisp.benchYaw", 0) + " "
            + Integer.getInteger("honeycrisp.benchPitch", 12));
        for (String command : c) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** Coloured windows project onto a white floor and the air in front of them. */
    private static void buildGlassScene(final MinecraftServer server, final int x, final int y, final int z) {
        var commands = new java.util.ArrayList<String>();
        for (int dy = 1; dy <= 32; dy += 8) {
            commands.add("fill " + (x - 24) + " " + (y + dy) + " " + (z - 24) + " " + (x + 40) + " " + (y + dy + 7) + " " + (z + 24) + " air");
        }
        commands.add("fill " + (x - 24) + " " + y + " " + (z - 24) + " " + (x + 40) + " " + y + " " + (z + 24) + " white_concrete");
        String[] glass = {"red_stained_glass", "lime_stained_glass", "blue_stained_glass"};
        for (int i = 0; i < 3; i++) {
            int dz = -18 + i * 12;
            commands.add("fill " + (x + 16) + " " + (y + 4) + " " + (z + dz) + " " + (x + 16) + " " + (y + 24) + " " + (z + dz + 10) + " " + glass[i]);
        }
        commands.add("kill @e[type=!player]");
        commands.add("tp @a " + (x - 20.5) + " " + (y + 6) + " " + (z + 20.5) + " -115 18");
        for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    // A distant slatted wall cuts beams through mist beyond the old 96-block volume.
    private static void buildFogScene(final MinecraftServer server, final int x, final int y, final int z) {
        var commands = new java.util.ArrayList<String>();
        commands.add("fill " + (x - 24) + " " + (y + 1) + " " + (z - 24) + " " + (x + 24) + " " + (y + 12) + " " + (z + 24) + " air");
        for (int dx = -20; dx < 200; dx += 20) {
            commands.add("fill " + (x + dx) + " " + y + " " + (z - 60) + " " + (x + dx + 19) + " " + y + " " + (z + 60) + " stone");
        }
        for (int dz = -48; dz <= 48; dz += 12) {
            commands.add("fill " + (x + 145) + " " + (y + 1) + " " + (z + dz) + " " + (x + 147) + " " + (y + 95) + " " + (z + dz + 5) + " stone_bricks");
        }
        commands.add("tp @a " + (x - 16.5) + " " + (y + 5) + " " + (z + 0.5) + " -90 -8");
        for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void finish(final Minecraft minecraft) {
        done = true;
        if (Boolean.getBoolean("honeycrisp.benchWalk") || Boolean.getBoolean("honeycrisp.benchJump")) {
            minecraft.options.keyUp.setDown(false);
            minecraft.options.keySprint.setDown(false);
            minecraft.options.keyJump.setDown(false);
        }
        long[] sorted = Arrays.copyOf(frameTimes, frames);
        Arrays.sort(sorted);
        double totalSec = Arrays.stream(sorted).sum() / 1e9;
        double median = 1e9 / sorted[sorted.length / 2];
        double average = frames / totalSec;
        double low1 = 1e9 / sorted[(int)(sorted.length * 0.99)];
        var window = minecraft.getWindow();
        String summary = String.format(
            Locale.ROOT,
            "backend=%s device=%s resolution=%dx%d frames=%d median_fps=%.1f avg_fps=%.1f one_percent_low_fps=%.1f",
            RenderSystem.getDevice().getDeviceInfo().backendName(), RenderSystem.getDevice().getDeviceInfo().name(),
            window.getWidth(), window.getHeight(), frames, median, average, low1
        );
        LOGGER.info("Honeycrisp bench: {}", summary);
        try {
            Files.writeString(Path.of("honeycrisp-bench.txt"), summary + "\n");
        } catch (IOException e) {
            LOGGER.error("Failed to write benchmark result", e);
        }
        minecraft.stop();
    }
}
