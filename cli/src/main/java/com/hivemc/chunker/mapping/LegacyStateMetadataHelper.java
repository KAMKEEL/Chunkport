package com.hivemc.chunker.mapping;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.resolver.identifier.state.StateMappingGroup;
import com.hivemc.chunker.conversion.encoding.base.resolver.identifier.state.VersionedStateMappingGroup;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockState;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockStateValue;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.VanillaBlockStates;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyStateGroups;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.JavaStateGroups;
import com.hivemc.chunker.mapping.identifier.states.StateValue;
import com.hivemc.chunker.mapping.identifier.states.StateValueBoolean;
import com.hivemc.chunker.mapping.identifier.states.StateValueInt;
import com.hivemc.chunker.mapping.identifier.states.StateValueString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper used to resolve legacy metadata values by delegating to the existing
 * {@link JavaLegacyStateGroups} and {@link VanillaBlockStates} definitions.
 */
public final class LegacyStateMetadataHelper {
    private static final Map<String, StateMappingGroup> LEGACY_LOOKUP = new HashMap<>();
    private static final Map<String, StateMappingGroup> JAVA_LOOKUP = new HashMap<>();

    static {
        try {
            // Map legacy group names
            for (Field field : JavaLegacyStateGroups.class.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (StateMappingGroup.class.isAssignableFrom(type)) {
                    LEGACY_LOOKUP.put(field.getName(), (StateMappingGroup) field.get(null));
                } else if (VersionedStateMappingGroup.class.isAssignableFrom(type)) {
                    LEGACY_LOOKUP.put(field.getName(), ((VersionedStateMappingGroup) field.get(null)).getStateMappingGroup(Version.LATEST));
                }
            }

            // Map modern group names
            for (Field field : JavaStateGroups.class.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (StateMappingGroup.class.isAssignableFrom(type)) {
                    JAVA_LOOKUP.put(field.getName(), (StateMappingGroup) field.get(null));
                } else if (VersionedStateMappingGroup.class.isAssignableFrom(type)) {
                    JAVA_LOOKUP.put(field.getName(), ((VersionedStateMappingGroup) field.get(null)).getStateMappingGroup(Version.LATEST));
                }
            }

        } catch (IllegalAccessException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private LegacyStateMetadataHelper() {
    }

    /**
     * Resolve the legacy metadata value for a specific Java legacy state group
     * using the supplied states.
     *
     * @param groupName the name of the legacy state group, e.g. "FENCE_GATE".
     * @param states    map of state names to values (case-insensitive keys).
     * @return the computed metadata value or {@code null} if it could not be
     * resolved.
     */
    public static Integer resolve(String groupName, Map<String, StateValue<?>> states) {
        StateMappingGroup legacy = LEGACY_LOOKUP.get(groupName);
        if (legacy == null) return null;

        // Normalise and unbox values
        Map<String, Object> boxed = new Object2ObjectOpenHashMap<>(states.size());
        for (Map.Entry<String, StateValue<?>> e : states.entrySet()) {
            boxed.put(e.getKey().toLowerCase(), e.getValue().getBoxed());
        }

        Map<BlockState<?>, BlockStateValue> modern = new Object2ObjectOpenHashMap<>();
        StateMappingGroup javaGroup = JAVA_LOOKUP.get(groupName);
        if (javaGroup != null) {
            javaGroup.applyInput((name, def) -> {
                Object val = boxed.get(name.toLowerCase());
                if (val instanceof String str) {
                    val = str.toLowerCase();
                } else if (val instanceof Boolean b) {
                    val = b.toString();
                }
                return val;
            }, modern);
        }

        Map<String, Object> outputs = new Object2ObjectOpenHashMap<>();
        if (javaGroup != null) {
            legacy.applyOutput((bs, def) -> {
                BlockStateValue val = modern.get(bs);
                if (val == null) {
                    val = bs.getDefault();
                }
                return val;
            }, outputs);
        } else {
            legacy.applyOutput((state, useDefault) -> {
                StateValue<?> raw = states.get(state.getName().toLowerCase());
                BlockStateValue val = parse(state, raw);
                if (val == null && useDefault) return state.getDefault();
                return val;
            }, outputs);
        }

        Object data = outputs.get("data");
        return data instanceof Integer ? (Integer) data : null;
    }

    private static BlockStateValue parse(BlockState<?> blockState, StateValue<?> value) {
        Class<?> type = blockState.getValues()[0].getClass();
        if (Enum.class.isAssignableFrom(type)) {
            String strValue;
            if (value instanceof StateValueBoolean b) {
                strValue = b.getValue() ? "TRUE" : "FALSE";
            } else if (value instanceof StateValueInt i) {
                strValue = String.valueOf(i.getValue());
            } else if (value instanceof StateValueString s) {
                strValue = s.getValue();
            } else {
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Enum<?> e = Enum.valueOf((Class<Enum>) type, strValue.toUpperCase());
                return (BlockStateValue) e;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        return null;
    }
}
