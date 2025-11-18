# Signs conversion notes

- Wall sign mappings such as `minecraft:spruce_wall_sign -> etfuturum:wall_sign_spruce -> FACING_HORIZONTAL_UNUSUAL` use the legacy state resolver to translate the `facing` property into the correct 1.7.10 metadata (N=2, S=3, W=4, E=5). Standing sign mappings should continue to use `ROTATION` for their directional data.
- If wall signs land with metadata 0 after conversion, double-check that the simple mappings file includes the appropriate `FACING_HORIZONTAL_UNUSUAL` state list and that the source blocks keep their `facing` state through earlier processing. Missing state lists or stripped states will force the resolver to fall back to the default value (0).
- A unit test (`testWallSignMetadataPreserved`) now covers the wall-sign path to ensure the simple mapping pipeline preserves orientation metadata.
