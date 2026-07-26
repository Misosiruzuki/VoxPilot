package dev.voxpilot.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds VoxPilot's performance profile once per run directory. Existing files are backed up
 * before the first application, and the marker prevents later user changes from being overwritten.
 */
final class DefaultPerformanceConfig {
    private static final String PROFILE_VERSION = "1";

    private DefaultPerformanceConfig() {
    }

    static void applyClient(Path run) throws IOException {
        Path marker = run.resolve("voxpilot-performance-client-v" + PROFILE_VERSION + ".applied");
        if (Files.isRegularFile(marker)) {
            return;
        }
        Path backup = run.resolve("voxpilot-config-backup").resolve("client-v" + PROFILE_VERSION);
        updateOptions(run.resolve("options.txt"), backup.resolve("options.txt"));
        seed(
                run,
                backup,
                "config/embeddium-options.json",
                EMBEDDIUM);
        seed(
                run,
                backup,
                "config/immediatelyfast.json",
                IMMEDIATELY_FAST);
        seed(
                run,
                backup,
                "config/entityculling.json",
                ENTITY_CULLING);
        Files.createDirectories(run);
        Files.writeString(
                marker,
                "VoxPilot maximum-performance client profile " + PROFILE_VERSION + "\n"
                        + "Applied once. Delete this marker to reapply the profile.\n",
                StandardCharsets.UTF_8);
        System.out.println("VOXPILOT_PERFORMANCE_PROFILE=client-v" + PROFILE_VERSION);
    }

    static void applyServer(Path run) throws IOException {
        Path marker = run.resolve("voxpilot-performance-server-v" + PROFILE_VERSION + ".applied");
        if (Files.isRegularFile(marker)) {
            return;
        }
        Path backup = run.resolve("voxpilot-config-backup").resolve("server-v" + PROFILE_VERSION);
        seed(
                run,
                backup,
                "config/ferritecore-mixin.toml",
                FERRITE_CORE);
        seed(
                run,
                backup,
                "config/servercore/config.yml",
                SERVER_CORE);
        seed(
                run,
                backup,
                "config/servercore/optimizations.yml",
                SERVER_CORE_OPTIMIZATIONS);
        Files.createDirectories(run);
        Files.writeString(
                marker,
                "VoxPilot maximum-performance server profile " + PROFILE_VERSION + "\n"
                        + "Applied once. Delete this marker to reapply the profile.\n",
                StandardCharsets.UTF_8);
        System.out.println("VOXPILOT_PERFORMANCE_PROFILE=server-v" + PROFILE_VERSION);
    }

    private static void updateOptions(Path options, Path backup) throws IOException {
        backupExisting(options, backup);
        List<String> lines = Files.isRegularFile(options)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                : new ArrayList<>();
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("enableVsync", "false");
        values.put("entityShadows", "false");
        values.put("renderDistance", "6");
        values.put("simulationDistance", "5");
        values.put("entityDistanceScaling", "0.5");
        values.put("particles", "2");
        values.put("maxFps", "60");
        values.put("graphicsMode", "0");
        values.put("ao", "false");
        values.put("prioritizeChunkUpdates", "2");
        values.put("biomeBlendRadius", "0");
        values.put("renderClouds", "\"false\"");
        values.put("mipmapLevels", "0");
        values.put("syncChunkWrites", "false");
        values.forEach((key, value) -> setOption(lines, key, value));
        Files.createDirectories(options.getParent());
        Files.write(options, lines, StandardCharsets.UTF_8);
    }

