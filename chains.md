# Chains conversion notes

- Simple block mappings that use the `AXIS` state list rely on Chunker's legacy state metadata resolver. When `minecraft:chain -> etfuturum:chain -> AXIS` is present, the `axis` state from the source block is converted into the correct legacy data value (Y=0, X=4, Z=8) before level.dat IDs are applied.
- If converted chains are disappearing in 1.7.10 outputs, the usual cause is a missing legacy ID mapping for `etfuturum:chain` in the provided `level.dat` (the `plug.dat` file passed via `--levelConvert`). Ensure that file contains the mod's numeric ID entry so level conversion can map the namespaced identifier instead of leaving it unmapped.
- A unit test (`testChainAxisMetadataPreserved`) was added to guard the axis→metadata mapping so future changes don't regress this behaviour.
