package com.hivemc.chunker.schematic;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.JavaBlockIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIDResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIdentifierResolver;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.mapping.LevelConvertMappings;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.identifier.Identifier;
import com.hivemc.chunker.mapping.identifier.states.StateValue;
import com.hivemc.chunker.mapping.identifier.states.StateValueString;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.array.LongArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal converter capable of reading Sponge .schem files (v1/v2/v3), Litematica
 * .litematic files or classic WorldEdit .schematic files and writing them back to
 * the classic schematic format used by WorldEdit GTNH (1.7.10) with support for
 * extended block IDs.
 */
public final class SchematicConverter {
    private static final String SCHEMATIC_ROOT_NAME = "Schematic";
    /** Version used to interpret numeric block IDs found in classic schematics. */
    private static final Version LEGACY_INPUT_VERSION = new Version(1, 12, 2);
    /** Version schematics are written for. */
    private static final Version TARGET_VERSION = new Version(1, 7, 10);
    /** Version assumed for Sponge schematics without a DataVersion (WorldEdit 1.13 era). */
    private static final Version DEFAULT_SPONGE_VERSION = new Version(1, 13, 2);
    /** First DataVersion using flattened identifiers (1.13). */
    private static final int FLATTENING_DATA_VERSION = 1519;

    /** Resolves 1.12-era numeric IDs to identifiers for classic schematic inputs. */
    private static final JavaLegacyBlockIDResolver LEGACY_BLOCK_RESOLVER = new JavaLegacyBlockIDResolver(LEGACY_INPUT_VERSION);
    /** Resolves identifiers to 1.7.10 numeric IDs for the written schematic. */
    private static final JavaLegacyBlockIDResolver TARGET_BLOCK_ID_RESOLVER = new JavaLegacyBlockIDResolver(TARGET_VERSION);

    // Schematics store entire builds in single NBT arrays, so the chunk-sized decode
    // limits need to be raised while reading them.
    private static final int SCHEMATIC_MAX_BYTE_ARRAY_LENGTH = 1 << 30; // 1GB of bytes
    private static final int SCHEMATIC_MAX_INT_ARRAY_LENGTH = 1 << 28; // 1GB of ints
    private static final int SCHEMATIC_MAX_LONG_ARRAY_LENGTH = 1 << 27; // 1GB of longs

    private SchematicConverter() {
    }

    /**
     * Convert the input schematic (classic or Sponge) into a classic
     * WorldEdit schematic written to {@code output}.
     *
     * @param input      the input schematic file.
     * @param output     the destination schematic file.
     * @param allowNeids whether NotEnoughIDs encoding (AddBlocks2) should be
     *                   emitted when blocks exceed 4095.
     * @throws IOException if reading or writing fails.
     */
    public static void convert(File input, File output, boolean allowNeids) throws IOException {
        convert(input, output, allowNeids, null, false);
    }

    public static void convert(File input, File output, boolean allowNeids, @Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) throws IOException {
        convert(input, output, allowNeids, new ResolutionContext(mappingsFile, legacySimpleMappings));
    }

    private static void convert(File input, File output, boolean allowNeids, ResolutionContext context) throws IOException {
        ReadResult result = readInternal(input.toPath(), context);
        SchematicData data = result.data();
        // Palette based inputs (Sponge/Litematica) already apply mappings while
        // resolving the palette, classic inputs apply them per block after reading.
        if (!result.paletteResolved() && context.mappingsFile != null) {
            data = applyMappings(data, context.mappingsFile, context.legacySimpleMappings);
        }
        writeClassic(output.toPath(), data, allowNeids);
    }

    /**
     * Convert all schematics found under the input directory into the output
     * directory, preserving relative paths and writing results with a
     * <code>.schematic</code> extension.
     *
     * @param inputDirectory  root directory to scan for schematics
     * @param outputDirectory directory to emit converted schematics into
     * @param allowNeids      whether NEIDs encoding should be emitted when needed
     * @param mappingsFile    optional mappings file used during conversion
     * @param legacySimpleMappings whether legacy simple mappings are enabled
     * @return number of schematics converted
     * @throws IOException if reading or writing fails
     */
    public static int convertDirectory(Path inputDirectory, Path outputDirectory, boolean allowNeids, @Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) throws IOException {
        Files.createDirectories(outputDirectory);

        List<Path> schematics;
        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            schematics = paths
                    .filter(Files::isRegularFile)
                    .filter(SchematicConverter::isSchematicFile)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (schematics.isEmpty()) {
            System.err.println("WARNING: no .schematic/.schem/.litematic files found in " + inputDirectory.toAbsolutePath());
            return 0;
        }
        System.out.println("Found " + schematics.size() + " schematic(s) in " + inputDirectory.toAbsolutePath());

        // Share a single resolution context (and its resolvers) across all files
        ResolutionContext context = new ResolutionContext(mappingsFile, legacySimpleMappings);

        int converted = 0;
        for (Path input : schematics) {
            Path relative = inputDirectory.relativize(input);
            String fileName = relative.getFileName().toString();
            String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            Path targetRelative = relative.resolveSibling(base + ".schematic");
            Path output = outputDirectory.resolve(targetRelative);
            Files.createDirectories(output.getParent());
            try {
                convert(input.toFile(), output.toFile(), allowNeids, context);
                converted++;
                System.out.println("Converted " + relative);
            } catch (Exception e) {
                System.err.println("Failed to convert " + relative + ": " + e.getMessage());
            }
        }

        return converted;
    }

