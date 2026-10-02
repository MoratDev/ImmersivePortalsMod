# Porting notes: Immersive Portals for Minecraft 1.21.8

Branch `port/1.21.8`, created from `port/1.21.5` (commit `d1701632`).
The notes of the previous step are in [PORTING_NOTES_1.21.5.md](PORTING_NOTES_1.21.5.md). This file only describes 1.21.5 -> 1.21.8
(it covers the changes of 1.21.6, 1.21.7 and 1.21.8).

## 1. Branches

| Branch | Minecraft | State |
|---|---|---|
| `port/1.21.4` | 1.21.4 | Working. Released as `v6.0.6-mc1.21.4`. |
| `port/1.21.5` | 1.21.5 | Working. Released as `v6.0.6-mc1.21.5`. |
| `port/1.21.8` | 1.21.8 | This branch. |

Planned next: `port/1.21.10`, `port/1.21.11`, then the 26.x versions.

## 2. Dependencies

| Dependency | port/1.21.5 | port/1.21.8 |
|---|---|---|
| Minecraft | 1.21.5 | **1.21.8** |
| Mappings | Mojang + Parchment `1.21.5:2025.06.15` | Mojang + Parchment `1.21.8:2025.09.14` |
| Fabric Loader | 0.16.14 | **0.19.5** |
| Fabric API | 0.128.2+1.21.5 | **0.136.1+1.21.8** |
| Fabric Loom | 1.13 | 1.13 (unchanged) |
| Gradle wrapper | 9.2.1 | 9.2.1 (unchanged) |
| Sodium | 0.6.13 | **0.7.3** (`mc1.21.8-0.7.3-fabric`) |
| Iris | 1.8.11 | **1.9.6** |
| Cloth Config | 18.0.145 | **19.0.147** |
| Mod Menu | 14.0.2 | **15.0.2** |
| DimLib (bundled) | `1.1.0+mc1.21.5` from mavenLocal | **`1.1.0+mc1.21.8` from mavenLocal** |

DimLib has no 1.21.8 version upstream. `misc/dimlib-1.21.8.patch` applies to upstream DimLib branch `1.21.3`
(it contains the 1.21.4 and 1.21.5 changes too). Its 1.21.8 part: build versions, and `ServerPlayer.server` became private (`getServer()`).

Iris 1.9 needs glsl-transformer 3 at runtime in the development environment. That library is built for Java 21,
so `build.gradle` calls `disableAutoTargetJvm()`. The mod's own class files still target Java 17.

`fabric.mod.json` now breaks with Sodium other than 0.7.3 and Iris other than 1.9.6.

## 3. What changed in Minecraft and how it is handled

### 3.1 Rendering: uniforms became uniform buffers (the large part)

1.21.6 removed the plain uniforms of the vanilla shaders. The model view matrix, color modulator and model offset are in the
`DynamicTransforms` block, the projection matrix in `Projection`, the fog in `Fog`, the light directions in `Lighting`.
`RenderSystem` only holds buffer slices. `FogParameters`, `RenderSystem.getProjectionMatrix()`, `RenderSystem.setShaderColor()`,
`Lighting.setupLevel()` and `GameRenderer.setRenderHand()` are gone. The fog renderer moved to `net.minecraft.client.renderer.fog`
and is an object owned by `GameRenderer`.

Vanilla renders the world once per frame, so it has one buffer for each of these. This mod renders the world many times per frame.

* **Fog.** The vanilla fog buffer is persistently mapped. Overwriting it while earlier draw calls may not have been executed is
  not safe, and the outer world rendering continues to use its fog after the portals are rendered.
  `MixinFogRenderer` gives every world rendering its own slice of a per-frame `DynamicUniformStorage` (`IPFogUniform`),
  returned from `FogRenderer.getBuffer(WORLD)`. `MyGameRenderer` saves and restores the current slice and `RenderSystem`'s shader fog
  around rendering portal content. The fog distance transformation (fog disabled for some portals) is now applied to `FogData`
  before the buffer is written, so Sodium (which reads `FogData`) gets the same values.
  The static water fog fields moved to `WaterFogEnvironment` (`MixinWaterFogEnvironment` holds the per-dimension swapping).
* **Projection matrix.** It cannot be read back. `MixinGameRenderer` tracks the matrix that vanilla uploads for the level
  (`MyRenderHelper.getLevelProjectionMatrix()`); the portal renderers use that. After rendering portal content the old matrix is
  written into the level projection buffer again. This mod's own drawing uses its own projection buffer (`withMatrices`).
* **Clipping plane.** `iportal_ClippingEquation` is still a plain uniform that the shader code transformation adds.
  Vanilla no longer manages plain uniforms, so `MixinGlProgram` only keeps its location and `MixinGlCommandEncoder` uploads it with
  `glUniform4f` at the end of `trySetup` (before every draw, after the program is bound).
