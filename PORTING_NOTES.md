# Porting notes: Immersive Portals for Minecraft 1.21.4

Branch `port/1.21.4`, created from `origin/1.21.3` (commit `6831a3a7`).

## 1. Starting point

The task was described as a 1.21.3 -> 1.21.4 port. The repository does not contain a working 1.21.3 version:

| Branch | Minecraft | State |
|---|---|---|
| `origin/1.21` | 1.21.1 | The last release (v6.0.6). |
| `origin/1.21.3` | 1.21.3 | 6 commits named "WIP update to 1.21.3". Not finished. |

Compiling `origin/1.21.3` untouched (JDK 21, Gradle 8.10.2, Loom 1.8) gives **256 compile errors in 74 files**.
The same sources against 1.21.4 give 260 errors.
The upstream work-in-progress had only touched `gradle.properties` and a part of the rendering code.

So this port is in fact **1.21.1 -> 1.21.4**, starting from the upstream WIP.
Where the WIP had dropped or stubbed something, it is restored here (see section 6).

## 2. Dependencies

| Dependency | 1.21.3 WIP branch | This port |
|---|---|---|
| Minecraft | 1.21.3 | **1.21.4** |
| Mappings | Mojang + Parchment `parchment-1.21:2024.07.28` | Mojang + Parchment `parchment-1.21.4:2025.03.23` |
| Fabric Loader | 0.16.9 | **0.16.14** |
| Fabric API | 0.110.0+1.21.3 | **0.119.4+1.21.4** |
| Fabric Loom | 1.8-SNAPSHOT | **1.10-SNAPSHOT** (resolved to 1.10.5) |
| Gradle wrapper | 8.10.2 | **8.12.1** (Loom 1.9+ needs 8.11, Loom 1.10 needs 8.12) |
| Java | 21 to build and run (bytecode target stays 17, as upstream) | unchanged |
| Cloth Config | 16.0.141 | **17.0.144** |
| Mod Menu | 12.0.0-beta.1 | **13.0.4** |
| Sodium (optional compat) | mc1.21.3-0.6.0 | **mc1.21.4-0.6.13** |
| Iris (optional compat) | 1.8.0+1.21.3 (disabled in dev runs) | **1.8.8+1.21.4** (enabled in dev runs again) |
| DimLib (bundled) | `com.github.iPortalTeam:DimLib:1.21.3-SNAPSHOT` from JitPack | **`qouteall.dimlib:DimLib:1.1.0+mc1.21.4` from mavenLocal** |
| `yarn_mappings` property | 1.21.3+build.2 | 1.21.4+build.8 (the property is not used by the build) |

Loom 1.10 is used instead of 1.9 because it has the production run task (`ClientProductionRunTask`).
That task is used to test the remapped jar, see section 8.

### DimLib

DimLib is a required dependency that is nested in the mod jar. Its upstream stops at 1.21.3
and its `fabric.mod.json` only accepts Minecraft 1.21.3. It had to be ported too:

* Repository `https://github.com/iPortalTeam/DimLib`, branch `1.21.3`, local branch `port/1.21.4`.
* Only version changes were needed (Minecraft 1.21.4, Loader 0.16.14, Fabric API 0.119.4+1.21.4,
  MidnightLib 1.7.3+1.21.4-fabric, Mod Menu 13.0.4, Loom 1.9, Gradle 8.12.1, `"minecraft": ["1.21.4"]`).
  No Java source change. It compiles and its mixins apply on 1.21.4 (covered by the tests of section 8,
  including adding dimensions while a player is online).
* The change is saved as `misc/dimlib-1.21.4.patch` in this repository. It applies cleanly to upstream DimLib `1.21.3`.
* It is installed with `gradlew publishToMavenLocal`. `build.gradle` of this mod already had `mavenLocal()`.

`fabric.mod.json` of this mod: `minecraft` is `1.21.4`, `fabric-api` is `>=0.119.4`,
and the `breaks` entries are now `sodium` other than 0.6.13 and `iris` other than 1.8.8
(the compat mixins target internal classes of these mods, as before).

## 3. Minecraft API changes that were encountered

Most of these happened in 1.21.2/1.21.3. The ones marked **(1.21.4)** are new in 1.21.4.

### Common

