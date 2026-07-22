package dev.voxpilot.app;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class Main {
    private static final int AGENT_PORT = 39071;
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || has(args, "--help") || has(args, "-h")) {
            usage();
            return;
        }
        if ("report".equals(args[0])) {
            writeHtml(requiredPath(args, "--dir").toAbsolutePath().normalize());
            return;
        }
        if (!"run".equals(args[0])) throw new IllegalArgumentException("Unknown command: " + args[0]);
        Path project = requiredPath(args, "--project").toAbsolutePath().normalize();
        Path scenarioPath = requiredPath(args, "--scenario").toAbsolutePath().normalize();
        validateProject(project);

        String sourceScenario = Files.readString(scenarioPath, StandardCharsets.UTF_8).trim();
        boolean launchServer = booleanField(sourceScenario, "launchServer", true);
        String javaHome = option(args, "--java-home", discoverJava17());
        Path reportsRoot = project.resolve("run").resolve("voxpilot-reports");
        Path reportDir = reportsRoot.resolve(RUN_ID.format(LocalDateTime.now()));
        Files.createDirectories(reportDir);
        String scenario = addOutputDir(sourceScenario, reportDir);
        Files.writeString(reportDir.resolve("scenario.resolved.json"), scenario, StandardCharsets.UTF_8);

        installAgent(project);
        Process server = null;
        Process client = null;
        try {
            if (launchServer) {
                configureServer(project);
                server = launch(project, javaHome, reportDir.resolve("server.log"), "runServer");
                waitForText(reportDir.resolve("server.log"), "Done (", 240);
            }
            String quickPlay = launchServer
                    ? "--quickPlayMultiplayer 127.0.0.1:25565 --width 854 --height 480"
                    : "--width 854 --height 480";
            client = launch(project, javaHome, reportDir.resolve("client.log"), "runClient", "--args=" + quickPlay);
            runScenario(scenario, reportDir.resolve("frames.jsonl"));
            waitForExit(client, 60);
            writeHtml(reportDir);
            System.out.println("VOXPILOT_SUCCESS=" + reportDir);
        } finally {
            terminateTree(client);
            terminateTree(server);
        }
    }

    private static void runScenario(String scenario, Path framesLog) throws Exception {
        long deadline = System.nanoTime() + 240_000_000_000L;
        Socket socket = new Socket();
        while (true) {
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), AGENT_PORT), 1000);
                break;
            } catch (IOException notReady) {
                socket.close();
                if (System.nanoTime() > deadline) throw new IOException("VoxPilot agent did not open port " + AGENT_PORT, notReady);
                Thread.sleep(500L);
                socket = new Socket();
            }
        }
        Socket connectedSocket = socket;
        try (connectedSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(connectedSocket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(connectedSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedWriter frames = Files.newBufferedWriter(framesLog, StandardCharsets.UTF_8)) {
            connectedSocket.setSoTimeout(240_000);
            String ready = in.readLine();
            if (ready == null || !ready.contains("agent_ready")) throw new IOException("Invalid agent greeting: " + ready);
            out.write(scenario.replace("\r", "").replace("\n", "")); out.newLine(); out.flush();
            for (String line; (line = in.readLine()) != null;) {
                frames.write(line); frames.newLine(); frames.flush();
                if (line.contains("\"type\":\"error\"")) System.err.println(line);
                if (line.contains("\"type\":\"complete\"")) return;
            }
            throw new EOFException("Agent disconnected before completion");
        }
    }

    private static Process launch(Path project, String javaHome, Path log, String... gradleArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("cmd.exe"); command.add("/d"); command.add("/c"); command.add("gradlew.bat"); command.add("--no-daemon");
        for (String arg : gradleArgs) command.add(arg);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.environment().put("JAVA_HOME", javaHome);
        builder.environment().put("PATH", javaHome + "\\bin;" + builder.environment().getOrDefault("PATH", ""));
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static void installAgent(Path project) throws IOException {
        Path mods = project.resolve("run").resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve("voxpilot-agent-1.0.0.jar");
        try (InputStream input = Main.class.getResourceAsStream("/agent/voxpilot-agent.jar")) {
            if (input == null) throw new FileNotFoundException("Embedded VoxPilot agent is missing");
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void configureServer(Path project) throws IOException {
        Path run = project.resolve("run");
        Files.createDirectories(run);
        Files.writeString(run.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        String properties = "online-mode=false\nserver-port=25565\nlevel-name=VoxPilotWorld\nlevel-type=minecraft:flat\n" +
                "gamemode=creative\ndifficulty=peaceful\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\nmotd=VoxPilot local test\n";
        Files.writeString(run.resolve("server.properties"), properties, StandardCharsets.UTF_8);
        UUID devUuid = UUID.nameUUIDFromBytes("OfflinePlayer:Dev".getBytes(StandardCharsets.UTF_8));
        String ops = "[{\"uuid\":\"" + devUuid + "\",\"name\":\"Dev\",\"level\":4,\"bypassesPlayerLimit\":true}]\n";
        Files.writeString(run.resolve("ops.json"), ops, StandardCharsets.UTF_8);
    }

    private static void waitForText(Path file, String needle, int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(needle)) return;
            Thread.sleep(500L);
        }
        throw new IOException("Timed out waiting for '" + needle + "' in " + file);
    }

    private static void waitForExit(Process process, int seconds) throws InterruptedException {
        if (process != null) process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void terminateTree(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            new ProcessBuilder("taskkill.exe", "/PID", Long.toString(process.pid()), "/T", "/F")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } catch (Exception ignored) { process.destroyForcibly(); }
    }

    private static void writeHtml(Path reportDir) throws IOException {
        String records = String.join(",", Files.readAllLines(reportDir.resolve("frames.jsonl"), StandardCharsets.UTF_8)
                .stream().filter(line -> line.contains("\"type\":\"frame\"")).toList());
        String html = """
                <!doctype html><html lang="ja"><meta charset="utf-8"><title>VoxPilot report</title>
                <style>body{font:14px system-ui;background:#111;color:#eee;margin:24px}h1{color:#8de1ff}.ok{color:#83f28f}table{border-collapse:collapse;width:100%%}td,th{border-bottom:1px solid #333;padding:5px;text-align:right}img{width:320px;border:1px solid #555}a{color:#8de1ff}</style>
                <h1>VoxPilot フレームレポート</h1><p class="ok">シナリオ完了</p><p><a href="frames.jsonl">生JSONL</a> / <a href="scenario.resolved.json">解決済みシナリオ</a> / <a href="client.log">クライアントログ</a> / <a href="server.log">サーバーログ</a></p>
                <div id="shots"></div><table><thead><tr><th>frame</th><th>x</th><th>y</th><th>z</th><th>yaw</th><th>camera</th><th>ground</th><th>health</th></tr></thead><tbody id="rows"></tbody></table>
                <script>const a=[%s];rows.innerHTML=a.map(x=>`<tr><td>${x.frame}</td><td>${x.x.toFixed(3)}</td><td>${x.y.toFixed(3)}</td><td>${x.z.toFixed(3)}</td><td>${x.yaw.toFixed(1)}</td><td>${x.camera}</td><td>${x.onGround}</td><td>${x.health}</td></tr>`).join('');shots.innerHTML=a.filter(x=>x.screenshot).map(x=>`<figure><img src="frames/frame-${String(x.frame).padStart(6,'0')}.png"><figcaption>frame ${x.frame}</figcaption></figure>`).join('')</script></html>
                """.formatted(records);
        Files.writeString(reportDir.resolve("report.html"), html, StandardCharsets.UTF_8);
    }

    private static String addOutputDir(String json, Path reportDir) {
        int end = json.lastIndexOf('}');
        if (end < 0) throw new IllegalArgumentException("Scenario must be a JSON object");
        String escaped = reportDir.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String prefix = json.substring(0, end).trim();
        return prefix + (prefix.endsWith("{") ? "" : ",") + "\"outputDir\":\"" + escaped + "\"}";
    }

    private static boolean booleanField(String json, String field, boolean fallback) {
        String compact = json.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("\"" + field.toLowerCase(Locale.ROOT) + "\":false")) return false;
        if (compact.contains("\"" + field.toLowerCase(Locale.ROOT) + "\":true")) return true;
        return fallback;
    }

    private static void validateProject(Path project) {
        if (!Files.isRegularFile(project.resolve("gradlew.bat")) || !Files.isRegularFile(project.resolve("build.gradle")))
            throw new IllegalArgumentException("Not a Forge Gradle project: " + project);
    }

    private static String discoverJava17() {
        String preferred = "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.19.10-hotspot";
        if (Files.isExecutable(Path.of(preferred, "bin", "java.exe"))) return preferred;
        String current = System.getenv("JAVA_HOME");
        if (current != null) return current;
        throw new IllegalStateException("Java 17 not found; pass --java-home");
    }

    private static Path requiredPath(String[] args, String key) { String value = option(args, key, null); if (value == null) throw new IllegalArgumentException("Missing " + key); return Path.of(value); }
    private static String option(String[] args, String key, String fallback) { for (int i=0;i<args.length-1;i++) if (key.equals(args[i])) return args[i+1]; return fallback; }
    private static boolean has(String[] args, String key) { for (String arg : args) if (key.equals(arg)) return true; return false; }
    private static void usage() { System.out.println("VoxPilot 1.0\njava -jar VoxPilot.jar run --project <Forge MDK> --scenario <scenario.json> [--java-home <JDK17>]\njava -jar VoxPilot.jar report --dir <report directory>"); }
}