* **This mod's shaders** (`portal_area`, `portal_draw_fb_in_area`) import the vanilla uniform blocks.
  `portal_draw_fb_in_area` gets the size from `textureSize` (it had the uniforms `w` and `h`).
  The pipelines use `MATRICES_PROJECTION_SNIPPET`; `IPRenderPipelines.draw` writes a `DynamicTransforms` slice and binds the default uniforms.
  Samplers are bound with texture views.
* **Light directions.** The level light directions are in a buffer that vanilla only updates when the client world changes.
  `MyGameRenderer.resetDiffuseLighting()` updates it for the dimension that is being rendered.
* **Hand rendering.** Vanilla always clears depth and renders the hand at the end of `GameRenderer.renderLevel`.
  `MixinGameRenderer` has its own flag and skips the depth clear, the hand, the screen effects and the debug crosshair when rendering portal content.
* **Terrain layers.** `LevelRenderer.renderSectionLayer` was replaced by `ChunkSectionsToRender.renderGroup` (opaque, translucent, tripwire).
  The clipping / mirror culling hooks moved there. The lambda parameters changed (`GpuBufferSlice` fog, one matrix).
* **Clouds.** The cloud mesh is in a texel buffer and the cloud info in a uniform buffer that is overwritten on every cloud rendering.
  `MixinCloudRenderer` uses an extra info buffer for every additional cloud rendering in a frame, and the cloud context cache
  now moves the texel buffer in and out.
* **Textures.** `GpuDevice.createTexture` has usage and depth/layers parameters; the depth-stencil mixins follow that.
* **GUI.** `GuiGraphics.pose()` is a 2D matrix stack; `BlockRenderLayerMap` moved to `fabric-rendering-v1` and takes `ChunkSectionLayer`.

### 3.2 Sodium 0.7

* `OcclusionCuller.Visitor` is `RenderSectionVisitor`; `setupTerrain` has a matrices parameter and Sodium's own `FogParameters`.
* Sodium now caches the draw batches of every render region. They are filled from the render list, which this mod keeps separate for
  each portal layer, so `MixinSodiumRenderRegion` keeps the cached batches separate for each portal layer too.
* `SodiumWorldRenderer.lastFogParameters` (used when drawing terrain) is swapped together with the rendering context, because the
  outer world draws its translucent terrain after the portals.

### 3.3 Not rendering

* **Entity data.** Entities read from `ValueInput` and write to `ValueOutput`. The portal data stays a `CompoundTag`:
  `Portal` implements the new methods by reading / writing the whole tag through a map codec (`McHelper.readWholeTag` / `writeWholeTag`),
  so the save format and the sync packet are unchanged. `McHelper.saveEntityWithoutId` / `loadEntity` replace `saveWithoutId(tag)` / `load(tag)`.
* `ServerPlayer.server` is private (`getServer()`), `ServerPlayer.level()` returns `ServerLevel`, the `Player` constructor lost two parameters.
* Packet sending takes netty's `ChannelFutureListener` (`MixinServerGamePacketListenerImpl_Redirect`).
* `Projectile` owner and `ItemEntity` thrower are `EntityReference`. The cross-dimension owner lookup wraps `EntityReference.get` in `Projectile.getOwner`.
* `isPlayerCollidingWithAnythingNew` became `isEntityCollidingWithAnythingNew` (also used for vehicles; the hook only handles the player)
  and uses `getPreMoveCollisions`.
* `Entity.checkInsideBlocks` makes the bounding box in its per-movement overload; `Minecraft.addInitialScreens` returns boolean;
  `WorldVersion.name()`.

## 4. Verification

| Check | Result |
|---|---|
| `gradlew build` | passes |
| Unit tests | pass |
| `gradlew runClientGameTest -Penable_sodium=false -Penable_iris=false` | ALL PASSED (60 checks, including the dedicated server part), mixin audit clean |
| `gradlew runClientGameTest` (Sodium 0.7.3 + Iris 1.9.6, no shader pack) | ALL PASSED (58 checks, the fabulous part is skipped with Iris), mixin audit clean |
| `gradlew runProductionClientGameTest` (remapped jar) | ALL PASSED (44 checks; the dedicated server part was skipped, no `eula.txt` in that run directory) |

The screenshots were compared with the ones of 1.21.5 (same-dimension portal, cross-dimension portal, looking back from the nether,
fabulous mode, recursive wrapping box, alternate dimensions).

## 5. Known issues / not tested

* Iris with an actual shader pack was not tested.
* `ExampleGuiPortalRendering` (a debug command) draws its framebuffer immediately. Since 1.21.6 the GUI is drawn deferred,
  so that image is drawn under the screen's own elements.
* The mirror scene of the game test puts the mirror 0.01 in front of stained glass, which is coplanar with the adjusted clipping plane.
  Which of these glass faces show up inside the mirror is unstable; the pattern differs from 1.21.5.
  Checked with one extra run with the mirror 0.3 in front of the glass: the reflection is clean (no glass inside the mirror),
  so the clipping works and this is only the coplanar case.
* Not run: Pehkui, Gravity Changer, Flywheel / Create compatibility (the same as in the previous steps).