| Change | Handling |
|---|---|
| `Direction.getNormal()` -> `getUnitVec3i()` | Renamed (50 call sites). |
| `Direction.getNearest(double, double, double)` -> `getApproximateNearest` | Renamed. |
| `Direction.fromDelta(int, int, int)` removed | `Direction.getNearest(x, y, z, null)`. The inputs are always axis vectors, for which the result is the same. |
| `Level.getProfiler()` / `Minecraft.getProfiler()` / `MinecraftServer.getProfiler()` removed | `Profiler.get()` (37 call sites). |
| `RegistryAccess.registryOrThrow` -> `lookupOrThrow`, `Registry.get` now returns `Optional<Holder.Reference>`, `getHolderOrThrow` -> `getOrThrow`, `Registry.asLookup()` removed | `lookupOrThrow`, `getValue`, `getOrThrow`. `Registry` is itself a lookup now. |
| `LevelHeightAccessor`: `getMinBuildHeight` -> `getMinY`, `getMaxBuildHeight` (exclusive) -> `getMaxY` (inclusive), `getMinSection`/`getMaxSection` -> `getMinSectionY`/`getMaxSectionY` (inclusive) | Adjusted with `+ 1` or `<=` where the old value was exclusive. `ServerPlayerGameMode.handleBlockBreakAction` now takes the inclusive max Y. |
| `EntityType.create(Level)` -> `create(Level, EntitySpawnReason)` | A reason is passed: `COMMAND` in commands, `LOAD` when reading from NBT/packet, `DIMENSION_TRAVEL` in teleportation, `TRIGGERED` otherwise. |
| Entity types, blocks and items must know their registry id when constructed (`EntityType.Builder.build(ResourceKey)`, `Properties.setId`) | Ids added. **API change**: `Portal.createPortalEntityType(factory)` -> `createPortalEntityType(factory, ResourceLocation id)`. Other mods that add portal entity types must pass the id that they register with. |
| `FabricBlockSettings` removed from Fabric API | `BlockBehaviour.Properties.of()`. |
| `Entity.hurt` is final, `hurtServer` is abstract | `Portal` and `LoadingIndicatorEntity` implement `hurtServer` with the 1.21.1 default behavior. |
| `Entity.makeBoundingBox()` is final, `makeBoundingBox(Vec3)` is the overridable one | `Portal` overrides the new one. |
| `Entity.canChangeDimensions` -> `canTeleport` | Renamed. |
| `Entity.createCommandSourceStack()` only exists on `ServerPlayer`, entities are no longer `CommandSource`, `Player.sendSystemMessage` removed | `ServerPlayer.createCommandSourceStack()`, `createCommandSourceStackForNameResolution` for other entities, `displayClientMessage(msg, false)`. |
| `DimensionTransition` -> `TeleportTransition`; `ServerPlayer.changeDimension` and `teleportTo(ServerLevel, ...)` both go through `ServerPlayer.teleport(TeleportTransition)` | `MixinServerPlayerEntity_MA` injects into `teleport`. |
| `RelativeMovement` -> `Relative`; `ServerGamePacketListenerImpl.teleport(PositionMoveRotation, Set<Relative>)` | The overwrite is updated. |
| `ClientboundPlayerPositionPacket` is a record with a composite `StreamCodec` | The codec is wrapped to carry the dimension id (see section 5). |
| `ServerboundMovePlayerPacket` constructor has a `horizontalCollision` argument | Handler signature updated. |
| `ClientboundSetTimePacket` is a record, `ClientLevel.setGameTime` removed | `getLevelData().setGameTime(packet.gameTime())`. |
| `ChunkTaskPriorityQueueSorter` and `ProcessorMailbox` removed | The accessor mixin for them was already unused in 1.21.1. Removed. |
| `ChunkMap.onChunkReadyToSend(LevelChunk)` -> `(ChunkHolder, LevelChunk)` and it now also calls `ServerChunkCache.onChunkReadyToSend` | The overwrite keeps that new call. |
| `Entity.checkInsideBlocks()` -> `checkInsideBlocks(List<Movement>, Set<BlockState>)` | Re-implemented (see section 5). |
| `Projectile.getOwner` lookup moved to `findOwner(UUID)` | Redirect moved. |
| `Item.use` returns `InteractionResult`, `InteractionResultHolder` removed, `Item.getDescriptionId(ItemStack)` removed, `InteractionResult.shouldSwing()` removed | `use` updated, `getName(ItemStack)` overridden, `InteractionResult.Success.swingSource()`. |
| `BlockState.isSolidRender(level, pos)` -> `isSolidRender()`, `PortalShape.createPortalBlocks(LevelAccessor)` | Updated. |
| `ChunkGenerator.applyCarvers` lost the `GenerationStep.Carving` argument, `createStructures` got a `ResourceKey<Level>` argument | `DelegatedChunkGenerator` updated. |

