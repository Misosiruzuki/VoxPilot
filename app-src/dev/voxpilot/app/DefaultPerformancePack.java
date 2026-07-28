package dev.voxpilot.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class DefaultPerformancePack {
    private enum Side {
        COMMON,
        SERVER,
        CLIENT
    }

    private record Artifact(
            String name,
            String artifactId,
            String version,
            String filename,
            String url,
            String sha512,
            Side side) {
    }

    private static final String USER_AGENT =
            "VoxPilot/1.2.1 (+https://github.com/Misosiruzuki/VoxPilot)";
    private static final String RESOURCE_PACK_FILENAME = "F8thful-v6.0.zip";
    private static final String RESOURCE_PACK_URL =
            "https://edge.forgecdn.net/files/4672/794/F8thful.zip";
    private static final String RESOURCE_PACK_SHA512 =
            "6f612a94b04bcd140202d6cbbd55dc572d250e4c1ed91604a21909ab0f90469e"
                    + "0bb477bd7800970b15458d98db0fcaf1e900aaea6fe7b44a1a47aad3e75f5cac";

    private static final List<Artifact> ARTIFACTS = List.of(
            new Artifact(
                    "FerriteCore 6.0.1",
                    "ferritecore",
                    "6.0.1-forge",
                    "ferritecore-6.0.1-forge.jar",
                    "https://cdn.modrinth.com/data/uXXizFIs/versions/DG5Fn9Sz/"
                            + "ferritecore-6.0.1-forge.jar",
                    "a1960a7c03dc32d4ccaccaf28afdd9b078758bbd62d15a91d4039a83fa9397a0"
                            + "98e89b69591f6bd5190254d9ee97e502504154b9aec764adb8c65f000b75ba2c",
                    Side.COMMON),
            new Artifact(
                    "ModernFix 5.27.66",
                    "modernfix-forge",
                    "5.27.66+mc1.20.1",
                    "modernfix-forge-5.27.66+mc1.20.1.jar",
                    "https://cdn.modrinth.com/data/nmDcB62a/versions/ZxDvSMHV/"
                            + "modernfix-forge-5.27.66%2Bmc1.20.1.jar",
                    "546eab2906684c463e55e27ff99467ab6ca7a6350803e5ad2c16e25d0eb1187a"
                            + "9ea619ab8a8887af652eeb21e2b1780b6f4957d5151a2b8be92d539feb244ea5",
                    Side.COMMON),
            new Artifact(
                    "ServerCore 1.5.2",
                    "servercore-forge",
                    "1.5.2+1.20.1",
                    "servercore-forge-1.5.2+1.20.1.jar",
                    "https://cdn.modrinth.com/data/4WWQxlQP/versions/rx1c7m6q/"
                            + "servercore-forge-1.5.2%2B1.20.1.jar",
                    "650f54dcf6d44e26cbc180ca5779857574692f02ff2a55146ed085db1665dde7fb"
                            + "578c75d655e3de064ed56599bc6d38dd547f5f123381c0a54867b98f805b0c",
                    Side.SERVER),
            new Artifact(
                    "Embeddium 0.3.31",
                    "embeddium",
                    "0.3.31+mc1.20.1",
                    "embeddium-0.3.31+mc1.20.1.jar",
                    "https://cdn.modrinth.com/data/sk9rgfiA/versions/UTbfe5d1/"
                            + "embeddium-0.3.31%2Bmc1.20.1.jar",
                    "ffbf2da4685260a4d5c14c621708bd20722563f084f042d3dfb0a7b87f048e39"
                            + "299648c854a93939129da0d23a15a91ec628560d601e76074b08e275f6e132e9",
                    Side.CLIENT),
            new Artifact(
                    "ImmediatelyFast 1.2.4",
                    "ImmediatelyFast",
                    "1.2.4+1.20.1",
                    "ImmediatelyFast-1.2.4+1.20.1.jar",
                    "https://cdn.modrinth.com/data/5ZwdcRci/versions/NJ17fqEK/"
                            + "ImmediatelyFast-1.2.4%2B1.20.1.jar",
                    "07c8b0bfe2c032985a664109abe5d55e6eae2faed8b72b38e19af824a38cf080"
                            + "86c31e198fa1890e9871baacdc2421a8bb0fa0c67a08057235b64f3d946319b5",
                    Side.CLIENT),
            new Artifact(
                    "Entity Culling 1.7.4",
                    "entityculling-forge",
                    "1.7.4-mc1.20.1",
                    "entityculling-forge-1.7.4-mc1.20.1.jar",
                    "https://cdn.modrinth.com/data/NNAgCjsB/versions/kMC7OLoZ/"
                            + "entityculling-forge-1.7.4-mc1.20.1.jar",
                    "b9d36a1320dbe41deec4b19b292b6936c3b9f699621beb148330e7dd8b4d63e9"
                            + "107967a6bcd658d89db69e953d8690c33ce686dc17f37862be7e53f200f987c0",
                    Side.CLIENT));

    private final Path project;
    private final Path run;
    private final Path mods;
    private final Path dedicatedServerRun;
    private final Path dedicatedServerMods;
    private final Path cache;
    private final Path remapWorkCache;
    private final Path remappedCache;
    private final String javaHome;
    private final HttpClient http;

    DefaultPerformancePack(Path project, String javaHome) {
        this.project = project;
        this.run = project.resolve("run");
        this.mods = run.resolve("mods");
        this.dedicatedServerRun = project.resolve("run-server");
        this.dedicatedServerMods = dedicatedServerRun.resolve("mods");
        this.cache = run.resolve("voxpilot-cache").resolve("performance");
        this.remapWorkCache = project.resolve("build")
                .resolve("voxpilot-remap-work");
        this.remappedCache = project.resolve(".gradle")
                .resolve("voxpilot-stable")
                .resolve("performance-remapped");
        this.javaHome = javaHome;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    void prepareServerSide(boolean dedicated) throws Exception {
        Path destination = dedicated ? dedicatedServerMods : mods;
        DefaultPerformanceConfig.applyServer(dedicated ? dedicatedServerRun : run);
        Files.createDirectories(destination);
        Files.createDirectories(cache);
        for (Artifact artifact : ARTIFACTS) {
            ensureDownloaded(
                    artifact.name,
                    artifact.url,
                    artifact.sha512,
                    cache.resolve(artifact.filename));
        }
        remapArtifacts();
        removeManagedMods(mods, run.resolve("voxpilot-managed-performance-files.txt"));
        removeManagedMods(
                dedicatedServerMods,
                dedicatedServerRun.resolve("voxpilot-managed-performance-files.txt"));
        installSides(destination, Side.COMMON, Side.SERVER);
        if (dedicated) {
            writeManagedManifest(
                    dedicatedServerRun.resolve("voxpilot-managed-performance-files.txt"),
                    Side.COMMON,
                    Side.SERVER);
        }
    }

    void prepareClientSide(boolean dedicated) throws Exception {
        DefaultPerformanceConfig.applyClient(run);
        ensureRemappedArtifacts();
        Files.createDirectories(mods);
        if (dedicated) {
            installSides(mods, Side.COMMON, Side.CLIENT);
        } else {
            installSides(mods, Side.CLIENT);
        }
        installResourcePack();
        enableResourcePack();
        writeManagedManifest(
                run.resolve("voxpilot-managed-performance-files.txt"),
                dedicated
                        ? new Side[] {Side.COMMON, Side.CLIENT}
                        : new Side[] {Side.COMMON, Side.SERVER, Side.CLIENT});
    }

    Path dedicatedServerRun() {
        return dedicatedServerRun;
    }

    private void installSides(Path destination, Side... sides) throws Exception {
        for (Artifact artifact : ARTIFACTS) {
            for (Side side : sides) {
                if (artifact.side == side) {
                    install(artifact, destination);
                    break;
                }
            }
        }
    }

    private void install(Artifact artifact, Path destinationDirectory) throws Exception {
        Path cached = cache.resolve(artifact.filename);
        ensureDownloaded(artifact.name, artifact.url, artifact.sha512, cached);
        Path remapped = findRemapped(artifact);
        Path destination = destinationDirectory.resolve(artifact.filename);
        if (!Files.isRegularFile(destination) || Files.mismatch(remapped, destination) != -1) {
            Files.copy(remapped, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("VOXPILOT_DEFAULT_MOD=" + artifact.name + " -> " + destination);
    }

    private void remapArtifacts() throws Exception {
        Files.createDirectories(remappedCache);
        Path initScript = run.resolve("voxpilot-cache").resolve("performance-remap.init.gradle");
        Files.writeString(initScript, remapInitScript(), StandardCharsets.UTF_8);
        Path log = run.resolve("voxpilot-cache").resolve("performance-remap.log");
        List<String> command = List.of(
                "cmd.exe",
                "/d",
                "/c",
                "gradlew.bat",
                "--no-daemon",
                "-I",
                initScript.toString(),
                "voxpilotRemapDefaultPerformance");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.environment().put("JAVA_HOME", javaHome);
        builder.environment().put(
                "PATH",
                javaHome + "\\bin;" + builder.environment().getOrDefault("PATH", ""));
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        System.out.println("VOXPILOT_REMAP=ForgeGradle -> " + remappedCache);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Timed out remapping performance mods; see " + log);
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "ForgeGradle failed to remap performance mods (exit "
                            + process.exitValue() + "); see " + log);
        }
        copyRemapWorkToStableCache();
        for (Artifact artifact : ARTIFACTS) {
            findRemapped(artifact);
        }
    }

    private void copyRemapWorkToStableCache() throws IOException {
        Files.createDirectories(remappedCache);
        try (var files = Files.list(remapWorkCache)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                Files.copy(
                        source,
                        remappedCache.resolve(source.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void ensureRemappedArtifacts() throws Exception {
        for (Artifact artifact : ARTIFACTS) {
            try {
                findRemapped(artifact);
            } catch (IOException missing) {
                remapArtifacts();
                return;
            }
        }
    }

    private String remapInitScript() {
        StringBuilder modules = new StringBuilder();
        for (Artifact artifact : ARTIFACTS) {
            if (!modules.isEmpty()) {
                modules.append(",\n");
            }
            modules.append("            'voxpilot:")
                    .append(artifact.artifactId)
                    .append(':')
                    .append(artifact.version)
                    .append('\'');
        }
        return """
                allprojects { target ->
                    target.afterEvaluate {
                        def inputDir = target.file('run/voxpilot-cache/performance')
                        def outputDir = target.file('build/voxpilot-remap-work')
                        def modules = [
                %s
                        ]
                        target.repositories.flatDir { dirs inputDir }
                        target.configurations.create('voxpilotDefaultPerformanceInput')
                        modules.each { module ->
                            target.dependencies.add(
                                'voxpilotDefaultPerformanceInput',
                                target.fg.deobf(module))
                        }
                        target.tasks.register('voxpilotRemapDefaultPerformance', Copy) {
                            from target.configurations.voxpilotDefaultPerformanceInput
                            into outputDir
                        }
                    }
                }
                """.formatted(modules);
    }

    private Path findRemapped(Artifact artifact) throws IOException {
        String prefix = artifact.artifactId + "-" + artifact.version + "_mapped_";
        try (var files = Files.list(remappedCache)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".jar");
                    })
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow(() -> new IOException(
                            "Remapped artifact is missing for " + artifact.name
                                    + " in " + remappedCache));
        }
    }

    private void installResourcePack() throws Exception {
        Path resourcePacks = run.resolve("resourcepacks");
        Files.createDirectories(resourcePacks);
        Path cached = cache.resolve(RESOURCE_PACK_FILENAME);
        ensureDownloaded(
                "F8thful v6.0 (8x8)",
                RESOURCE_PACK_URL,
                RESOURCE_PACK_SHA512,
                cached);
        Path destination = resourcePacks.resolve(RESOURCE_PACK_FILENAME);
        if (!hasHash(destination, RESOURCE_PACK_SHA512)) {
            Files.copy(cached, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("VOXPILOT_DEFAULT_RESOURCE_PACK=F8thful v6.0 -> " + destination);
    }

    private void ensureDownloaded(
            String name,
            String url,
            String sha512,
            Path target) throws Exception {
        if (hasHash(target, sha512)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(partial);
        System.out.println("VOXPILOT_DOWNLOAD=" + name);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> response =
                http.send(request, HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(partial);
            throw new IOException(
                    "Download failed for " + name + ": HTTP " + response.statusCode());
        }
        if (!hasHash(partial, sha512)) {
            Files.deleteIfExists(partial);
            throw new IOException("SHA-512 mismatch for " + name);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void removeManagedMods(Path directory, Path manifest) throws IOException {
        List<String> filenames = new ArrayList<>();
        for (Artifact artifact : ARTIFACTS) {
            filenames.add(artifact.filename);
        }
        filenames.add("BadOptimizations-2.4.1-1.20.1.jar");
        filenames.add("ImmediatelyFast-Forge-1.5.5+1.20.4.jar");
        filenames.add("entityculling-forge-1.10.5-mc1.20.1.jar");
        if (Files.isRegularFile(manifest)) {
            filenames.addAll(Files.readAllLines(manifest, StandardCharsets.UTF_8));
        }
        for (String filename : filenames) {
            if (!filename.isBlank()) {
                Files.deleteIfExists(directory.resolve(filename.trim()));
            }
        }
    }

    private void writeManagedManifest(Path manifest, Side... sides) throws IOException {
        List<Side> included = List.of(sides);
        List<String> filenames = ARTIFACTS.stream()
                .filter(artifact -> included.contains(artifact.side))
                .map(Artifact::filename)
                .toList();
        Files.createDirectories(manifest.getParent());
        Files.write(
                manifest,
                filenames,
                StandardCharsets.UTF_8);
    }

    private void enableResourcePack() throws IOException {
        Path options = run.resolve("options.txt");
        List<String> lines = Files.isRegularFile(options)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                : new ArrayList<>();
        String entry = "\"file/" + RESOURCE_PACK_FILENAME + "\"";
        int resourcePacksLine = findOption(lines, "resourcePacks:");
        if (resourcePacksLine < 0) {
            lines.add("resourcePacks:[\"vanilla\",\"mod_resources\"," + entry + "]");
        } else {
            String current = lines.get(resourcePacksLine);
            if (!current.contains(entry)) {
                int close = current.lastIndexOf(']');
                if (close >= 0) {
                    String before = current.substring(0, close);
                    String separator = before.endsWith("[") ? "" : ",";
                    lines.set(resourcePacksLine, before + separator + entry + "]");
                } else {
                    lines.set(
                            resourcePacksLine,
                            "resourcePacks:[\"vanilla\",\"mod_resources\"," + entry + "]");
                }
            }
        }
        if (findOption(lines, "incompatibleResourcePacks:") < 0) {
            lines.add("incompatibleResourcePacks:[]");
        }
        Files.createDirectories(run);
        Files.write(options, lines, StandardCharsets.UTF_8);
    }

    private static int findOption(List<String> lines, String prefix) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean hasHash(Path file, String expected) throws Exception {
        return Files.isRegularFile(file) && sha512(file).equalsIgnoreCase(expected);
    }

    private static String sha512(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-512 is unavailable", impossible);
        }
    }
}
