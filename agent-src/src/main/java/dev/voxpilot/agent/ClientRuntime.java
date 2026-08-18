package dev.voxpilot.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Mod.EventBusSubscriber(modid = VoxPilotAgent.MOD_ID, value = Dist.CLIENT)
public final class ClientRuntime {
    private static final Gson GSON = new Gson();
    private static final AtomicReference<JsonObject> PENDING = new AtomicReference<>();
    private static final List<Action> ACTIONS = new ArrayList<>();
    private static final Set<Action> EXECUTED_ACTION_COMMANDS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Action> EXECUTED_CONTAINER_CLICKS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Action> EXECUTED_CLOSE_SCREEN =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Action> EXECUTED_USE_INTERACT =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile BufferedWriter output;
    private static JsonObject scenario;
    private static int frame;
    private static int totalFrames;
    private static int totalTicks;
    private static int captureEvery;
    private static Path outputDir;
    private static boolean active;
    private static boolean windowPositioned;
    private static volatile boolean scenarioStarted;
    private static boolean commandsSent;
    private static boolean frameStarted;
    private static int warmupRemaining;
    private static long scenarioStartGameTime;
    /** When scenario became active while player/level still null (nanoTime). */
    private static long waitingForWorldSinceNanos;
    private static long lastWorldWaitHeartbeatNanos;


    static {
        Thread listener = new Thread(ClientRuntime::listen, "VoxPilot-localhost-agent");
        listener.setDaemon(true);
        listener.start();
    }