### Client

| Change | Handling |
|---|---|
| `ShaderInstance` -> `CompiledShaderProgram`, shaders are loaded by `ShaderManager` from `ShaderProgram` records | The mod's 3 shader programs are `ShaderProgram`s in the `immersive_portals` namespace and are added to the preload list. |
| Shader JSON: `vertex`/`fragment` are resource locations, no `blend`, no `attributes` | The 3 JSON files are rewritten. The blend state that was in the JSON is now set in code. |
| One shader source is shared by many render types (`core/terrain`, `core/entity`); the terrain offset uniform is `ModelOffset` (was `ChunkOffset`) | `shader_transformation.yaml` now lists `minecraft:core/terrain`, `minecraft:core/entity`, ... and uses `ModelOffset`. |
| Vanilla `blit_screen` sampler is `InSampler` (was `DiffuseSampler`) | Updated. |
| `LevelRenderer.renderLevel` builds a frame graph; the passes are lambdas | The injections of the WIP target the lambdas by intermediary name (`method_62214`, ...). |
| **(1.21.4)** `LevelRenderer.renderLevel` and the weather/particle pass lambdas lost the `LightTexture` argument | Handler signatures and the `renderLevel` call descriptor in `MixinGameRenderer` updated. |
| `LevelRenderer.visibleEntities` is a field | It is swapped during portal rendering (the same `LevelRenderer` renders the outer world and the portal content of the same dimension). |
| `LevelRenderer.transparencyChain`/`translucentTarget` fields removed, `getTransparencyChain()` | Inject into `getTransparencyChain` to turn off fabulous transparency for portal content. |
| Translucent sorting moved from `renderSectionLayer` to `scheduleTranslucentSectionResort` | Injection moved. |
| `setupRender` uses the camera position instead of the player position | 3 redirects are no longer needed. |
| Cloud rendering moved to `CloudRenderer` | The cloud mesh cache is re-implemented as `MixinCloudRenderer`. |
| `FogRenderer`: no static fog color fields, `setupColor` -> `computeFogColor` returning `Vector4f`, `setupFog` returns `FogParameters`; `RenderSystem.setShaderFogStart/End` -> `setShaderFog(FogParameters)` | The fog color is tracked in the mixin, the outer world fog is restored from the pass's `FogParameters`. |
| `RenderSystem.applyModelViewMatrix()` removed, `GameRenderer.resetProjectionMatrix` removed, `ProjectionType` | Model view stack swap only; projection matrix and type are saved and restored. |
| `Minecraft.getTimer()` -> `getDeltaTracker()` | Renamed. |
| `ViewArea` and `RenderSection` use packed section positions (`repositionCamera(SectionPos)`, `getRenderSection(long)`, `RenderSection(int, long)`) | `ImmPtlViewArea` adapted. |
| `Frustum.cubeInFrustum` returns `int` and has two overloads | `MixinFrustum` adapted (see section 5). |
| `EntityRenderer<T, S extends EntityRenderState>` | `PortalEntityRenderer` has a render state that refers to the portal. |
| `ParticleEngine.render(Camera, float, BufferSource)`, per-particle calls are in static helpers | Injections moved, the custom particle path is also covered. |
| `ClientLevel` constructor: no profiler supplier, new `seaLevel` argument | Uses the sea level of the current dimension (see section 7). |
| `TextureTarget`, `RenderTarget.resize/clear`, `GlStateManager._clear` lost the `clearError` argument | Updated. |
| `LevelRenderer.renderLineBox` -> `ShapeRenderer.renderLineBox`, `Sheets.translucentCullBlockSheet` -> `translucentItemSheet` | Renamed. |
| `GuiGraphics.blit` needs a render type function, `AbstractSelectionList.getScrollbarPosition` -> `scrollBarX` | Updated. |
| `ScreenEffectRenderer.renderTex`, `DebugRenderer.render`, `CreateWorldScreen.<init>` signatures | Handler signatures updated. |
| **(1.21.4)** Items need an item model definition in `assets/<namespace>/items/` | Added for the 3 items. Without them the items render as the missing model. |

## 4. Files changed