    private static boolean isSchematicFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".schem") || name.endsWith(".schematic") || name.endsWith(".litematic");
    }

    /**
     * Read a schematic from Sponge .schem (v1/v2/v3), Litematica .litematic or
     * classic .schematic.
     */
    public static SchematicData read(Path path) throws IOException {
        return readInternal(path, new ResolutionContext(null, false)).data();
    }

    private static ReadResult readInternal(Path path, ResolutionContext context) throws IOException {
        CompoundTag root = readRoot(path);
        if (root == null) {
            throw new IOException("Invalid schematic: no root tag present");
        }

        // Sponge v3 nests everything inside a "Schematic" compound
        Tag<?> nested = root.get("Schematic");
        if (nested instanceof CompoundTag nestedCompound) {
            root = nestedCompound;
        }

        // Litematica stores named sub-regions inside a "Regions" compound
        Tag<?> regionsTag = root.get("Regions");
        if (regionsTag instanceof CompoundTag regionsCompound) {
            return new ReadResult(readLitematic(path, root, regionsCompound, context), true);
        }

        // Sponge v3 groups block information inside a "Blocks" compound
        Tag<?> blocksTag = root.get("Blocks");
        if (blocksTag instanceof CompoundTag blocksCompound && blocksCompound.contains("Palette")) {
            return new ReadResult(readSponge(path, root, blocksCompound.getCompound("Palette"), blocksCompound.getByteArray("Data"), context), true);
        }

        // Sponge v1/v2 keeps Palette/BlockData on the root
        if (root.contains("Palette") && root.contains("BlockData")) {
            return new ReadResult(readSponge(path, root, root.getCompound("Palette"), root.getByteArray("BlockData"), context), true);
        }
        return new ReadResult(readClassic(root), false);
    }

    private static SchematicData readClassic(CompoundTag root) throws IOException {
        short width = root.getShort("Width", (short) -1);
        short height = root.getShort("Height", (short) -1);
        short length = root.getShort("Length", (short) -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        byte[] blocks = root.getByteArray("Blocks");
        byte[] blockData = root.getByteArray("Data");
        if (blocks.length != blockData.length) {
            throw new IOException("Mismatched block/data lengths");
        }
        if (blocks.length != (long) width * height * length) {
            throw new IOException("Blocks length " + blocks.length + " does not match dimensions "
                    + width + "x" + height + "x" + length);
        }

        byte[] addBlocks = root.contains("AddBlocks") ? root.getByteArray("AddBlocks") : null;
        byte[] addBlocks2 = root.contains("AddBlocks2") ? root.getByteArray("AddBlocks2") : null;
        byte[] addData = root.contains("AddData") ? root.getByteArray("AddData") : null;

        int[] blockIds = new int[blocks.length];
        int[] meta = new int[blocks.length];
        for (int index = 0; index < blocks.length; index++) {
            int id = blocks[index] & 0xFF;
            if (addBlocks != null && (index >> 1) < addBlocks.length) {
                int add = (index & 1) == 0 ? addBlocks[index >> 1] & 0x0F : (addBlocks[index >> 1] & 0xF0) >> 4;
                id |= add << 8;
            }
            if (addBlocks2 != null && (index >> 1) < addBlocks2.length) {
                int add = (index & 1) == 0 ? addBlocks2[index >> 1] & 0x0F : (addBlocks2[index >> 1] & 0xF0) >> 4;
                id |= add << 12;
            }

            int data = blockData[index] & 0xFF;
            if (addData != null && index < addData.length) {
                data |= (addData[index] & 0xFF) << 8;
            }
            blockIds[index] = id;
            meta[index] = data;
        }

        // Preserve the WorldEdit paste anchor when present
        int[] offset = readVector(root, "WEOffsetX", "WEOffsetY", "WEOffsetZ");
        int[] origin = readVector(root, "WEOriginX", "WEOriginY", "WEOriginZ");
        return new SchematicData(width, height, length, blockIds, meta, offset, origin);
    }

    /** Read three int tags as an [x, y, z] vector, or null when any is missing. */
    private static int @Nullable [] readVector(CompoundTag root, String x, String y, String z) {
        if (!root.contains(x) || !root.contains(y) || !root.contains(z)) {
            return null;
        }
        return new int[]{root.getInt(x), root.getInt(y), root.getInt(z)};
    }

    private static CompoundTag readRoot(Path path) throws IOException {
        // Raise the NBT array limits while reading, schematics store the whole build
        // in single arrays which vastly exceed the chunk-sized defaults.
        ByteArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_BYTE_ARRAY_LENGTH);
        IntArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_INT_ARRAY_LENGTH);
        LongArrayTag.setMaxDecodeLength(SCHEMATIC_MAX_LONG_ARRAY_LENGTH);
        try (InputStream input = Files.newInputStream(path);
             BufferedInputStream buffered = new BufferedInputStream(input);
             GZIPInputStream gzip = new GZIPInputStream(buffered);
             DataInputStream dataInputStream = new DataInputStream(gzip)) {
            TagWithName<CompoundTag> pair = Tag.decodeNamed(Reader.toJavaReader(dataInputStream), CompoundTag.class);
            return pair.tag();
        } finally {
            ByteArrayTag.resetMaxDecodeLength();
            IntArrayTag.resetMaxDecodeLength();
            LongArrayTag.resetMaxDecodeLength();
        }
    }

    private static SchematicData readSponge(Path path, CompoundTag root, CompoundTag paletteTag, byte[] blockData, ResolutionContext context) throws IOException {
        short width = root.getShort("Width", (short) -1);
        short height = root.getShort("Height", (short) -1);
        short length = root.getShort("Length", (short) -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        long volumeLong = (long) width * (long) height * (long) length;
        if (volumeLong > Integer.MAX_VALUE) {
            throw new IOException("Schematic too large: " + volumeLong + " blocks");
        }
        int volume = (int) volumeLong;

        // BlockData is a varint array with one entry per block (YZX order)
        int[] paletteIndices = decodeVarIntArray(blockData, volume);

        // Determine the Java version the palette identifiers belong to
        int dataVersion = root.getInt("DataVersion", -1);
        Version version;
        if (dataVersion >= FLATTENING_DATA_VERSION) {
            version = JavaDataVersion.getNearestVersion(dataVersion).getVersion();
        } else {
            if (dataVersion >= 0) {
                System.err.println("[warn] " + path.getFileName() + ": DataVersion " + dataVersion + " is pre-1.13, treating palette as 1.13.2 identifiers");
            }
            version = DEFAULT_SPONGE_VERSION;
        }

        // Resolve every palette entry to a 1.7.10 block ID + data value
        int paletteSize = 0;
        for (Map.Entry<String, Tag<?>> entry : paletteTag.getValue().entrySet()) {
            if (entry.getValue() instanceof IntTag indexTag) {
                paletteSize = Math.max(paletteSize, indexTag.getValue() + 1);
            }
        }

        int[] paletteIds = new int[paletteSize];
        int[] paletteMeta = new int[paletteSize];
        List<String> unmapped = new ArrayList<>();
        for (Map.Entry<String, Tag<?>> entry : paletteTag.getValue().entrySet()) {
            if (!(entry.getValue() instanceof IntTag indexTag)) continue;
            int index = indexTag.getValue();
            if (index < 0 || index >= paletteSize) continue;

            BlockResolution resolution = resolvePaletteEntry(entry.getKey(), version, context);
            if (resolution != null) {
                paletteIds[index] = resolution.blockId();
                paletteMeta[index] = resolution.data();
            } else {
                paletteIds[index] = 0;
                paletteMeta[index] = 0;
                unmapped.add(entry.getKey());
            }
        }

        if (!unmapped.isEmpty()) {
            System.err.println("[warn] " + path.getFileName() + ": " + unmapped.size() + " palette entries could not be mapped (converted to air):");
            for (String name : unmapped) {
                System.err.println("[warn]   " + name);
            }
        }

        int[] blockIds = new int[volume];
        int[] meta = new int[volume];
        for (int i = 0; i < volume; i++) {
            int paletteIndex = paletteIndices[i];
            if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                throw new IOException("BlockData references palette index " + paletteIndex + " outside palette size " + paletteSize);
            }
            blockIds[i] = paletteIds[paletteIndex];
            meta[i] = paletteMeta[paletteIndex];
        }

        return new SchematicData(width, height, length, blockIds, meta, readSpongeOffset(root), null);
    }

    /**
     * Read the Sponge paste offset: the "Offset" int array (v2/v3), falling back
     * to the WEOffsetX/Y/Z ints some writers place in Metadata.
     */
    private static int @Nullable [] readSpongeOffset(CompoundTag root) {
        Tag<?> offsetTag = root.get("Offset");
        if (offsetTag instanceof IntArrayTag intArray && intArray.getValue() != null && intArray.getValue().length == 3) {
            return intArray.getValue().clone();
        }
        CompoundTag metadata = root.getCompound("Metadata", null);
        if (metadata != null) {
            return readVector(metadata, "WEOffsetX", "WEOffsetY", "WEOffsetZ");
        }
        return null;
    }

    /**
     * Read a Litematica .litematic file. Every named region is resolved through the
     * same palette pipeline as Sponge schematics and composed into a single schematic
     * covering the enclosing bounding box of all regions.
     */
    private static SchematicData readLitematic(Path path, CompoundTag root, CompoundTag regionsTag, ResolutionContext context) throws IOException {
        // Determine the Java version the palette identifiers belong to
        int dataVersion = root.getInt("MinecraftDataVersion", -1);
        Version version;
        if (dataVersion >= FLATTENING_DATA_VERSION) {
            version = JavaDataVersion.getNearestVersion(dataVersion).getVersion();
        } else {
            if (dataVersion >= 0) {
                System.err.println("[warn] " + path.getFileName() + ": MinecraftDataVersion " + dataVersion + " is pre-1.13, treating palettes as 1.13.2 identifiers");
            }
            version = DEFAULT_SPONGE_VERSION;
        }

        // Collect the regions and compute the enclosing bounding box
        List<LitematicRegion> regions = new ArrayList<>();
        for (Map.Entry<String, Tag<?>> entry : regionsTag.getValue().entrySet()) {
            if (!(entry.getValue() instanceof CompoundTag region)) continue;
            CompoundTag position = region.getCompound("Position");
            CompoundTag size = region.getCompound("Size");
            if (position == null || size == null || !region.contains("BlockStatePalette") || !region.contains("BlockStates")) {
                throw new IOException("Unsupported litematic region '" + entry.getKey() + "' (version " + root.getInt("Version", -1) + ")");
            }

            int sizeX = size.getInt("x", 0);
            int sizeY = size.getInt("y", 0);
            int sizeZ = size.getInt("z", 0);
            if (sizeX == 0 || sizeY == 0 || sizeZ == 0) {
                throw new IOException("Invalid litematic region size for '" + entry.getKey() + "'");
            }

            // Negative size components extend in the negative direction from Position
            int minX = position.getInt("x", 0) + Math.min(sizeX + 1, 0);
            int minY = position.getInt("y", 0) + Math.min(sizeY + 1, 0);
            int minZ = position.getInt("z", 0) + Math.min(sizeZ + 1, 0);
            regions.add(new LitematicRegion(entry.getKey(), region, minX, minY, minZ, Math.abs(sizeX), Math.abs(sizeY), Math.abs(sizeZ)));
        }

        if (regions.isEmpty()) {
            throw new IOException("Litematic contains no readable regions");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LitematicRegion region : regions) {
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.minX() + region.sizeX() - 1);
            maxY = Math.max(maxY, region.minY() + region.sizeY() - 1);
            maxZ = Math.max(maxZ, region.minZ() + region.sizeZ() - 1);
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int length = maxZ - minZ + 1;
        long volumeLong = (long) width * (long) height * (long) length;
        if (width > Short.MAX_VALUE || height > Short.MAX_VALUE || length > Short.MAX_VALUE || volumeLong > Integer.MAX_VALUE) {
            throw new IOException("Litematic too large: " + width + "x" + height + "x" + length);
        }
        int volume = (int) volumeLong;

        int[] blockIds = new int[volume];
        int[] meta = new int[volume];
        List<String> unmapped = new ArrayList<>();

        for (LitematicRegion region : regions) {
            // Resolve the palette (list of {Name, Properties} compounds)
            ListTag<? extends Tag<?>, ?> paletteTag = region.tag().get("BlockStatePalette");
            int paletteSize = paletteTag.size();
            int[] paletteIds = new int[paletteSize];
            int[] paletteMeta = new int[paletteSize];
            int paletteIndex = 0;
            for (Tag<?> paletteEntry : paletteTag) {
                if (!(paletteEntry instanceof CompoundTag blockState)) {
                    throw new IOException("Invalid litematic palette entry in region '" + region.name() + "'");
                }
                Identifier identifier = parseLitematicBlockState(blockState);
                BlockResolution resolution = resolveIdentifier(identifier, version, context);
                if (resolution != null) {
                    paletteIds[paletteIndex] = resolution.blockId();
                    paletteMeta[paletteIndex] = resolution.data();
                } else {
                    unmapped.add(displayBlockState(identifier));
                }
                paletteIndex++;
            }

            // Unpack the bit-packed BlockStates array (entries span across long boundaries)
            long[] blockStates = ((LongArrayTag) region.tag().get("BlockStates")).getValue();
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
            long mask = (1L << bits) - 1;
            long regionVolume = (long) region.sizeX() * (long) region.sizeY() * (long) region.sizeZ();
            long requiredLongs = (regionVolume * bits + 63) / 64;
            if (blockStates == null || blockStates.length < requiredLongs) {
                throw new IOException("Litematic region '" + region.name() + "' has truncated BlockStates ("
                        + (blockStates == null ? 0 : blockStates.length) + " longs, expected " + requiredLongs + ")");
            }

            int offsetX = region.minX() - minX;
            int offsetY = region.minY() - minY;
            int offsetZ = region.minZ() - minZ;
            int regionIndex = 0;
            for (int y = 0; y < region.sizeY(); y++) {
                for (int z = 0; z < region.sizeZ(); z++) {
                    for (int x = 0; x < region.sizeX(); x++) {
                        int palette = extractPackedIndex(blockStates, regionIndex++, bits, mask);
                        if (palette < 0 || palette >= paletteSize) {
                            throw new IOException("Litematic region '" + region.name() + "' references palette index "
                                    + palette + " outside palette size " + paletteSize);
                        }
                        int destination = ((y + offsetY) * length + (z + offsetZ)) * width + (x + offsetX);
                        blockIds[destination] = paletteIds[palette];
                        meta[destination] = paletteMeta[palette];
                    }
                }
            }
        }

        if (!unmapped.isEmpty()) {
            System.err.println("[warn] " + path.getFileName() + ": " + unmapped.size() + " palette entries could not be mapped (converted to air):");
            for (String name : unmapped) {
                System.err.println("[warn]   " + name);
            }
        }

        return new SchematicData((short) width, (short) height, (short) length, blockIds, meta);
    }

    /**
     * Extract a palette index from a Litematica bit-packed long array. Entries are
     * packed at a fixed bit width and may span across long boundaries.
     */
    private static int extractPackedIndex(long[] longs, int index, int bits, long mask) {
        long startOffset = (long) index * bits;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((long) (index + 1) * bits - 1) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);
        if (startArrIndex == endArrIndex) {
            return (int) ((longs[startArrIndex] >>> startBitOffset) & mask);
        }
        return (int) ((longs[startArrIndex] >>> startBitOffset | longs[endArrIndex] << (64 - startBitOffset)) & mask);
    }

    /**
     * Convert a Litematica palette entry ({Name, Properties}) into an Identifier
     * with string state values, matching the Sponge palette parsing.
     */
    private static Identifier parseLitematicBlockState(CompoundTag blockState) throws IOException {
        String name = blockState.getString("Name", null);
        if (name == null) {
            throw new IOException("Litematic palette entry is missing a Name");
        }
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }

        CompoundTag properties = blockState.getCompound("Properties", null);
        if (properties == null || properties.getValue().isEmpty()) {
            return new Identifier(name);
        }

        Map<String, StateValue<?>> states = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<String, Tag<?>> property : properties.getValue().entrySet()) {
            states.put(property.getKey(), new StateValueString(String.valueOf(property.getValue().getBoxedValue())));
        }
        return new Identifier(name, states);
    }

    /** Format an identifier as a blockstate string for warning messages. */
    private static String displayBlockState(Identifier identifier) {
        if (identifier.getStates().isEmpty()) {
            return identifier.getIdentifier();
        }
        StringBuilder builder = new StringBuilder(identifier.getIdentifier()).append('[');
        boolean first = true;
        for (Map.Entry<String, StateValue<?>> state : new java.util.TreeMap<>(identifier.getStates()).entrySet()) {
            if (!first) builder.append(',');
            builder.append(state.getKey()).append('=').append(state.getValue().getBoxed());
            first = false;
        }
        return builder.append(']').toString();
    }

    /** A litematic region normalized to its minimum corner and absolute size. */
    private record LitematicRegion(String name, CompoundTag tag, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    }

    /**
     * Decode a Sponge BlockData varint array into palette indices.
     *
     * @param data          the raw varint bytes.
     * @param expectedCount the expected number of entries (schematic volume).
     * @return an array of palette indices with one entry per block.
     * @throws IOException if the varints are malformed or the count mismatches.
     */
    private static int[] decodeVarIntArray(byte[] data, int expectedCount) throws IOException {
        int[] out = new int[expectedCount];
        int count = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (i >= data.length) {
                    throw new IOException("Truncated varint in BlockData");
                }
                if (shift > 28) {
                    throw new IOException("Malformed varint in BlockData (too long)");
                }
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            if (count >= expectedCount) {
                throw new IOException("BlockData contains more entries than the schematic volume " + expectedCount);
            }
            out[count++] = value;
        }
        if (count != expectedCount) {
            throw new IOException("BlockData contains " + count + " entries but the schematic volume is " + expectedCount);
        }
        return out;
    }

    /**
     * Resolve a Sponge palette entry (e.g. "minecraft:oak_stairs[facing=east,half=bottom]")
     * to a legacy block ID and data value using the full Chunker pipeline:
     * modern identifier -> Chunker block -> 1.7.10 identifier (+ simple mappings) -> numeric ID.
     *
     * @param state   the palette blockstate string.
     * @param version the Java version of the palette identifiers.
     * @param context the shared resolution context.
     * @return the resolved block, or null when nothing could map it.
     */
    @Nullable
    private static BlockResolution resolvePaletteEntry(String state, Version version, ResolutionContext context) {
        return resolveIdentifier(parseBlockState(state), version, context);
    }

    /**
     * Resolve a parsed blockstate identifier to a legacy block ID and data value.
     */
    @Nullable
    private static BlockResolution resolveIdentifier(Identifier parsed, Version version, ResolutionContext context) {
        // 1) Full vanilla pipeline (handles flattened names + block state properties)
        Optional<ChunkerBlockIdentifier> chunker = context.modernResolver(version).to(parsed);
        if (chunker.isPresent()) {
            Optional<Identifier> legacy = context.legacyWriterResolver.from(chunker.get());
            if (legacy.isPresent()) {
                Identifier out = legacy.get();
                // When legacy simple mappings are inactive the resolver won't have applied
                // the mappings file, apply it here so behaviour matches the classic path.
                if (context.mappingsFile != null && !context.legacySimpleMappingsActive) {
                    OptionalInt data = out.getDataValue();
                    Identifier mappingInput = Identifier.fromData(out.getIdentifier(), data);
                    Optional<Identifier> mapped = context.mappingsFile.convertBlock(mappingInput);
                    if (mapped.isPresent()) {
                        Identifier converted = mapped.get();
                        Integer id = resolveConvertedBlockId(converted, TARGET_BLOCK_ID_RESOLVER);
                        if (id != null) {
                            return new BlockResolution(id, resolveMappedMeta(context.mappingsFile, mappingInput, converted, data.orElse(0)));
                        }
                    }
                }

                Integer id = resolveTargetBlockId(out.getIdentifier());
                if (id != null) {
                    BlockResolution resolution = new BlockResolution(id, out.getDataValue().orElse(0));
                    // Numeric rules only fire when no name rule already rewrote the
                    // block inside the resolvers, otherwise two single-step rules
                    // could chain (name rule output hit by an unrelated numeric rule).
                    // A reader-applied rule is marked by a PreservedIdentifier, a
                    // writer-applied rule is detected by comparing against a
                    // mapping-free resolver.
                    if (context.legacySimpleMappingsActive) {
                        boolean nameRuleApplied = chunker.get().getPreservedIdentifier() != null;
                        if (!nameRuleApplied) {
                            Optional<Identifier> plain = context.plainWriterResolver.from(chunker.get());
                            nameRuleApplied = plain.isEmpty() || !plain.get().getIdentifier().equals(out.getIdentifier());
                        }
                        if (nameRuleApplied) {
                            return resolution;
                        }
                    }
                    return applyNumericRules(resolution, context);
                }
            }
        }

        // 2) Unknown to vanilla (e.g. modded blocks), try the mappings file on the raw identifier
        if (context.mappingsFile != null) {
            Optional<Identifier> mapped = context.mappingsFile.convertBlock(new Identifier(parsed.getIdentifier()));
            if (mapped.isPresent()) {
                Integer id = resolveConvertedBlockId(mapped.get(), TARGET_BLOCK_ID_RESOLVER);
                if (id != null) {
                    return new BlockResolution(id, mapped.get().getDataValue().orElse(0));
                }
            }
        }

        // 3) Direct lookup, the identifier may exist in level.dat under the same name
        Integer direct = resolveTargetBlockId(parsed.getIdentifier());
        if (direct != null) {
            return applyNumericRules(new BlockResolution(direct, 0), context);
        }
        return null;
    }

    /**
     * Apply numeric ID rules (e.g. "52 -> minecraft:air" or "1:1 -> 4:0") to a
     * resolved block. Name-based rules take precedence, numeric rules only fire
     * when no name rule already rewrote the block.
     */
    private static BlockResolution applyNumericRules(BlockResolution resolution, ResolutionContext context) {
        if (context.mappingsFile == null) {
            return resolution;
        }
        Identifier numericInput = Identifier.fromData(String.valueOf(resolution.blockId()), OptionalInt.of(resolution.data()));
        Optional<Identifier> mapped = context.mappingsFile.convertBlock(numericInput);
        if (mapped.isEmpty()) {
            return resolution;
        }
        Integer id = resolveConvertedBlockId(mapped.get(), TARGET_BLOCK_ID_RESOLVER);
        if (id == null) {
            return resolution;
        }
        return new BlockResolution(id, resolveMappedMeta(context.mappingsFile, numericInput, mapped.get(), resolution.data()));
    }

    /**
     * Determine the output meta for a mapped block. Named state lists in the simple
     * mapping format (e.g. "-> SLAB_HALF") are parsed as empty lists which zero the
     * data value; the world resolvers restore the input states afterwards, so a
     * constant-zero output with a non-zero input keeps the input meta. Explicit
     * outputs (e.g. "[data=5]" or data-constrained rules) are honoured as written.
     */
    private static int resolveMappedMeta(MappingsFile mappingsFile, Identifier lookup, Identifier converted, int currentMeta) {
        OptionalInt dataOut = converted.getDataValue();
        if (dataOut.isEmpty()) {
            return currentMeta;
        }
        int value = dataOut.getAsInt();
        if (value == 0 && currentMeta != 0 && lookup.getDataValue().isPresent()) {
            // Probe with a different data value, if the rule still matches and still
            // outputs zero, a state list consumed the data and the input meta wins.
            // Only probe when the original lookup carried data, otherwise the probe
            // could match data-constrained rules the real lookup never could.
            int probe = currentMeta == 15 ? 14 : 15;
            Optional<Identifier> probed = mappingsFile.convertBlock(Identifier.fromData(lookup.getIdentifier(), OptionalInt.of(probe)));
            if (probed.isPresent() && probed.get().getIdentifier().equals(converted.getIdentifier())) {
                OptionalInt probedData = probed.get().getDataValue();
                if (probedData.isPresent() && probedData.getAsInt() == 0) {
                    return currentMeta;
                }
            }
        }
        return value;
    }

    /**
     * Parse a blockstate string such as "minecraft:oak_stairs[facing=east,half=bottom]"
     * into an Identifier with string state values.
     */
    private static Identifier parseBlockState(String input) {
        String trimmed = input.trim();
        int bracket = trimmed.indexOf('[');
        String name = bracket == -1 ? trimmed : trimmed.substring(0, bracket);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        if (bracket == -1) {
            return new Identifier(name);
        }

        int end = trimmed.lastIndexOf(']');
        if (end <= bracket) {
            return new Identifier(name);
        }

        Map<String, StateValue<?>> states = new Object2ObjectOpenHashMap<>();
        String stateString = trimmed.substring(bracket + 1, end);
        for (String pair : stateString.split(",")) {
            int equals = pair.indexOf('=');
            if (equals == -1) continue;
            states.put(pair.substring(0, equals).trim(), new StateValueString(pair.substring(equals + 1).trim()));
        }
        return new Identifier(name, states);
    }

    private static SchematicData applyMappings(SchematicData data, MappingsFile mappingsFile, boolean legacySimpleMappings) {
        int[] ids = Arrays.copyOf(data.getBlockIds(), data.getBlockIds().length);
        int[] meta = Arrays.copyOf(data.getBlockData(), data.getBlockData().length);

        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            final int currentMeta = meta[i];
            OptionalInt metaValue = legacySimpleMappings ? OptionalInt.of(currentMeta) : OptionalInt.empty();

            // Try a rule keyed by the block identifier first (e.g. minecraft:mob_spawner)
            String identifier = resolveIdentifierFromMappings(ids[i]);
            Identifier lookup = null;
            Optional<Identifier> mapped = Optional.empty();
            if (identifier != null) {
                lookup = Identifier.fromData(identifier, metaValue);
                mapped = mappingsFile.convertBlock(lookup);
            }

            // Fall back to numeric ID rules (e.g. "52 -> minecraft:air" or "112:3 -> ...")
            if (mapped.isEmpty()) {
                lookup = Identifier.fromData(String.valueOf(ids[i]), metaValue);
                mapped = mappingsFile.convertBlock(lookup);
            }

            final Identifier lookupUsed = lookup;
            mapped.ifPresent(converted -> {
                Integer newId = resolveConvertedBlockId(converted, LEGACY_BLOCK_RESOLVER);
                if (newId != null) {
                    ids[index] = newId;
                    meta[index] = resolveMappedMeta(mappingsFile, lookupUsed, converted, currentMeta);
                }
            });
        }

        return data.withBlocks(ids, meta);
    }

    /**
     * Write the schematic to classic WorldEdit schematic format.
     */
    public static void writeClassic(Path output, SchematicData schematic, boolean allowNeids) throws IOException {
        int volume = schematic.getBlockIds().length;
        byte[] blocks = new byte[volume];
        byte[] data = new byte[volume];
        byte[] addBlocks = null;
        byte[] addBlocks2 = null;
        byte[] addData = null;
        int truncatedIds = 0;
        int truncatedMeta = 0;

        for (int i = 0; i < volume; i++) {
            int id = schematic.getBlockIds()[i];
            int meta = schematic.getBlockData()[i];
            if ((!allowNeids && id > 4095) || id > 65535) {
                truncatedIds++;
            }
            if (meta > 65535) {
                truncatedMeta++;
            }
            blocks[i] = (byte) (id & 0xFF);
            if (id > 255) {
                if (addBlocks == null) addBlocks = new byte[(volume >> 1) + 1];
                addBlocks[i >> 1] = (byte) (((i & 1) == 0) ? addBlocks[i >> 1] & 0xF0 | (id >> 8) & 0xF
                        : addBlocks[i >> 1] & 0xF | ((id >> 8) & 0xF) << 4);
            }
            if (allowNeids && id > 4095) {
                if (addBlocks2 == null) addBlocks2 = new byte[(volume >> 1) + 1];
                addBlocks2[i >> 1] = (byte) (((i & 1) == 0) ? addBlocks2[i >> 1] & 0xF0 | (id >> 12) & 0xF
                        : addBlocks2[i >> 1] & 0xF | ((id >> 12) & 0xF) << 4);
            }

            data[i] = (byte) (meta & 0xFF);
            if (meta > 15) {
                if (addData == null) addData = new byte[volume];
                addData[i] = (byte) ((meta >> 8) & 0xFF);
            }
        }

        CompoundTag root = new CompoundTag();
        root.put("Width", new ShortTag(schematic.getWidth()));
        root.put("Height", new ShortTag(schematic.getHeight()));
        root.put("Length", new ShortTag(schematic.getLength()));
        root.put("Materials", new StringTag("Alpha"));
        root.put("Blocks", new ByteArrayTag(blocks));
        root.put("Data", new ByteArrayTag(data));
        root.put("Entities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));
        root.put("TileEntities", new ListTag<>(TagType.COMPOUND, Arrays.asList()));

        if (addBlocks != null) {
            root.put("AddBlocks", new ByteArrayTag(addBlocks));
        }

        if (addBlocks2 != null) {
            root.put("AddBlocks2", new ByteArrayTag(addBlocks2));
        }

        if (addData != null) {
            root.put("AddData", new ByteArrayTag(addData));
        }

        // Preserve the WorldEdit paste anchor so //paste positions match the source
        if (schematic.getOffset() != null) {
            root.put("WEOffsetX", new IntTag(schematic.getOffset()[0]));
            root.put("WEOffsetY", new IntTag(schematic.getOffset()[1]));
            root.put("WEOffsetZ", new IntTag(schematic.getOffset()[2]));
        }
        if (schematic.getOrigin() != null) {
            root.put("WEOriginX", new IntTag(schematic.getOrigin()[0]));
            root.put("WEOriginY", new IntTag(schematic.getOrigin()[1]));
            root.put("WEOriginZ", new IntTag(schematic.getOrigin()[2]));
        }

        if (truncatedIds > 0) {
            System.err.println("[warn] " + output.getFileName() + ": " + truncatedIds
                    + " blocks exceed the writable ID range" + (allowNeids ? "" : " (enable NEIDs?)") + " and were truncated");
        }
        if (truncatedMeta > 0) {
            System.err.println("[warn] " + output.getFileName() + ": " + truncatedMeta
                    + " blocks exceed the writable data range and were truncated");
        }

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writeWithRootName(output, root);
    }

    private static void writeWithRootName(Path output, CompoundTag root) throws IOException {
        try (var fileOutputStream = Files.newOutputStream(output);
             var gzipOutputStream = new GZIPOutputStream(fileOutputStream);
             var bufferedOutputStream = new BufferedOutputStream(gzipOutputStream);
             var writerStream = new DataOutputStream(bufferedOutputStream)) {
            Tag.encodeNamed(Writer.toJavaWriter(writerStream), SCHEMATIC_ROOT_NAME, root);
            writerStream.flush();
        }
    }

    private static Integer resolveLegacyBlockId(String identifier) {
        Integer levelId = LevelConvertMappings.getLegacyId(identifier);
        if (levelId != null) {
            return levelId;
        }
        return LEGACY_BLOCK_RESOLVER.from(identifier).orElse(null);
    }

    /**
     * Resolve a mapping output to a numeric block ID. The meta:no_level_convert
     * marker produced by '=' prefixed rules is resolver metadata; the world path
     * strips it before converting the output through level.dat, so the schematic
     * path must resolve through level.dat as well (verified against
     * JavaLegacyBlockIdentifierResolver end-to-end behaviour).
     */
    private static Integer resolveConvertedBlockId(Identifier converted, JavaLegacyBlockIDResolver idResolver) {
        String identifier = converted.getIdentifier();
        Integer levelId = LevelConvertMappings.getLegacyId(identifier);
        if (levelId != null) {
            return levelId;
        }
        return idResolver.from(identifier).orElse(null);
    }

    /**
     * Resolve an identifier to a numeric block ID for the written 1.7.10 schematic,
     * preferring level.dat mappings and falling back to vanilla 1.7.10 IDs.
     */
    private static Integer resolveTargetBlockId(String identifier) {
        Integer levelId = LevelConvertMappings.getLegacyId(identifier);
        if (levelId != null) {
            return levelId;
        }
        return TARGET_BLOCK_ID_RESOLVER.from(identifier).orElse(null);
    }

    private static String resolveIdentifierFromMappings(int id) {
        String identifier = LevelConvertMappings.getLegacyIdentifier(id);
        if (identifier != null) {
            return identifier;
        }
        return LEGACY_BLOCK_RESOLVER.to(id).orElse(null);
    }

    /** Result of reading a schematic, tracking whether mappings were applied at palette level. */
    private record ReadResult(SchematicData data, boolean paletteResolved) {
    }

    /** A palette entry resolved to a legacy block ID and data value. */
    private record BlockResolution(int blockId, int data) {
    }

    /**
     * Shared state for a conversion run: the mappings file and the resolvers used
     * to translate identifiers between versions.
     */
    private static final class ResolutionContext {
        @Nullable
        final MappingsFile mappingsFile;
        final boolean legacySimpleMappings;
        final boolean legacySimpleMappingsActive;
        final WorldConverter converter;
        final JavaLegacyBlockIdentifierResolver legacyWriterResolver;
        /** Resolver without any mappings, used to detect whether a name rule fired. */
        final JavaLegacyBlockIdentifierResolver plainWriterResolver;
        final Map<Version, JavaBlockIdentifierResolver> modernResolvers = new HashMap<>();

        ResolutionContext(@Nullable MappingsFile mappingsFile, boolean legacySimpleMappings) {
            this.mappingsFile = mappingsFile;
            this.legacySimpleMappings = legacySimpleMappings;
            this.legacySimpleMappingsActive = legacySimpleMappings && mappingsFile != null;

            converter = new WorldConverter(UUID.randomUUID());
            if (mappingsFile != null) {
                converter.setBlockMappings(new MappingsFileResolvers(mappingsFile));
            }
            converter.setLegacySimpleMappings(legacySimpleMappingsActive);
            legacyWriterResolver = new JavaLegacyBlockIdentifierResolver(converter, TARGET_VERSION, false, false);
            plainWriterResolver = legacySimpleMappingsActive
                    ? new JavaLegacyBlockIdentifierResolver(new WorldConverter(UUID.randomUUID()), TARGET_VERSION, false, false)
                    : legacyWriterResolver;
        }

        JavaBlockIdentifierResolver modernResolver(Version version) {
            return modernResolvers.computeIfAbsent(version, v -> new JavaBlockIdentifierResolver(converter, v, true, false));
        }
    }
}