    private static void listen() {
        int port = Integer.getInteger("voxpilot.port", 39071);
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
             Socket socket = server.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            output = out;
            send(event("agent_ready"));
            String line = in.readLine();
            if (line != null) PENDING.set(JsonParser.parseString(line).getAsJsonObject());
            while (!scenarioStarted || active || PENDING.get() != null) Thread.sleep(100L);
        } catch (Exception exception) {
            exception.printStackTrace();
        } finally {
            output = null;
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            frameStarted = false;
            JsonObject incoming = PENDING.getAndSet(null);
            if (incoming != null) begin(incoming, mc);
            if (!active) return;
            if (mc.player == null || mc.level == null) {
                if (waitingForWorldSinceNanos == 0L) {
                    waitingForWorldSinceNanos = System.nanoTime();
                }
                long now = System.nanoTime();
                long waited = now - waitingForWorldSinceNanos;
                // Heartbeat every ~5s so the app socket read does not idle-timeout
                if (now - lastWorldWaitHeartbeatNanos > 5_000_000_000L) {
                    lastWorldWaitHeartbeatNanos = now;
                    JsonObject wait = event("waiting_for_world");
                    wait.addProperty("waitedMs", waited / 1_000_000L);
                    wait.addProperty(
                            "screen",
                            mc.screen == null ? "" : mc.screen.getClass().getName());
                    send(wait);
                }
                // Fail fast: no player/level after 90s → connection or join failure
                if (waited > 90_000_000_000L) {
                    JsonObject err = event("error");
                    err.addProperty("where", "connection_failed");
                    err.addProperty(
                            "message",
                            "No player/level after 90s — multiplayer join or world load failed"
                                    + " (screen="
                                    + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                                    + ")");
                    send(err);
                    active = false;
                    send(event("complete"));
                    if (bool(scenario, "closeClient", true)) {
                        mc.stop();
                    }
                }
                return;
            }
            waitingForWorldSinceNanos = 0L;
            if (!windowPositioned) configureWindow(mc);
            if (!commandsSent) sendSceneCommands(mc);
            if (warmupRemaining-- > 0) return;
            applyFrame(mc);
            frameStarted = true;
        } else {
            if (!active || !frameStarted || mc.player == null || mc.level == null) return;
            boolean captured = captureEvery > 0 && frame % captureEvery == 0;
            String image = captured ? capture(mc, frame) : null;
            send(frameEvent(mc, image));
            frame++;
            long elapsedTicks =
                    mc.level.getGameTime() - scenarioStartGameTime;
            if (frame >= totalFrames
                    || (totalTicks >= 0 && elapsedTicks >= totalTicks)) {
                finish(mc);
            }
        }
    }

    private static void begin(JsonObject root, Minecraft mc) {
        scenarioStarted = true;
        scenario = root;
        waitingForWorldSinceNanos = 0L;
        frame = 0;
        totalFrames = integer(root, "totalFrames", 600);
        totalTicks = integer(root, "totalTicks", -1);
        captureEvery = integer(root, "captureEvery", 0);
        warmupRemaining = integer(root, "warmupFrames", 20);
        commandsSent = false;
        scenarioStartGameTime = Long.MIN_VALUE;
        outputDir = Path.of(string(root, "outputDir", "voxpilot-output")).toAbsolutePath();
        ACTIONS.clear();
        EXECUTED_ACTION_COMMANDS.clear();
        EXECUTED_CONTAINER_CLICKS.clear();
        EXECUTED_CLOSE_SCREEN.clear();
        EXECUTED_USE_INTERACT.clear();
        JsonArray actions = root.has("actions") ? root.getAsJsonArray("actions") : new JsonArray();
        for (JsonElement item : actions) ACTIONS.add(new Action(item.getAsJsonObject()));
        mc.options.pauseOnLostFocus = false;
        if (!windowPositioned) configureWindow(mc);
        active = true;
        send(event("scenario_loaded"));
    }

    private static void configureWindow(Minecraft mc) {
        JsonObject display = scenario.has("display") ? scenario.getAsJsonObject("display") : new JsonObject();
        if (bool(display, "background", true)) {
            long handle = mc.getWindow().getWindow();
            GLFW.glfwSetWindowPos(handle, -32000, -32000);
        }
        windowPositioned = true;
    }

    private static void sendSceneCommands(Minecraft mc) {
        JsonArray commands = scenario.has("commands") ? scenario.getAsJsonArray("commands") : new JsonArray();
        MinecraftServer integratedServer = mc.getSingleplayerServer();
        if (integratedServer != null) {
            List<String> queued = new ArrayList<>();
            for (JsonElement item : commands) {
                String command = item.getAsString();
                queued.add(command.startsWith("/") ? command.substring(1) : command);
            }
            integratedServer.execute(() -> {
                for (String command : queued) {
                    integratedServer.getCommands().performPrefixedCommand(
                            integratedServer.createCommandSourceStack().withPermission(4),
                            command);
                }
            });
            commandsSent = true;
            return;
        }
        for (JsonElement item : commands) {
            String command = item.getAsString();
            mc.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
        }
        commandsSent = true;
    }

    private static void applyFrame(Minecraft mc) {
        if (scenarioStartGameTime == Long.MIN_VALUE) {
            scenarioStartGameTime = mc.level.getGameTime();
        }
        long elapsedTicks =
                mc.level.getGameTime() - scenarioStartGameTime;
        releaseKeys(mc.options);
        for (Action action : ACTIONS) {
            if (!action.contains(frame, elapsedTicks)) continue;
            JsonObject data = action.data;
            if (data.has("commands")
                    && EXECUTED_ACTION_COMMANDS.add(action)) {
                sendCommands(mc, data.getAsJsonArray("commands"));
            }
            if (data.has("rawKey")) {
                MinecraftForge.EVENT_BUS.post(new InputEvent.Key(
                        data.get("rawKey").getAsInt(), 0, GLFW.GLFW_PRESS, 0));
            }
            if (data.has("keys")) applyKeys(mc.options, data.getAsJsonObject("keys"));
            if (data.has("keys")
                    && bool(data.getAsJsonObject("keys"), "use", false)
                    && EXECUTED_USE_INTERACT.add(action)) {
                forceUseOnCrosshair(mc);
            }
            if (data.has("camera")) {
                String camera = data.get("camera").getAsString().toLowerCase(Locale.ROOT);
                mc.options.setCameraType(switch (camera) {
                    case "third_back", "third-person-back" -> CameraType.THIRD_PERSON_BACK;
                    case "third_front", "third-person-front" -> CameraType.THIRD_PERSON_FRONT;
                    default -> CameraType.FIRST_PERSON;
                });
            }
            if (data.has("debugScreen")) {
                mc.options.renderDebug = bool(data, "debugScreen", false);
            }
            if (data.has("yaw")) mc.player.setYRot(data.get("yaw").getAsFloat());
            if (data.has("pitch")) mc.player.setXRot(data.get("pitch").getAsFloat());
            if (data.has("deltaYaw")) mc.player.setYRot(mc.player.getYRot() + data.get("deltaYaw").getAsFloat());
            if (data.has("deltaPitch")) mc.player.setXRot(mc.player.getXRot() + data.get("deltaPitch").getAsFloat());
            if (data.has("hotbar")) {
                int slot = data.get("hotbar").getAsInt();
                if (slot >= 0 && slot <= 8) {
                    mc.player.getInventory().selected = slot;
                }
            }
            if (data.has("containerClick") && EXECUTED_CONTAINER_CLICKS.add(action)) {
                applyContainerClick(mc, data.get("containerClick").getAsJsonObject());
            }
            if (data.has("closeScreen") && bool(data, "closeScreen", false)
                    && EXECUTED_CLOSE_SCREEN.add(action)) {
                mc.player.closeContainer();
            }
        }
    }


    /** Prefer explicit use-on-block so GUI opens even when key-mapping alone is flaky. */
    private static void forceUseOnCrosshair(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null || mc.level == null) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            JsonObject err = event("error");
            err.addProperty("where", "useInteract");
            err.addProperty(
                    "message",
                    "use requested but crosshair is not on a block (hit="
                            + (hit == null ? "null" : String.valueOf(hit.getType()))
                            + ")");
            send(err);
            return;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
        JsonObject note = event("use_interact");
        note.addProperty("pos", blockHit.getBlockPos().toShortString());
        note.addProperty(
                "block",
                mc.level.getBlockState(blockHit.getBlockPos()).toString());
        send(note);
    }

    /**
     * Click a slot in the currently open container menu (Nexus GUI, crafting table, etc.).
     * JSON: { "slot": 29, "button": 0, "type": "quick_move"|"pickup"|"throw" }
     * NexusMenu: 0=input, 1=output, 2-28=main inv, 29-37=hotbar.
     * CraftingMenu: 0=result, 1-9=grid, then player inv/hotbar.
     */
    private static void applyContainerClick(Minecraft mc, JsonObject click) {
        if (mc.player == null || mc.gameMode == null) {
            send(error("containerClick", new IllegalStateException("no player/gameMode")));
            return;
        }
        if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
            send(error("containerClick", new IllegalStateException(
                    "no container screen open (screen="
                            + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                            + ")")));
            return;
        }
        int slot = integer(click, "slot", 0);
        int button = integer(click, "button", 0);
        String typeName = string(click, "type", "pickup").toLowerCase(Locale.ROOT);
        ClickType type = switch (typeName) {
            case "quick_move", "shift", "quickmove" -> ClickType.QUICK_MOVE;
            case "throw" -> ClickType.THROW;
            case "clone" -> ClickType.CLONE;
            case "swap" -> ClickType.SWAP;
            default -> ClickType.PICKUP;
        };
        int containerId = mc.player.containerMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, mc.player);
        JsonObject note = event("container_click");
        note.addProperty("slot", slot);
        note.addProperty("button", button);
        note.addProperty("clickType", type.name());
        note.addProperty("containerId", containerId);
        note.addProperty(
                "screen",
                mc.screen == null ? "null" : mc.screen.getClass().getName());
        send(note);
    }

    private static void sendCommands(Minecraft mc, JsonArray commands) {
        MinecraftServer integratedServer = mc.getSingleplayerServer();
        if (integratedServer != null) {
            List<String> queued = new ArrayList<>();
            for (JsonElement item : commands) {
                String command = item.getAsString();
                queued.add(command.startsWith("/")
                        ? command.substring(1)
                        : command);
            }
            integratedServer.execute(() -> {
                for (String command : queued) {
                    integratedServer.getCommands().performPrefixedCommand(
                            integratedServer.createCommandSourceStack().withPermission(4),
                            command);
                }
            });
            return;
        }
        for (JsonElement item : commands) {
            String command = item.getAsString();
            mc.player.connection.sendCommand(command.startsWith("/")
                    ? command.substring(1)
                    : command);
        }
    }

    private static void applyKeys(Options options, JsonObject keys) {
        set(options.keyUp, keys, "forward");
        set(options.keyDown, keys, "back");
        set(options.keyLeft, keys, "left");
        set(options.keyRight, keys, "right");
        set(options.keyJump, keys, "jump");
        set(options.keyShift, keys, "sneak");
        set(options.keySprint, keys, "sprint");
        set(options.keyAttack, keys, "attack");
        set(options.keyUse, keys, "use");
    }

    private static void set(KeyMapping mapping, JsonObject keys, String name) {
        if (bool(keys, name, false)) mapping.setDown(true);
    }

    private static void releaseKeys(Options options) {
        options.keyUp.setDown(false); options.keyDown.setDown(false);
        options.keyLeft.setDown(false); options.keyRight.setDown(false);
        options.keyJump.setDown(false); options.keyShift.setDown(false);
        options.keySprint.setDown(false); options.keyAttack.setDown(false); options.keyUse.setDown(false);
    }

    private static String capture(Minecraft mc, int index) {
        try {
            Path frames = outputDir.resolve("frames");
            Files.createDirectories(frames);
            Path file = frames.resolve(String.format(Locale.ROOT, "frame-%06d.png", index));
            try (NativeImage image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(file);
            }
            return file.toString();
        } catch (Exception exception) {
            send(error("capture", exception));
            return null;
        }
    }

    private static JsonObject frameEvent(Minecraft mc, String image) {
        JsonObject item = event("frame");
        item.addProperty("frame", frame);
        item.addProperty("gameTime", mc.level.getGameTime());
        item.addProperty("x", mc.player.getX()); item.addProperty("y", mc.player.getY()); item.addProperty("z", mc.player.getZ());
        item.addProperty("yaw", mc.player.getYRot()); item.addProperty("pitch", mc.player.getXRot());
        item.addProperty("vx", mc.player.getDeltaMovement().x); item.addProperty("vy", mc.player.getDeltaMovement().y); item.addProperty("vz", mc.player.getDeltaMovement().z);
        item.addProperty("onGround", mc.player.onGround()); item.addProperty("health", mc.player.getHealth());
        item.addProperty("camera", mc.options.getCameraType().name());
        JsonArray tracked = new JsonArray();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.hasCustomName()
                    || !"VoxPilotTrack".equals(
                            entity.getCustomName().getString())) {
                continue;
            }
            JsonObject trackedEntity = new JsonObject();
            trackedEntity.addProperty(
                    "type",
                    net.minecraft.core.registries.BuiltInRegistries
                            .ENTITY_TYPE
                            .getKey(entity.getType())
                            .toString());
            trackedEntity.addProperty("x", entity.getX());
            trackedEntity.addProperty("y", entity.getY());
            trackedEntity.addProperty("z", entity.getZ());
            trackedEntity.addProperty("yaw", entity.getYRot());
            if (entity instanceof LivingEntity living) {
                trackedEntity.addProperty("health", living.getHealth());
                trackedEntity.addProperty("hurtTime", living.hurtTime);
                trackedEntity.addProperty("swinging", living.swinging);
                trackedEntity.addProperty("swingTime", living.swingTime);
                trackedEntity.addProperty(
                        "swingingArm",
                        living.swingingArm == null
                                ? "NONE"
                                : living.swingingArm.name());
            }
            tracked.add(trackedEntity);
        }
        item.add("trackedEntities", tracked);
        JsonArray trackedBlocks = new JsonArray();
        if (scenario.has("trackedBlocks")) {
            for (JsonElement element : scenario.getAsJsonArray("trackedBlocks")) {
                JsonObject source = element.getAsJsonObject();
                BlockPos pos = new BlockPos(
                        source.get("x").getAsInt(),
                        source.get("y").getAsInt(),
                        source.get("z").getAsInt());
                JsonObject trackedBlock = new JsonObject();
                trackedBlock.addProperty(
                        "label",
                        string(source, "label", pos.toShortString()));
                trackedBlock.addProperty("x", pos.getX());
                trackedBlock.addProperty("y", pos.getY());
                trackedBlock.addProperty("z", pos.getZ());
                trackedBlock.addProperty(
                        "state",
                        mc.level.getBlockState(pos).toString());
                trackedBlocks.add(trackedBlock);
            }
        }
        item.add("trackedBlocks", trackedBlocks);
        Screen screen = mc.screen;
        item.addProperty("screen", screen == null ? "" : screen.getClass().getName());
        item.addProperty("hasContainerScreen", screen instanceof AbstractContainerScreen<?>);
        if (mc.player != null) {
            item.addProperty("hotbarSelected", mc.player.getInventory().selected);
            ItemStack carried = mc.player.containerMenu.getCarried();
            item.addProperty(
                    "carried",
                    carried.isEmpty() ? "" : carried.getItem().toString());
            ItemStack main = mc.player.getMainHandItem();
            item.addProperty(
                    "mainHand",
                    main.isEmpty() ? "" : main.getItem().toString());
        }
        if (image != null) item.addProperty("screenshot", image);
        return item;
    }

    private static void finish(Minecraft mc) {
        releaseKeys(mc.options);
        active = false;
        send(event("complete"));
        if (bool(scenario, "closeClient", true)) mc.stop();
    }

    private static JsonObject event(String type) { JsonObject o = new JsonObject(); o.addProperty("type", type); return o; }
    private static JsonObject error(String where, Exception e) { JsonObject o = event("error"); o.addProperty("where", where); o.addProperty("message", e.toString()); return o; }
    private static int integer(JsonObject o, String key, int fallback) { return o.has(key) ? o.get(key).getAsInt() : fallback; }
    private static String string(JsonObject o, String key, String fallback) { return o.has(key) ? o.get(key).getAsString() : fallback; }
    private static boolean bool(JsonObject o, String key, boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
    private static synchronized void send(JsonObject item) {
        try { if (output != null) { output.write(GSON.toJson(item)); output.newLine(); output.flush(); } }
        catch (IOException ignored) { }
    }

    private record Action(
            int fromFrame,
            int toFrame,
            long fromTick,
            long toTick,
            boolean tickBased,
            JsonObject data) {
        Action(JsonObject source) {
            this(
                    integer(source, "fromFrame", integer(source, "frame", 0)),
                    integer(source, "toFrame", integer(source, "frame", 0)),
                    integer(source, "fromTick", integer(source, "tick", 0)),
                    integer(source, "toTick", integer(source, "tick", 0)),
                    source.has("fromTick")
                            || source.has("toTick")
                            || source.has("tick"),
                    source);
        }

        boolean contains(int frameValue, long tickValue) {
            return tickBased
                    ? tickValue >= fromTick && tickValue <= toTick
                    : frameValue >= fromFrame && frameValue <= toFrame;
        }
    }
}