131 files: 119 modified, 3 deleted, 9 added (`git diff HEAD --shortstat`: 2367 insertions, 978 deletions).
The complete list:

Build:
`build.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/unit-tests.init.gradle` (new), `changelog.md`,
`misc/dimlib-1.21.4.patch` (new), `PORTING_NOTES.md` (new).

Resources:
`fabric.mod.json`, `imm_ptl.accesswidener` (one new entry: `CloudRenderer$RelativeCameraPos`), `imm_ptl.mixins.json`,
`assets/immersive_portals/shaders/core/{blit_screen_noblend,portal_area,portal_draw_fb_in_area}.json`,
`assets/immersive_portals/shaders/shader_transformation.yaml`,
`assets/immersive_portals/items/{portal_wand,command_stick,portal_helper}.json` (new).

Java, `qouteall/imm_ptl/core` (non-mixin):
`ClientWorldLoader`, `McHelper`, `ScaleUtils`, `api/PortalAPI`, `api/example/ExampleGuiPortalRendering`,
`block_manipulation/{BlockManipulationClient,BlockManipulationServer}`,
`chunk_loading/{EntitySync,ImmPtlChunkTracking,WorldInfoSender}`,
`collision/{CollisionHelper,PortalCollisionHandler}`, `commands/{PortalCommand,PortalDebugCommands}`,
`compat/IPPortingLibCompat`, `ducks/{IEShader,IEWorldRenderer}`, `mc_utils/WireRenderingHelper`,
`network/ImmPtlNetworking`, `platform_specific/IPModEntryClient`,
`portal/{BreakableMirror,EndPortalEntity,LoadingIndicatorEntity,Mirror,Portal,PortalManipulation,PortalPlaceholderBlock,PortalRenderInfo}`,
`portal/custom_portal_gen/{CustomPortalGenManager,CustomPortalGeneration,PortalGenInfo}`,
`portal/custom_portal_gen/form/{DiligentMatcher,FlippingFloorSquareForm,OneWayForm}`,
`portal/global_portals/{GlobalPortalStorage,GlobalTrackedPortal,VerticalConnectingPortal,WorldWrappingPortal}`,
`portal/nether_portal/{BlockPortalShape,FastBlockAccess,GeneralBreakablePortal,NetherPortalEntity,NetherPortalGeneration}`,
`render/{FrontClipping,FrustumCuller,GuiPortalRendering,ImmPtlViewArea,LoadingIndicatorRenderer,MyGameRenderer,MyRenderHelper,OverlayRendering,PortalEntityRenderer,SecondaryFrameBuffer,ViewAreaRenderer,VisibleSectionDiscovery}`,
`render/context_management/{CloudContext,FogRendererContext}`, `render/renderer/{RendererDebug,RendererDummy}`,
`teleportation/{ClientTeleportationManager,CrossPortalSound,ServerTeleportationManager}`.

Java, `qouteall/imm_ptl/peripheral` (non-mixin):
`CommandStickItem`, `PeripheralModMain`, `PortalHelperItem`,
`alternate_dimension/{AlternateDimensions,DelegatedChunkGenerator}`,
`dim_stack/{DimEntryWidget,DimListWidget,DimStackManagement}`,
`portal_generation/IntrinsicPortalGeneration`, `wand/{PortalWandInteraction,PortalWandItem}`.

Java, `qouteall/q_misc_util`:
`CustomTextOverlay`, `Helper`, `MiscNetworking`, `my_util/{AARotation,IntBox,IntMatrix3}`.

Mixins: see section 5.

New, not a part of the released jar: `src/gametest/` (the client game test, section 8).

## 5. Mixins that required changes

There are 180 mixin classes. 29 were changed, 3 removed, 1 added.

