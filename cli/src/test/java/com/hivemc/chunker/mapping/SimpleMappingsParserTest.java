package com.hivemc.chunker.mapping;

import com.hivemc.chunker.mapping.identifier.Identifier;
import com.hivemc.chunker.mapping.identifier.states.StateValueInt;
import com.hivemc.chunker.mapping.identifier.states.StateValueString;
import com.hivemc.chunker.mapping.identifier.states.StateValueBoolean;
import com.hivemc.chunker.mapping.parser.SimpleMappingsParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link SimpleMappingsParser} utility.
 */
public class SimpleMappingsParserTest {
    @Test
    public void testParseSimpleFile() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(), "minecraft:wool -> minecraft:stone\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:wool", Collections.emptyMap());
        assertEquals(new Identifier("minecraft:stone", Collections.emptyMap()), mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testParseStates() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(), "minecraft:stairs[facing=east] -> custom:stairs[data=3]\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:stairs", Map.of("facing", new StateValueString("EAST")));
        Identifier expected = new Identifier("custom:stairs", Map.of("facing", new StateValueString("EAST"), "data", new StateValueInt(3)));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testParseNumericId() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(), "minecraft:stairs[facing=east] -> 112:3\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:stairs", Map.of("facing", new StateValueString("EAST")));
        Identifier expected = new Identifier("112", Map.of("facing", new StateValueString("EAST"), "data", new StateValueInt(3)));
        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testExtraStates() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(), "minecraft:stripped_spruce_log[axis=Y] -> 3006:0\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:stripped_spruce_log", Map.of(
                "axis", new StateValueString("Y"),
                "waterlogged", StateValueBoolean.FALSE
        ));
        Identifier expected = new Identifier("3006", Map.of(
                "axis", new StateValueString("Y"),
                "waterlogged", StateValueBoolean.FALSE,
                "data", new StateValueInt(0)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testTrapdoorWithPoweredState() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_trapdoor[facing=NORTH,half=TOP,open=FALSE] -> 3006:2\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:acacia_trapdoor", Map.of(
                "facing", new StateValueString("NORTH"),
                "half", new StateValueString("TOP"),
                "open", StateValueBoolean.FALSE,
                "powered", StateValueBoolean.FALSE,
                "waterlogged", StateValueBoolean.FALSE
        ));
        Identifier expected = new Identifier("3006", Map.of(
                "facing", new StateValueString("NORTH"),
                "half", new StateValueString("TOP"),
                "open", StateValueBoolean.FALSE,
                "powered", StateValueBoolean.FALSE,
                "waterlogged", StateValueBoolean.FALSE,
                "data", new StateValueInt(2)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testCaseInsensitiveStateKeys() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_trapdoor[facing=north,half=top,open=false] -> 3006:2\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:acacia_trapdoor", Map.of(
                "FACING", new StateValueString("north"),
                "Half", new StateValueString("top"),
                "Open", StateValueBoolean.FALSE,
                "powered", StateValueBoolean.FALSE,
                "waterlogged", StateValueBoolean.FALSE
        ));
        Identifier expected = new Identifier("3006", Map.of(
                "FACING", new StateValueString("north"),
                "Half", new StateValueString("top"),
                "Open", StateValueBoolean.FALSE,
                "powered", StateValueBoolean.FALSE,
                "waterlogged", StateValueBoolean.FALSE,
                "data", new StateValueInt(2)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testBooleanStringEquivalence() throws IOException {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_trapdoor[open=false] -> 3006:0\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:acacia_trapdoor", Map.of(
                "open", new StateValueString("false"),
                "powered", StateValueBoolean.FALSE
        ));
        Identifier expected = new Identifier("3006", Map.of(
                "open", new StateValueString("false"),
                "powered", StateValueBoolean.FALSE,
                "data", new StateValueInt(0)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testParseWithStateList() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_fence_gate -> etfuturum:acacia_fence_gate -> FENCE_GATE\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        String json = mappingsFile.toJsonString();
        assertTrue(json.contains("\"state_list\": \"FENCE_GATE\""));
    }

    @Test
    public void testFenceGateLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_fence_gate -> 3000 -> FENCE_GATE\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:acacia_fence_gate", Map.of(
                "facing", new StateValueString("WEST"),
                "open", StateValueBoolean.TRUE,
                "powered", StateValueBoolean.FALSE
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3000", Map.of(
                "data", new StateValueInt(5)
        )), out);
    }

    @Test
    public void testTrapdoorLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:acacia_trapdoor -> 3006 -> TRAPDOOR\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:acacia_trapdoor", Map.of(
                "facing", new StateValueString("EAST"),
                "half", new StateValueString("TOP"),
                "open", StateValueBoolean.TRUE
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3006", Map.of(
                "data", new StateValueInt(15)
        )), out);
    }

    @Test
    public void testDoorLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:oak_door -> 3001 -> DOOR\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:oak_door", Map.of(
                "hinge", new StateValueString("RIGHT"),
                "half", new StateValueString("BOTTOM"),
                "powered", StateValueBoolean.FALSE,
                "facing", new StateValueString("EAST"),
                "open", StateValueBoolean.FALSE
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3001", Map.of(
                "data", new StateValueInt(0)
        )), out);
    }

    @Test
    public void testDoor2LegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:birch_door -> 3002 -> DOOR_2\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:birch_door", Map.of(
                "hinge", new StateValueString("RIGHT"),
                "half", new StateValueString("BOTTOM"),
                "powered", StateValueBoolean.FALSE,
                "facing", new StateValueString("EAST"),
                "open", StateValueBoolean.FALSE
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3002", Map.of(
                "data", new StateValueInt(0)
        )), out);
    }

    @Test
    public void testSlabHalfLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:oak_slab -> 3003 -> SLAB_HALF\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:oak_slab", Map.of(
                "type", new StateValueString("TOP")
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3003", Map.of(
                "data", new StateValueInt(8)
        )), out);
    }

    @Test
    public void testButtonLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:stone_button -> 3004 -> BUTTON\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());
        Identifier input = new Identifier("minecraft:stone_button", Map.of(
                "face", new StateValueString("WALL"),
                "powered", StateValueBoolean.TRUE,
                "facing", new StateValueString("EAST")
        ));
        Identifier out = mappingsFile.convertBlock(input).orElse(null);
        assertEquals(new Identifier("3004", Map.of(
                "data", new StateValueInt(9)
        )), out);
    }

    @Test
    public void testWarpedDoorLegacyData() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:warped_door -> netherlicious:doorwarped -> DOOR\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());

        Object[][] cases = {
                {"EAST", "LOWER", false, "LEFT", 0},
                {"SOUTH", "LOWER", false, "LEFT", 1},
                {"WEST", "LOWER", false, "LEFT", 2},
                {"NORTH", "LOWER", false, "LEFT", 3},
                {"EAST", "LOWER", true, "LEFT", 4},
                {"SOUTH", "LOWER", true, "LEFT", 5},
                {"WEST", "LOWER", true, "LEFT", 6},
                {"NORTH", "LOWER", true, "LEFT", 7},
                {"EAST", "LOWER", false, "RIGHT", 0},
                {"SOUTH", "LOWER", false, "RIGHT", 1},
        };

        for (Object[] c : cases) {
            Identifier input = new Identifier("minecraft:warped_door", Map.of(
                    "facing", new StateValueString((String) c[0]),
                    "half", new StateValueString((String) c[1]),
                    "open", ((Boolean) c[2]) ? StateValueBoolean.TRUE : StateValueBoolean.FALSE,
                    "hinge", new StateValueString((String) c[3])
            ));
            Identifier expected = new Identifier("netherlicious:doorwarped", Map.of(
                    "data", new StateValueInt((Integer) c[4])
            ));
            assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
        }
    }

    @Test
    public void testWallSignMetadataPreserved() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:spruce_wall_sign -> etfuturum:wall_sign_spruce -> FACING_HORIZONTAL_UNUSUAL\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());

        Identifier input = new Identifier("minecraft:spruce_wall_sign", Map.of(
                "facing", new StateValueString("east")
        ));

        Identifier expected = new Identifier("etfuturum:wall_sign_spruce", Map.of(
                "data", new StateValueInt(5)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }

    @Test
    public void testChainAxisMetadataPreserved() throws Exception {
        File temp = File.createTempFile("simple", ".txt");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(),
                "minecraft:chain -> etfuturum:chain -> AXIS\n");

        MappingsFile mappingsFile = SimpleMappingsParser.parse(temp.toPath());

        Identifier input = new Identifier("minecraft:chain", Map.of(
                "axis", new StateValueString("x")
        ));

        Identifier expected = new Identifier("etfuturum:chain", Map.of(
                "data", new StateValueInt(4)
        ));

        assertEquals(expected, mappingsFile.convertBlock(input).orElse(null));
    }
}
