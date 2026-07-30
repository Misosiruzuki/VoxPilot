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
import net.minecraft.world.entity.Entity;
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
import java.util.concurrent.atomic.AtomicReference;

@Mod.EventBusSubscriber(modid = VoxPilotAgent.MOD_ID, value = Dist.CLIENT)
public final class ClientRuntime {
    private static final Gson GSON = new Gson();
    private static final AtomicReference<JsonObject> PENDING = new AtomicReference<>();
    private static final List<Action> ACTIONS = new ArrayList<>();
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
            if (mc.player == null || mc.level == null) return;
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
        frame = 0;
        totalFrames = integer(root, "totalFrames", 120);
        totalTicks = integer(root, "totalTicks", -1);
        captureEvery = integer(root, "captureEvery", 0);
        warmupRemaining = integer(root, "warmupFrames", 20);
        commandsSent = false;
        scenarioStartGameTime = Long.MIN_VALUE;
        outputDir = Path.of(string(root, "outputDir", "voxpilot-output")).toAbsolutePath();
        ACTIONS.clear();
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
            if (data.has("rawKey")) {
                MinecraftForge.EVENT_BUS.post(new InputEvent.Key(
                        data.get("rawKey").getAsInt(), 0, GLFW.GLFW_PRESS, 0));
            }
            if (data.has("keys")) applyKeys(mc.options, data.getAsJsonObject("keys"));
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
            tracked.add(trackedEntity);
        }
        item.add("trackedEntities", tracked);
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