| Mixin | Target | Change |
|---|---|---|
| `common/position_sync/MixinPlayerPositionLookS2CPacket` | `ClientboundPlayerPositionPacket` | Rewritten. The packet is a record. The mixin wraps `STREAM_CODEC` in `<clinit>` to write and read the dimension id. |
| `client/sync/MixinClientboundPlayerPositionPacket` | same | **Removed.** It injected into the removed reading constructor. Its job is done by the codec wrapper above. |
| `common/position_sync/MixinServerGamePacketListenerImpl` | `ServerGamePacketListenerImpl` | `@Overwrite teleport(PositionMoveRotation, Set<Relative>)` follows the new vanilla body. |
| `client/sync/MixinClientPacketListener` | `ClientPacketListener` | Position packet accessors (the absolute position is computed with `PositionMoveRotation.calculateAbsolute`), `setGameTime`, shadow signature of `applyLightData`. |
| `client/sync/MixinServerBoundMovePlayerPacket` | `ServerboundMovePlayerPacket` | New constructor argument. |
| `platform_specific/mixin/common/MixinServerPlayerEntity_MA` | `ServerPlayer` | Two injections (`changeDimension`, `teleportTo`) replaced by one into `teleport(TeleportTransition)`. |
| `common/chunk_sync/IEChunkTaskPriorityQueueSorter` | removed class | **Removed** (was unused). |
| `common/chunk_sync/IEDistanceManager` | `DistanceManager` | Unused accessor of a removed field removed. |
| `common/chunk_sync/MixinChunkMap_C` | `ChunkMap` | `@Overwrite onChunkReadyToSend` has the new signature and keeps the new vanilla call. |
| `common/collision/MixinEntity` | `Entity` | `checkInsideBlocks`: `@Redirect` of `getBoundingBox` + local capture replaced by a head injection and a `@WrapOperation` of `makeBoundingBox(Vec3)`. |
| `common/collision/MixinProjectile` | `Projectile` | `getOwner` -> `findOwner`. |
| `common/interaction/MixinBucketItem`, `common/portal_generation/MixinItemEntity_P`, `client/MixinMinecraft` | | Only renamed API inside the handlers (`getApproximateNearest`, `Profiler.get()`). |
| `client/MixinClientLevel` | `ClientLevel` | Constructor arguments. |
| `client/accessor/CoreShadersAccessor` | `CoreShaders` | Now an accessor of the `PROGRAMS` list (the WIP used the `register` method, which hardcodes the `minecraft` namespace). |
| `client/render/shader/MixinShaderInstance` | `CompiledShaderProgram` | The clipping equation is a real `Uniform` again, added to the program's uniform list. The WIP wrote it with raw `glUniform4f` into whichever program was bound. |
| `client/render/MixinRenderSystem_Clipping` | `RenderSystem` | `remap = false` removed. The method descriptors now contain Minecraft classes. With `remap = false` they were not remapped and the mixin would fail in production (found by the check of section 8). |
| `client/render/MixinRenderSystem_Fog` | `RenderSystem` | `setShaderFogStart/End` -> one `@ModifyVariable` on `setShaderFog(FogParameters)`. |
| `client/multiworld_awareness/MixinFogRenderer` | `FogRenderer` | Tracks the fog color returned by `computeFogColor` (the static fields are gone). |
| `peripheral/.../MixinFogRenderer_A_CVB` | `FogRenderer` | `setupColor` -> `computeFogColor`. |
| `client/render/MixinLevelRenderer` | `LevelRenderer` | 1.21.4 signatures (no `LightTexture`), swap of `visibleEntities`, `getTransparencyChain` injection, dark disc eye position redirect, fog reset uses the pass's `FogParameters`. |
| `client/render/MixinLevelRenderer_Optional` | `LevelRenderer` | Translucent sort hook moved, `CompiledShaderProgram.apply` target, 3 obsolete redirects removed. |
| `client/render/MixinLevelRenderer_BeforeIris` | `LevelRenderer` | The `"translucent"` constant is in the main pass lambda. |
| `client/render/optimization/MixinLevelRenderer_Clouds` | `LevelRenderer` | **Removed** (it was an empty "TODO re-implement" stub in the WIP). |
| `client/render/optimization/MixinCloudRenderer` | `CloudRenderer` | **Added.** Re-implements the cloud mesh cache. |
| `client/render/optimization/MixinFrustum` | `Frustum` | `cubeInFrustum(DDDDDD)I`: returns "outside" as int; also downgrades "fully inside" to "intersects" while portal frustum culling is active, because vanilla's new octree culling skips the children of nodes that are fully inside. |
| `client/render/MixinGameRenderer` | `GameRenderer` | **(1.21.4)** descriptor of `LevelRenderer.renderLevel`. |
| `client/render/MixinScreenEffectRenderer` | `ScreenEffectRenderer` | Signature. |
| `client/particle/MixinParticleEngine` | `ParticleEngine` | New `render` signature, injections moved to `renderParticleType` and `renderCustomParticles`. |
| `peripheral/.../MixinCreateWorldScreen_CVB`, `MixinDebugRenderer` | | Signatures. |
| `compat/mixin/sodium/MixinSodiumWorldRenderer` | Sodium `SodiumWorldRenderer` | `setupTerrain` has a `FogParameters` argument in Sodium 0.6.13. |

