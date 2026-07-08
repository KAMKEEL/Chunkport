package com.hivemc.chunker.cli;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hivemc.chunker.cli.messenger.Messenger;
import com.hivemc.chunker.cli.messenger.messaging.DimensionPruningList;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.parser.SimpleMappingsTemplateGenerator;
import com.hivemc.chunker.pruning.PruningConfig;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import com.hivemc.chunker.schematic.SchematicConverter;
import com.google.gson.JsonArray;
import java.io.IOException;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is the entry-point for the command-line version of Chunker.
 * Example usage: java -jar Chunker.jar --inputDirectory myWorld --outputFormat JAVA_1_20_5 --outputDirectory converted
 * If no arguments are provided then usage is printed.
 * <p/>
 * Mappings can be put in the world folder and by default these are loaded from if there is no parameter, these are done
 * for the following:
 * - block_mappings.chunker.json
 * - converter_settings.chunker.json
 * - dimension_mappings.chunker.json
 * - pruning.chunker.json
 * - world_settings.chunker.json
 * These can be generated using the Chunker web GUI in the Converter Settings tab.
 */
@CommandLine.Command(name = "Chunker", versionProvider = VersionProvider.class, mixinStandardHelpOptions = true)
public class CLI implements Runnable {
    private static final TypeToken<Map<Dimension, Dimension>> DIMENSION_INPUT_TO_OUTPUT_TYPE = new TypeToken<>() {
    };
    private static final Gson GSON = new Gson();

    @CommandLine.Option(
            names = {"--inputDirectory", "-i"},
            description = "Directory to read the world from."
    )
    private File inputDirectory;

    @CommandLine.Option(
            names = {"--inputSchematic", "--input-schem"},
            description = "Directory containing .schematic/.schem files to convert."
    )
    private File inputSchematic;

    @CommandLine.Option(
            names = {"--outputFormat", "-f"},
            description = "The format to convert the world to.",
            converter = EncodingTypeValidator.class
    )
    private String format;

    @CommandLine.Option(
            names = {"--outputDirectory", "-o"},
            description = "Directory to write the world to."
    )
    private File outputDirectory;

    @CommandLine.Option(
            names = {"--outputSchematic", "--output-schem"},
            description = "Directory to write converted .schematic files to."
    )
    private File outputSchematic;

