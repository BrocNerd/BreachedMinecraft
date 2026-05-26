package nrd.breached.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import nrd.breached.Breached;
import nrd.breached.worldgen.BreachedStructureDefinition;
import nrd.breached.worldgen.BreachedStructureDefinitions;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BreachedConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = Breached.MOD_ID + ".json";
    private static BreachedConfig INSTANCE = createDefault();

    public int plannedCandidateEvaluationsPerStructureTick = 4;
    public MinorPoiSettings minorPoi = new MinorPoiSettings();
    public MajorStructureLootSettings majorStructureLoot = new MajorStructureLootSettings();
    public Map<String, StructureSettings> structures = createDefaultStructureSettings();

    public static BreachedConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) {
                BreachedConfig defaultConfig = createDefault();
                Files.writeString(configPath, GSON.toJson(defaultConfig));
                INSTANCE = defaultConfig;
                System.out.println("[Breached] Created default config at " + configPath + ".");
                return INSTANCE;
            }

            try (Reader reader = Files.newBufferedReader(configPath)) {
                BreachedConfig loadedConfig = GSON.fromJson(reader, BreachedConfig.class);
                if (loadedConfig == null) {
                    loadedConfig = createDefault();
                }

                loadedConfig.normalize();
                saveNormalizedConfig(configPath, loadedConfig);
                INSTANCE = loadedConfig;
                System.out.println("[Breached] Loaded config from " + configPath + ".");
                return INSTANCE;
            }
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            INSTANCE = createDefault();
            System.out.println("[Breached] Failed to load config at " + configPath
                    + "; using built-in defaults. Error: " + exception.getMessage());
            return INSTANCE;
        }
    }

    private static void saveNormalizedConfig(Path configPath, BreachedConfig config) {
        try {
            Files.writeString(configPath, GSON.toJson(config));
        } catch (IOException exception) {
            System.out.println("[Breached] Loaded config but could not write updated defaults to " + configPath
                    + ". Error: " + exception.getMessage());
        }
    }

    public static BreachedConfig get() {
        return INSTANCE;
    }

    public BreachedStructureDefinition applyStructureOverrides(BreachedStructureDefinition definition) {
        int plannedCandidateCount = definition.plannedCandidateCount();
        int preferredSpacing = definition.preferredSpacingFromBreachedStructures();
        int minimumSpacing = definition.minimumSpacingFromBreachedStructures();

        if (definition.spacingGroup() == BreachedStructureDefinition.SpacingGroup.MINOR) {
            preferredSpacing = minorPoi.preferredSpacing;
            minimumSpacing = minorPoi.minimumSpacing;
        }

        StructureSettings settings = structures.get(BreachedStructureDefinitions.key(definition));
        if (settings != null) {
            if (settings.plannedCandidateCount != null) {
                plannedCandidateCount = settings.plannedCandidateCount;
            }
            if (settings.preferredSpacing != null) {
                preferredSpacing = settings.preferredSpacing;
            }
            if (settings.minimumSpacing != null) {
                minimumSpacing = settings.minimumSpacing;
            }
        }

        BreachedStructureDefinition configuredDefinition = definition;
        if (plannedCandidateCount != configuredDefinition.plannedCandidateCount()) {
            configuredDefinition = configuredDefinition.withPlannedCandidateCount(plannedCandidateCount);
        }
        if (preferredSpacing != configuredDefinition.preferredSpacingFromBreachedStructures()
                || minimumSpacing != configuredDefinition.minimumSpacingFromBreachedStructures()) {
            configuredDefinition = configuredDefinition.withSpacing(preferredSpacing, minimumSpacing);
        }

        return configuredDefinition;
    }

    private static BreachedConfig createDefault() {
        BreachedConfig config = new BreachedConfig();
        config.normalize();
        return config;
    }

    private void normalize() {
        plannedCandidateEvaluationsPerStructureTick = clamp(plannedCandidateEvaluationsPerStructureTick, 1, 64);
        if (minorPoi == null) {
            minorPoi = new MinorPoiSettings();
        }
        minorPoi.normalize();
        if (majorStructureLoot == null) {
            majorStructureLoot = new MajorStructureLootSettings();
        }
        majorStructureLoot.normalize();

        if (structures == null) {
            structures = createDefaultStructureSettings();
        } else {
            createDefaultStructureSettings().forEach(structures::putIfAbsent);
        }
        for (StructureSettings settings : structures.values()) {
            if (settings != null) {
                settings.normalize();
            }
        }
    }

    private static Map<String, StructureSettings> createDefaultStructureSettings() {
        Map<String, StructureSettings> settings = new LinkedHashMap<>();
        settings.put("breached:swordstatue", new StructureSettings(96, null, null));
        settings.put("breached:portal", new StructureSettings(64, null, null));
        settings.put("breached:horace", new StructureSettings(64, null, null));
        settings.put("breached:pinktree", new StructureSettings(96, null, null));
        settings.put("breached:bigboat", new StructureSettings(96, null, null));
        settings.put("breached:cavehut", new StructureSettings(null, null, null));
        settings.put("breached:pueblo1", new StructureSettings(null, null, null));
        settings.put("breached:pueblo2", new StructureSettings(null, null, null));
        settings.put("breached:raft", new StructureSettings(null, null, null));
        settings.put("breached:waystop", new StructureSettings(null, null, null));
        return settings;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class MinorPoiSettings {
        public int smallWorldBudget = 40;
        public int standardWorldBudget = 128;
        public int chunkChanceDivisor = 10;
        public int localCandidatesPerChunk = 6;
        public int pendingChunksPerTick = 2;
        public int playerScanIntervalTicks = 200;
        public int playerScanRadiusChunks = 2;
        public int spreadCellSizeBlocks = 160;
        public int maxPerSpreadCell = 1;
        public int hardMinimumSpacing = 64;
        public int preferredSpacing = 96;
        public int minimumSpacing = 64;

        private void normalize() {
            smallWorldBudget = clamp(smallWorldBudget, 0, 4096);
            standardWorldBudget = clamp(standardWorldBudget, 0, 4096);
            chunkChanceDivisor = clamp(chunkChanceDivisor, 1, 100000);
            localCandidatesPerChunk = clamp(localCandidatesPerChunk, 1, 64);
            pendingChunksPerTick = clamp(pendingChunksPerTick, 1, 64);
            playerScanIntervalTicks = clamp(playerScanIntervalTicks, 1, 72000);
            playerScanRadiusChunks = clamp(playerScanRadiusChunks, 0, 16);
            spreadCellSizeBlocks = spreadCellSizeBlocks <= 0 ? 160 : clamp(spreadCellSizeBlocks, 16, 4096);
            maxPerSpreadCell = maxPerSpreadCell <= 0 ? 1 : clamp(maxPerSpreadCell, 1, 64);
            hardMinimumSpacing = clamp(hardMinimumSpacing, 0, 2048);
            minimumSpacing = clamp(Math.max(minimumSpacing, hardMinimumSpacing), 0, 2048);
            preferredSpacing = clamp(Math.max(preferredSpacing, minimumSpacing), 0, 4096);
        }
    }

    public static final class MajorStructureLootSettings {
        public boolean enabled = true;
        public boolean announceRestocks = true;
        public int minRestockIntervalTicks = 36000;
        public int maxRestockIntervalTicks = 144000;
        public int scanIntervalTicks = 1200;

        private void normalize() {
            minRestockIntervalTicks = clamp(minRestockIntervalTicks, 20, 1728000);
            maxRestockIntervalTicks = clamp(Math.max(maxRestockIntervalTicks, minRestockIntervalTicks), 20, 1728000);
            scanIntervalTicks = clamp(scanIntervalTicks, 20, 72000);
        }
    }

    public static final class StructureSettings {
        public Integer plannedCandidateCount;
        public Integer preferredSpacing;
        public Integer minimumSpacing;

        public StructureSettings() {
        }

        private StructureSettings(Integer plannedCandidateCount, Integer preferredSpacing, Integer minimumSpacing) {
            this.plannedCandidateCount = plannedCandidateCount;
            this.preferredSpacing = preferredSpacing;
            this.minimumSpacing = minimumSpacing;
        }

        private void normalize() {
            if (plannedCandidateCount != null) {
                plannedCandidateCount = clamp(plannedCandidateCount, 0, 4096);
            }
            if (minimumSpacing != null) {
                minimumSpacing = clamp(minimumSpacing, 0, 2048);
            }
            if (preferredSpacing != null) {
                int minimum = minimumSpacing == null ? 0 : minimumSpacing;
                preferredSpacing = clamp(Math.max(preferredSpacing, minimum), 0, 4096);
            }
        }
    }
}