All mixins were checked in three ways: a static check of every injection target against the 1.21.4 jar,
`MixinEnvironment.audit()` at runtime (dev client, production client, production server),
and a check that every Mojang-named string in the remapped jar has a refmap entry.

## 6. Things that the upstream WIP had dropped or broken, and their state now

| Item | State |
|---|---|
| Cloud mesh cache (`MixinLevelRenderer_Clouds` was a stub) | Re-implemented (`MixinCloudRenderer`). |
| Fabulous graphics handling (transparency chain) | Restored in a different way (`getTransparencyChain`). Tested. |
| Sky eye position correction | Restored for the dark disc check. The sky color already uses the camera in 1.21.4. |
| `PortalRenderer.onAfterTranslucentRendering` removed | Not restored. Every implementation was empty in 1.21.1. |
| `clear_iris_gbuffer` shader removed | Not restored. It was not referenced by any code in 1.21.1. |
| Iris disabled in dev runs (`enable_iris=false`) | Enabled again. |
| `renderScreenTriangle` never set a shader (NullPointerException when a portal was rendered) and replaced the global projection matrix | Fixed, draws with identity matrices as in 1.21.1. |
| Compatibility renderer: `Uniform.set(int)` on float uniforms (NullPointerException) | Fixed. |
| Clipping uniform written with raw GL calls | Fixed (see section 5). |
| Shader programs registered in the `minecraft` namespace although the files are in `immersive_portals` | Fixed. |
| `MixinRenderSystem_Clipping` not remapped in production | Fixed. |

## 7. Unresolved issues and limitations

* **Portal rendering with an Iris shader pack is not tested.** With Iris 1.8.8 installed and no shader pack,
  everything in section 8 passes and all Iris compat mixins apply. No shader pack was available in the test environment,
  so `IrisPortalRenderer`, `IrisCompatibilityPortalRenderer` and `ExperimentalIrisPortalRenderer` did not run.
* **Not covered by the automated test** (they compile and their mixins apply, but nobody played with them):
  dimension stack (world creation GUI), portal wand interaction, command stick usage, end portal modes,
  datapack custom portal generation, GUI portals, portal animations, block interaction through portals,
  cross-portal collision, scaled portals, Flywheel and Cardinal Components compat (these mods were not present).
* **Sea level of remote client worlds.** `ClientLevel` needs a sea level since 1.21.2 and the server only tells it
  for the dimension the player is in. The client worlds of other dimensions use the sea level of the current dimension.
  On the client it only affects cosmetic things.
* **`Portal.createPortalEntityType` signature changed** (it needs the registry id). Mods that call it must be updated.
* **Entity render types share shaders in 1.21.4.** The clipping plane is now added to the whole `core/entity` shader,
  so a few more entity render types than in 1.21.1 are clipped by portals (for example armor layers). This is the wanted behavior,
  but it is a difference.
* **Messages that were seen in the logs and are believed to be the same as in 1.21.1** (the code paths did not change):
  * `Chunk loading failure ...` from `ImmPtlChunkTickets`, a few times when a player logs in or a dimension starts loading. The chunks do load.
  * `Received passengers for unknown entity` when a remote dimension starts to be tracked.
  * `Failed to fetch iPortal mod info 404`: `https://qouteall.fun/immptl_info/1.21.4.json` does not exist upstream. It is handled.
  * In the "compatibility" render mode, the part of a portal view where nothing is drawn (open void in the Nether) is black.
    1.21.1 clears with the same alpha value and uses the same blend state.
* `mod_version` is still 6.0.6. The jar name contains the Minecraft version.
* `FabricEntityTypeBuilder` is deprecated in Fabric API. It still works and is still used.
* Sodium deprecation warning at compile time (`SpriteUtil.markSpriteActive`). It was there before.

## 8. Build and test results

Environment: Windows 10, JDK 21, NVIDIA RTX 2060 SUPER.