    private static void setOption(List<String> lines, String key, String value) {
        String prefix = key + ":";
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                lines.set(index, prefix + value);
                return;
            }
        }
        lines.add(prefix + value);
    }

    private static void seed(Path run, Path backup, String relative, String contents)
            throws IOException {
        Path destination = run.resolve(relative.replace('/', java.io.File.separatorChar));
        Path backupFile = backup.resolve(relative.replace('/', java.io.File.separatorChar));
        backupExisting(destination, backupFile);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
    }

    private static void backupExisting(Path source, Path backup) throws IOException {
        if (!Files.isRegularFile(source) || Files.isRegularFile(backup)) {
            return;
        }
        Files.createDirectories(backup.getParent());
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static final String EMBEDDIUM = """
            {
              "quality": {
                "weather_quality": "FAST",
                "leaves_quality": "FAST",
                "enable_vignette": false,
                "use_quad_normals_for_shading": false
              },
              "advanced": {
                "enable_memory_tracing": false,
                "use_advanced_staging_buffers": true,
                "disable_incompatible_mod_warnings": false,
                "cpu_render_ahead_limit": 3
              },
              "performance": {
                "chunk_builder_threads": 0,
                "always_defer_chunk_updates_v2": true,
                "animate_only_visible_textures": true,
                "use_entity_culling": true,
                "use_fog_occlusion": true,
                "use_block_face_culling": true,
                "use_compact_vertex_format": true,
                "use_translucent_face_sorting_v2": true,
                "use_no_error_g_l_context": true
              },
              "notifications": {
                "force_disable_donation_prompts": false,
                "has_cleared_donation_button": false,
                "has_seen_donation_prompt": false
              }
            }
            """;

    private static final String IMMEDIATELY_FAST = """
            {
              "REGULAR_INFO": "----- Regular config values below -----",
              "font_atlas_resizing": true,
              "map_atlas_generation": true,
              "hud_batching": true,
              "fast_text_lookup": true,
              "fast_buffer_upload": true,
              "fast_buffer_upload_size_mb": 256,
              "fast_buffer_upload_explicit_flush": true,
              "COSMETIC_INFO": "----- Cosmetic only config values below (Does not optimize anything) -----",
              "dont_add_info_into_debug_hud": true,
              "EXPERIMENTAL_INFO": "----- Experimental config values below (Rendering glitches may occur) -----",
              "experimental_disable_error_checking": false,
              "experimental_disable_resource_pack_conflict_handling": false,
              "experimental_sign_text_buffering": true,
              "DEBUG_INFO": "----- Debug only config values below (Do not touch) -----",
              "debug_only_and_not_recommended_disable_universal_batching": false,
              "debug_only_and_not_recommended_disable_mod_conflict_handling": false,
              "debug_only_and_not_recommended_disable_hardware_conflict_handling": false,
              "debug_only_print_additional_error_information": false
            }
            """;

    private static final String ENTITY_CULLING = """
            {
              "configVersion": 8,
              "renderNametagsThroughWalls": false,
              "blockEntityWhitelist": [
                "minecraft:beacon"
              ],
              "entityWhitelist": [],
              "tracingDistance": 64,
              "debugMode": false,
              "sleepDelay": 20,
              "hitboxLimit": 50,
              "captureRate": 10,
              "tickCulling": true,
              "tickCullingWhitelist": [
                "minecraft:block_display",
                "minecraft:item_display",
                "minecraft:text_display",
                "minecraft:firework_rocket"
              ],
              "disableF3": false,
              "skipEntityCulling": false,
              "skipBlockEntityCulling": false,
              "blockEntityFrustumCulling": true,
              "forceDisplayCulling": false,
              "solidLeaves": true
            }
            """;

    private static final String FERRITE_CORE = """
            # VoxPilot maximum-memory-reduction profile.
            compactFastMap = true
            useSmallThreadingDetector = false
            cacheMultipartPredicates = true
            multipartDeduplication = true
            blockstateCacheDeduplication = true
            modelResourceLocations = true
            modelSides = true
            replaceNeighborLookup = true
            populateNeighborTable = false
            replacePropertyMap = true
            bakedQuadDeduplication = true
            """;

    private static final String SERVER_CORE = """
            # VoxPilot maximum-performance profile for ServerCore 1.5.2.
            features:
              disable-spawn-chunks: true
              prevent-moving-into-unloaded-chunks: true
              autosave-interval: 12000
              xp-merge-fraction: 1
              xp-merge-radius: 3.0
              item-merge-radius: 3.0
              lobotomize-villagers:
                enabled: true
                tick-interval: 40

            dynamic:
              enabled: true
              target-mspt: 35
              dynamic-settings:
                - setting: 'CHUNK_TICK_DISTANCE'
                  max: 6
                  min: 2
                  increment: 1
                  interval: 15
                - setting: 'MOBCAP_PERCENTAGE'
                  max: 100
                  min: 30
                  increment: 10
                  interval: 15
                - setting: 'SIMULATION_DISTANCE'
                  max: 5
                  min: 2
                  increment: 1
                  interval: 15
                - setting: 'VIEW_DISTANCE'
                  max: 6
                  min: 2
                  increment: 1
                  interval: 150

            breeding-cap:
              enabled: true
              villagers:
                limit: 24
                range: 64
              animals:
                limit: 24
                range: 64

            mob-spawning:
              zombie-reinforcements:
                enforce-mobcap: true
                additional-capacity: 16
              nether-portal-randomticks:
                enforce-mobcap: true
                additional-capacity: 16
              monster-spawners:
                enforce-mobcap: true
                additional-capacity: 16
              categories:
                - category: 'MONSTER'
                  mobcap: 70
                  spawn-interval: 1
                - category: 'CREATURE'
                  mobcap: 10
                  spawn-interval: 400
                - category: 'AMBIENT'
                  mobcap: 15
                  spawn-interval: 20
                - category: 'AXOLOTLS'
                  mobcap: 5
                  spawn-interval: 20
                - category: 'UNDERGROUND_WATER_CREATURE'
                  mobcap: 5
                  spawn-interval: 20
                - category: 'WATER_CREATURE'
                  mobcap: 5
                  spawn-interval: 20
                - category: 'WATER_AMBIENT'
                  mobcap: 20
                  spawn-interval: 20

            commands:
              status-enabled: true
              mobcaps-enabled: true
              colors:
                primary: 'dark_aqua'
                secondary: 'green'
                tertiary: 'aqua'

            activation-range:
              enabled: true
              tick-new-entities: true
              use-vertical-range: true
              skip-non-immune: true
              villager-tick-panic: true
              villager-work-immunity-after: 20
              villager-work-immunity-for: 20
              excluded-entity-types:
                - 'minecraft:ghast'
                - 'minecraft:warden'
                - 'minecraft:hopper_minecart'
              default-activation-type:
                activation-range: 16
                tick-interval: 20
                wakeup-interval: -1
                extra-height-up: false
                extra-height-down: false
              custom-activation-types:
                - name: 'raider'
                  activation-range: 48
                  tick-interval: 20
                  wakeup-interval: 20
                  extra-height-up: true
                  extra-height-down: false
                  entity-matcher: ['typeof:raider']
                - name: 'water'
                  activation-range: 8
                  tick-interval: 20
                  wakeup-interval: 60
                  extra-height-up: false
                  extra-height-down: false
                  entity-matcher: ['typeof:water_animal']
                - name: 'villager'
                  activation-range: 16
                  tick-interval: 20
                  wakeup-interval: 30
                  extra-height-up: false
                  extra-height-down: false
                  entity-matcher: ['typeof:villager']
                - name: 'zombie'
                  activation-range: 24
                  tick-interval: 20
                  wakeup-interval: 20
                  extra-height-up: true
                  extra-height-down: false
                  entity-matcher: ['minecraft:zombie', 'minecraft:husk']
                - name: 'flying-monster'
                  activation-range: 48
                  tick-interval: 20
                  wakeup-interval: 20
                  extra-height-up: true
                  extra-height-down: false
                  entity-matcher: ['minecraft:ghast', 'minecraft:phantom']
                - name: 'monster'
                  activation-range: 24
                  tick-interval: 20
                  wakeup-interval: 20
                  extra-height-up: true
                  extra-height-down: false
                  entity-matcher: ['typeof:monster']
                - name: 'animal'
                  activation-range: 16
                  tick-interval: 20
                  wakeup-interval: 60
                  extra-height-up: false
                  extra-height-down: false
                  entity-matcher: ['typeof:animal', 'typeof:ambient']
                - name: 'creature'
                  activation-range: 16
                  tick-interval: 20
                  wakeup-interval: 30
                  extra-height-up: false
                  extra-height-down: false
                  entity-matcher: ['typeof:mob']
            """;

    private static final String SERVER_CORE_OPTIMIZATIONS = """
            # These options trade tiny vanilla-parity differences for lower server tick cost.
            reduce-sync-loads: true
            cache-ticking-chunks: true
            fast-biome-lookups: true
            cancel-duplicate-fluid-ticks: true
            """;
}