    @CommandLine.Option(
            names = {"--blockMappings", "-m"},
            description = "A JSON file/object containing block mappings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile blockMappings;

    @CommandLine.Option(
            names = {"--simpleBlockMappings", "-sm"},
            description = "A text file containing simple block mappings in the form 'old[state=value] -> new[state=value]'."
    )
    private File simpleBlockMappings;

    @CommandLine.Option(
            names = {"--levelConvert"},
            description = "Path to a level.dat used to resolve simple mappings to legacy IDs (Java 1.12 and below only)."
    )
    private File levelConvert;

    @CommandLine.Option(
            names = {"--generateSimpleMappingsTemplate"},
            description = "Writes an example simple mapping file to the provided path and exits."
    )
    private File generateSimpleMappingsTemplate;

    @CommandLine.Option(
            names = {"--convertMapping"},
            description = "Parse the supplied simple mappings and output generated.json next to them."
    )
    private boolean convertMapping;

    @CommandLine.Option(
            names = {"--worldSettings", "-s"},
            description = "A JSON file/object containing world settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile worldSettings;

    @CommandLine.Option(
            names = {"--pruning", "-p"},
            description = "A JSON file/object containing pruning settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile pruningSettings;

    @CommandLine.Option(
            names = {"--converterSettings", "-c"},
            description = "A JSON file/object containing converter settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile converterSettings;

    @CommandLine.Option(
            names = {"--dimensionMappings", "-d"},
            description = "A JSON file/object containing dimension mappings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile dimensionMappings;

    @CommandLine.Option(
            names = {"--keepOriginalNBT", "-k"},
            description = "Whether original NBT should be kept and written to the output world (only works if the output is the same as the input)."
    )
    private boolean keepOriginalNBT;

    @CommandLine.Option(
            names = {"--enableNEIDs"},
            description = "Enable NotEnoughIDs formatting when converting to a legacy version."
    )
    private boolean enableNEIDs;

    @CommandLine.Option(
            names = {"--legacySimpleMappings"},
            description = "Apply simple mappings after flattening using legacy identifiers (IDs or ID:META)."
    )
    private boolean legacySimpleMappings;

    @CommandLine.Option(
            names = {"--debug"},
            description = "Enable verbose debug logging."
    )
    private boolean debug;

    // Track whether simple mappings were provided so we can automatically
    // enable legacy mapping behaviour for legacy outputs.
    private boolean simpleMappingsProvided;

    /**
     * Merge two mappings files by appending the identifier list from the second
     * to the first. This is primarily used so simple mappings can extend a JSON
     * mappings file when both are supplied.
     */
    public static MappingsFile mergeMappings(MappingsFile base, MappingsFile extra) {
        JsonObject baseJson = base.toJson().getAsJsonObject();
        JsonArray baseIds = baseJson.getAsJsonArray("identifiers");
        if (baseIds == null) {
            baseIds = new JsonArray();
            baseJson.add("identifiers", baseIds);
        }

        JsonObject extraJson = extra.toJson().getAsJsonObject();
        JsonArray extraIds = extraJson.getAsJsonArray("identifiers");
        if (extraIds != null) {
            for (int i = 0; i < extraIds.size(); i++) {
                baseIds.add(extraIds.get(i));
            }
        }

        return MappingsFile.load(baseJson);
    }

    /**
     * Main entry point for the CLI
     *
     * @param args the arguments which should be parsed by picocli.
     */
    public static void main(String[] args) {
        // Special case for running the messenger
        if (args.length == 1 && args[0].equals("messenger")) {
            Messenger.main(args);
            return;
        }

        // Parse args
        int exitCode = new CommandLine(new CLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        WorldConverter worldConverter = null;
        try {
            // Create the converter
            Stopwatch stopwatch = Stopwatch.createStarted();

            // Validate input files up front with clear errors
            if (levelConvert != null && !levelConvert.isFile()) {
                System.err.println("ERROR: --levelConvert level.dat not found: " + levelConvert.getAbsolutePath());
                System.err.println("       Provide the level.dat of the target 1.7.10 server (contains the FML block ID registry).");
                return;
            }
            if (simpleBlockMappings != null && !simpleBlockMappings.isFile()) {
                System.err.println("ERROR: --simpleBlockMappings file not found: " + simpleBlockMappings.getAbsolutePath());
                return;
            }

            if (generateSimpleMappingsTemplate != null) {
                try {
                    SimpleMappingsTemplateGenerator.writeTemplate(generateSimpleMappingsTemplate.toPath());
                    System.out.println("Template written to " + generateSimpleMappingsTemplate.getAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Failed to write template: " + e.getMessage());
                }
                return;
            }

            if (convertMapping) {
                if (simpleBlockMappings == null) {
                    System.err.println("--convertMapping requires --simpleBlockMappings to be set.");
                    return;
                }
                try {
                    if (levelConvert != null) {
                        com.hivemc.chunker.mapping.LevelConvertMappings.load(levelConvert);
                        if (debug) {
                            System.out.println("[DEBUG] Loaded " + com.hivemc.chunker.mapping.LevelConvertMappings.size() + " level.dat mappings");
                        }
                    }
                    MappingsFile mappingsFile = SimpleMappingsParser.parse(simpleBlockMappings.toPath());
                    if (debug) {
                        int count = mappingsFile.toJson().getAsJsonObject().getAsJsonArray("identifiers").size();
                        System.out.println("[DEBUG] Parsed " + count + " simple mappings");
                    }
                    Path outPath = simpleBlockMappings.toPath().getParent().resolve("generated.json");
                    Files.writeString(outPath, mappingsFile.toJsonString());
                    System.out.println("Generated mapping file: " + outPath.toAbsolutePath());
                } catch (Exception e) {
                    System.err.println("Failed to generate mapping: " + e.getMessage());
                    throw new RuntimeException(e);
                }
                return;
            }

            boolean schematicMode = inputSchematic != null || outputSchematic != null;
            if (schematicMode) {
                if (inputSchematic == null || outputSchematic == null) {
                    System.err.println("--inputSchematic and --outputSchematic must both be provided for schematic conversion.");
                    return;
                }

                if (!inputSchematic.isDirectory()) {
                    System.err.println("--inputSchematic must point to a directory containing schematics.");
                    return;
                }

                if (outputSchematic.exists() && !outputSchematic.isDirectory()) {
                    System.err.println("--outputSchematic must point to a directory for converted schematics.");
                    return;
                }

                if (format != null && !format.equalsIgnoreCase("JAVA_1_7_10")) {
                    System.err.println("Schematic conversion only supports JAVA_1_7_10 outputs.");
                    return;
                }

                try {
                    if (levelConvert != null) {
                        LevelConvertMappings.load(levelConvert);
                        if (LevelConvertMappings.size() == 0) {
                            System.err.println("WARNING: no FML block ID mappings found in " + levelConvert.getName()
                                    + " - is this the level.dat of a Forge server?");
                        } else {
                            System.out.println("Loaded " + LevelConvertMappings.size() + " block ID mappings from " + levelConvert.getName());
                        }
                    } else {
                        System.out.println("Note: no --levelConvert provided, mod block IDs will resolve using vanilla ID tables only.");
                    }

                    MappingsFile loadedMappings = null;
                    if (blockMappings != null) {
                        try {
                            loadedMappings = MappingsFile.load(blockMappings.getJSONObjectString());
                        } catch (Exception e) {
                            System.err.println("Failed to parse block mappings.");
                            throw new RuntimeException(e);
                        }
                    }

                    if (simpleBlockMappings != null) {
                        try {
                            MappingsFile mappingsFile = SimpleMappingsParser.parse(simpleBlockMappings.toPath());
                            int count = mappingsFile.toJson().getAsJsonObject().getAsJsonArray("identifiers").size();
                            System.out.println("Parsed " + count + " mapping rules from " + simpleBlockMappings.getName());
                            simpleMappingsProvided = true;
                            if (loadedMappings == null) {
                                loadedMappings = mappingsFile;
                            } else {
                                loadedMappings = mergeMappings(loadedMappings, mappingsFile);
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to parse simple block mappings (" + simpleBlockMappings.getName() + "): " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                    } else {
                        System.out.println("Note: no --simpleBlockMappings provided, blocks convert using built-in tables only.");
                    }

                    boolean useLegacySimpleMappings = legacySimpleMappings || simpleMappingsProvided;
                    int convertedCount = SchematicConverter.convertDirectory(inputSchematic.toPath(), outputSchematic.toPath(), enableNEIDs, loadedMappings, useLegacySimpleMappings);
                    System.out.println("Converted " + convertedCount + " schematics into " + outputSchematic.getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("Failed to convert schematic: " + e.getMessage());
                    throw new RuntimeException(e);
                }
                return;
            }

            if (inputDirectory == null || outputDirectory == null || format == null) {
                System.err.println("--inputDirectory, --outputDirectory and --outputFormat are required when not converting schematics.");
                return;
            }

            // Create the converter
            worldConverter = new WorldConverter(UUID.randomUUID());
            worldConverter.setDebug(debug);
            File logFile = new File(outputDirectory, "conversion.log");
            worldConverter.setLogFile(logFile);
            if (levelConvert != null) {
                worldConverter.setLegacyLevelDat(levelConvert);
            }

            // If any of the mappings file aren't present, check if they're inside the input world

            // Apply block mappings if they're present and parse
            if (blockMappings == null) {
                Path file = inputDirectory.toPath().resolve("block_mappings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        blockMappings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated block mappings.");
                    throw new RuntimeException(e);
                }
            }

            MappingsFile loadedMappings = null;
            if (blockMappings != null) {
                try {
                    loadedMappings = MappingsFile.load(blockMappings.getJSONObjectString());
                } catch (Exception e) {
                    System.err.println("Failed to parse block mappings.");
                    throw new RuntimeException(e);
                }
            }

            if (simpleBlockMappings != null) {
                try {
                    if (levelConvert != null) {
                        LevelConvertMappings.load(levelConvert);
                        if (LevelConvertMappings.size() == 0) {
                            System.err.println("WARNING: no FML block ID mappings found in " + levelConvert.getName()
                                    + " - is this the level.dat of a Forge server?");
                        } else {
                            System.out.println("Loaded " + LevelConvertMappings.size() + " block ID mappings from " + levelConvert.getName());
                        }
                    }
                    MappingsFile mappingsFile = SimpleMappingsParser.parse(simpleBlockMappings.toPath());
                    int count = mappingsFile.toJson().getAsJsonObject().getAsJsonArray("identifiers").size();
                    System.out.println("Parsed " + count + " mapping rules from " + simpleBlockMappings.getName());
                    simpleMappingsProvided = true;
                    if (loadedMappings == null) {
                        loadedMappings = mappingsFile;
                    } else {
                        loadedMappings = mergeMappings(loadedMappings, mappingsFile);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse simple block mappings.");
                    throw new RuntimeException(e);
                }
            }

            if (loadedMappings != null) {
                worldConverter.setBlockMappings(new MappingsFileResolvers(loadedMappings));
            }

            // Apply world settings if they're present and parse
            if (worldSettings == null) {
                Path file = inputDirectory.toPath().resolve("world_settings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        worldSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated world settings.");
                    throw new RuntimeException(e);
                }
            }
            if (worldSettings != null) {
                try {
                    worldConverter.setChangedSettings(GSON.fromJson(worldSettings.getJSONObjectString(), JsonObject.class));
                } catch (Exception e) {
                    System.err.println("Failed to parse world settings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply pruning settings if they're present and parse
            if (pruningSettings == null) {
                Path file = inputDirectory.toPath().resolve("pruning.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        pruningSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated pruning settings.");
                    throw new RuntimeException(e);
                }
            }
            if (pruningSettings != null) {
                try {
                    DimensionPruningList pruningList = GSON.fromJson(pruningSettings.getJSONObjectString(), DimensionPruningList.class);
                    if (pruningList.getConfigs() != null && !pruningList.getConfigs().isEmpty()) {
                        Map<Dimension, PruningConfig> pruningConfigs = new Object2ObjectOpenHashMap<>(pruningList.getConfigs().size());
                        for (int i = 0; i < pruningList.getConfigs().size(); i++) {
                            pruningConfigs.put(Dimension.values()[i], pruningList.getConfigs().get(i));
                        }
                        worldConverter.setPruningConfigs(pruningConfigs);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse pruning settings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply dimension mappings if they're present and parse
            if (dimensionMappings == null) {
                Path file = inputDirectory.toPath().resolve("dimension_mappings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        dimensionMappings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated dimension mappings.");
                    throw new RuntimeException(e);
                }
            }
            if (dimensionMappings != null) {
                try {
                    Map<Dimension, Dimension> dimensionMapping = GSON.fromJson(dimensionMappings.getJSONObjectString(), DIMENSION_INPUT_TO_OUTPUT_TYPE);
                    worldConverter.setDimensionMapping(dimensionMapping);
                } catch (Exception e) {
                    System.err.println("Failed to parse dimension mappings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply converter settings if they're present and parse
            if (converterSettings == null) {
                Path file = inputDirectory.toPath().resolve("converter_settings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        converterSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated converter settings.");
                    throw new RuntimeException(e);
                }
            }
            if (converterSettings != null) {
                try {
                    JsonObject parsedConverterSettings = GSON.fromJson(converterSettings.getJSONObjectString(), JsonObject.class);

                    // Load the settings (the names are based on original Chunker field names)
                    boolean skipMaps = parsedConverterSettings.has("mapConversion") && !parsedConverterSettings.get("mapConversion").getAsBoolean();
                    boolean skipLootTables = parsedConverterSettings.has("lootTableConversion") && !parsedConverterSettings.get("lootTableConversion").getAsBoolean();
                    boolean skipItemConversion = parsedConverterSettings.has("itemConversion") && !parsedConverterSettings.get("itemConversion").getAsBoolean();
                    boolean skipBlockConnections = parsedConverterSettings.has("blockConnections") && !parsedConverterSettings.get("blockConnections").getAsBoolean();
                    boolean enableCompact = !parsedConverterSettings.has("enableCompact") || parsedConverterSettings.get("enableCompact").getAsBoolean();
                    boolean discardEmptyChunks = parsedConverterSettings.has("discardEmptyChunks") && parsedConverterSettings.get("discardEmptyChunks").getAsBoolean();
                    boolean preventYBiomeBlending = parsedConverterSettings.has("preventYBiomeBlending") && parsedConverterSettings.get("preventYBiomeBlending").getAsBoolean();

                    // Apply the settings
                    worldConverter.setProcessMaps(!skipMaps);
                    worldConverter.setProcessLootTables(!skipLootTables);
                    worldConverter.setProcessItems(!skipItemConversion);
                    worldConverter.setProcessColumnPreTransform(!skipBlockConnections);
                    worldConverter.setLevelDBCompaction(enableCompact);
                    worldConverter.setDiscardEmptyChunks(discardEmptyChunks);
                    worldConverter.setPreventYBiomeBlending(preventYBiomeBlending);
                } catch (Exception e) {
                    System.err.println("Failed to parse converter settings.");
                    throw new RuntimeException(e);
                }
            }

            // Check for the original NBT option
            worldConverter.setAllowNBTCopying(keepOriginalNBT);

            // Enable legacy simple mappings prior to resolver construction so
            // the writer can apply them correctly. This is normally set again
            // after validation below but needs to be enabled before creating
            // the reader and writer.
            if (legacySimpleMappings || (simpleMappingsProvided && format.toLowerCase().startsWith("java"))) {
                worldConverter.setLegacySimpleMappings(true);
            }

            // Create the reader / writer (note: converter settings cannot be set after this point)
            Optional<? extends LevelReader> reader = EncodingType.findReader(inputDirectory, worldConverter);
            Optional<? extends LevelWriter> writer = Messenger.findWriter(format, worldConverter, outputDirectory);
            if (reader.isEmpty()) {
                System.err.println("Failed to find suitable reader for the world.");
                return;
            }
            if (writer.isEmpty()) {
                System.err.println("Failed to find suitable writer for the world.");
                return;
            }
            if (levelConvert != null) {
                if (writer.get().getEncodingType() != EncodingType.JAVA ||
                        !writer.get().getVersion().equals(new Version(1, 7, 10))) {
                    System.err.println("--levelConvert can only be used when converting to Java 1.7.10.");
                    return;
                }
            }
            System.out.println(MessageFormat.format(
                    "Converting from {0} {1} to {2} {3}",
                    reader.get().getEncodingType().getName(),
                    reader.get().getVersion(),
                    writer.get().getEncodingType().getName(),
                    writer.get().getVersion()
            ));

            // Safety check for NBT copying since it cannot break worlds
            if (worldConverter.shouldAllowNBTCopying()) {
                if (reader.get().getEncodingType() != writer.get().getEncodingType()) {
                    System.err.println("Original NBT is not available for this conversion due to differing formats. Please disable it to continue.");
                    System.exit(0);
                } else {
                    System.out.println("Original NBT will be copied for this world, if you experience issues consider turning this option off as incompatible NBT may be written.");
                }
            }

            if (enableNEIDs) {
                // Only legacy Java region formats support the extended Blocks16 tag.
                if (writer.get().getEncodingType() != EncodingType.JAVA || !writer.get().getVersion().isLessThan(1, 13, 0)) {
                    System.err.println("NotEnoughIDs is only supported when converting to legacy Java versions (1.12 or lower). Please remove the flag to continue.");
                    System.exit(0);
                }

                // Enable NotEnoughIDs support so the writer will include Blocks16 like mIDas Platinum.
                worldConverter.setNotEnoughIDs(true);
            }

            if (legacySimpleMappings) {
                if (writer.get().getEncodingType() != EncodingType.JAVA || !writer.get().getVersion().isLessThan(1, 13, 0)) {
                    System.err.println("--legacySimpleMappings is only supported when converting to legacy Java versions (1.12 or lower). Please remove the flag to continue.");
                    System.exit(0);
                }

                worldConverter.setLegacySimpleMappings(true);
            } else if (simpleMappingsProvided && writer.get().getEncodingType() == EncodingType.JAVA && writer.get().getVersion().isLessThan(1, 13, 0)) {
                // Automatically enable legacy simple mappings when converting to legacy
                // versions using a simple mapping file.
                worldConverter.setLegacySimpleMappings(true);
            }

            // Add the handler for the compaction signal
            worldConverter.setCompactionSignal((started) -> {
                if (started) {
                    System.out.println("Compacting world, this may take a while...");
                } else {
                    System.out.println("Finished compacting world.");
                }
            });

            // Run the conversion
            TrackedTask<Void> conversionTask = worldConverter.convert(reader.get(), writer.get());

            // Redirect any failure to an atomic, so we can exit our loop
            AtomicReference<Throwable> failed = new AtomicReference<>();
            conversionTask.future().exceptionally((exception) -> {
                failed.set(exception);
                return null;
            });

            double value = -1D;
            while (value != 1 && failed.get() == null) {
                double polled = conversionTask.getProgress();
                if (polled != value) {
                    System.out.printf("%.2f%%%n", (polled * 100D));
                    value = polled;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            // Handle result
            if (failed.get() != null) {
                System.err.println("Failed with exception");
                failed.get().printStackTrace();
                worldConverter.closeLogFile();
                System.exit(1);
            } else {
                Duration duration = stopwatch.elapsed();
                System.out.println("Conversion complete! Took " + String.format("%sm %ss %sms",
                        duration.toMinutesPart(),
                        duration.toSecondsPart(),
                        duration.toMillisPart()
                ));
                worldConverter.closeLogFile();
                System.exit(0);
            }
        } catch (OutOfMemoryError e) {
            try {
                e.printStackTrace();
            } catch (OutOfMemoryError e2) {
                // We tried printing it
            }

            // Use error code 12 for OOM
            if (worldConverter != null) {
                worldConverter.closeLogFile();
            }
            System.exit(12);
        }
    }
}