| Check | Result |
|---|---|
| `gradlew clean build` | **BUILD SUCCESSFUL.** `build/libs/immersive-portals-6.0.6-mc1.21.4-fabric.jar` (DimLib and Cloth Config nested). |
| Unit tests (`gradlew test -I gradle/unit-tests.init.gradle`) | **3 of 3 passed** (`HelperTest`, `Mesh2DTest` x2). They are the only unit tests. They are not a part of `build`, as upstream. |
| Dedicated dev server (`gradlew runServer`) | Starts ("Done"), no mixin error, stops cleanly. |
| Production Fabric server with the remapped jar + `MixinEnvironment.audit()` | Starts, all mixins apply. |
| Client game test, dev, vanilla renderer | **ALL PASSED**, 60 checks (16 of them on a dedicated server). |
| Client game test, dev, Sodium 0.6.13 | **ALL PASSED**, 60 checks (16 on a dedicated server). |
| Client game test, dev, Sodium 0.6.13 + Iris 1.8.8 (no shader pack) | **ALL PASSED**, 58 checks (16 on a dedicated server). The fabulous graphics part is skipped because Iris turns that mode off. |
| Client game test, production (`runProductionClientGameTest`, official client jar + intermediary + remapped jar) | **ALL PASSED**, 60 checks (16 on a dedicated server), vanilla renderer. |
| Refmap check of the remapped jar | 0 Mojang-named mixin strings without a refmap entry. |

### Fix after the first release: crash when entering a world

The first released jar crashed with a `NullPointerException` in `FogRendererContext.update` when entering a world.
The fog context was only created by the static initializer that the mixin adds into `FogRenderer`.
Since 1.21.2 vanilla first uses `FogRenderer` when it renders the world, which is after the mod first uses the fog context.
`FogRendererContext.ensureInitialized()` now initializes `FogRenderer` on demand.

The game test did not find it because it ran `MixinEnvironment.audit()` first, and the audit initializes every class.
The audit now runs at the end of the test, so the world is entered with the class initialization order of a normal launch.

### The client game test

`src/gametest` is a small test mod that uses Fabric's client game test API. It starts a real client and

1. calls `MixinEnvironment.audit()`, which loads every mixin target class, so that every mixin is applied and checked;
2. creates a singleplayer world and checks: the mod's items and their models; a portal within one dimension
   (rendered, the player walks through, client and server positions agree); a portal to the Nether and back;
   `/tp` within and across dimensions; the compatibility and debug renderers; fabulous graphics; an arrow flying
   through both portals; clouds seen through a portal; death in the Nether and respawn in the overworld; a mirror;
   lighting an obsidian frame (the mod generates its nether portal) and walking through it;
   `/portal create_small_inward_wrapping`; adding the skyland, chaos and bright void dimensions while online and going into them;
3. starts a dedicated server in the same JVM, connects to it and repeats the portal and `/tp` checks.
   This part matters because a singleplayer world does not serialize packets, and the mod changes several packets.
   It only runs when `eula.txt` with `eula=true` is in the run directory; the test does not accept the EULA by itself.

It takes screenshots into `build/run/clientGameTest/screenshots`. They were looked at by hand: portals show the other side,
the Nether room is visible from the overworld and the overworld from the Nether, the mirror shows the player,
the wrapping box shows the repeated room.

The test framework's `waitForChunksRender()` cannot be used, because it looks into vanilla's client chunk storage and the mod replaces it.

## 9. Commands to reproduce

JDK 21 is needed (`JAVA_HOME` must point to it).

```
# 1. DimLib for 1.21.4 (once)
git clone -b 1.21.3 https://github.com/iPortalTeam/DimLib.git
cd DimLib
git apply ../ImmersivePortalsMod/misc/dimlib-1.21.4.patch
./gradlew publishToMavenLocal
cd ..
#   (in this workspace it is already done: ../DimLib, local branch port/1.21.4)

# 2. the mod
cd ImmersivePortalsMod
git checkout port/1.21.4
./gradlew clean build                               # the jar is in build/libs
./gradlew test -I gradle/unit-tests.init.gradle     # unit tests

# 3. runtime checks (they open a game window)
./gradlew runClientGameTest                                              # Sodium + Iris
./gradlew runClientGameTest -Penable_iris=false                          # Sodium
./gradlew runClientGameTest -Penable_iris=false -Penable_sodium=false    # vanilla renderer
./gradlew runProductionClientGameTest                                    # the remapped jar

# normal dev runs
./gradlew runClient
./gradlew runServer        # needs run/eula.txt
```

To include the dedicated server part of the game test, create `build/run/clientGameTest/eula.txt`
(and `build/run/prodClientGameTest/eula.txt`) containing `eula=true`.
