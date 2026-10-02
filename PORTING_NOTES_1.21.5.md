# Porting notes: Immersive Portals for Minecraft 1.21.5

Branch `port/1.21.5`, created from `port/1.21.4` (commit `cbb3e5dc`).
The notes of the previous step are in [PORTING_NOTES.md](PORTING_NOTES.md). This file only describes 1.21.4 -> 1.21.5.

## 1. Branches

One long-lived branch per supported Minecraft version. Each one is created from the previous one.

| Branch | Minecraft | State |
|---|---|---|
| `origin/1.21` (upstream) | 1.21.1 | Last upstream release, v6.0.6. |
| `origin/1.21.3` (upstream) | 1.21.3 | Unfinished upstream work. Does not compile. |
| `port/1.21.4` | 1.21.4 | Working. Released as `v6.0.6-mc1.21.4`. |
| `port/1.21.5` | 1.21.5 | This branch. |

Planned next: `port/1.21.8` (covers 1.21.6 - 1.21.8), `port/1.21.10` (1.21.9 - 1.21.10), `port/1.21.11`, then the 26.x versions.
These are the versions that Sodium and Iris target. The 26.x versions need Java 25 and have no Parchment mappings.

Rules: a fix is committed on the oldest branch that needs it and merged forward (`port/1.21.4` -> `port/1.21.5` -> ...),
never backward. Releases are tags named `v<mod version>-mc<minecraft version>`. The default branch is not touched.

## 2. Dependencies

| Dependency | port/1.21.4 | port/1.21.5 |
|---|---|---|
| Minecraft | 1.21.4 | **1.21.5** |
| Mappings | Mojang + Parchment `1.21.4:2025.03.23` | Mojang + Parchment `1.21.5:2025.06.15` |
| Fabric Loader | 0.16.14 | 0.16.14 |
| Fabric API | 0.119.4+1.21.4 | **0.128.2+1.21.5** |
| Fabric Loom | 1.10 | **1.13** (the dependencies for 1.21.5 are built with it) |
| Gradle wrapper | 8.12.1 | **9.2.1** (needed by Loom 1.13) |
| Sodium | 0.6.13 | 0.6.13 (`mc1.21.5-0.6.13-fabric`) |
| Iris | 1.8.8 | **1.8.11** |
| Cloth Config | 17.0.144 | **18.0.145** |
| Mod Menu | 13.0.4 | **14.0.2** |
| DimLib (bundled) | `1.1.0+mc1.21.4` from mavenLocal | **`1.1.0+mc1.21.5` from mavenLocal** |

DimLib has no 1.21.5 version upstream. `misc/dimlib-1.21.5.patch` applies to upstream DimLib branch `1.21.3`
(it contains the 1.21.4 change too). Its 1.21.5 part: build versions, `deactivateTicketsOnClosing`, the NBT getters.

Gradle 9 changed two things about the test task: it fails when test sources exist but no test is discovered,
and it no longer provides the JUnit launcher. `build.gradle` handles both. The unit tests are still only run with the init script.

Loom 1.13 no longer writes a refmap file into the jar. The mixin annotations are remapped in the class files
(`Fabric-Loom-Mixin-Remap-Type: static`). The refmap check that was used for 1.21.4 does not apply. The production run replaces it.

## 3. What changed in Minecraft and how it is handled

### 3.1 Rendering backend (the large part)

1.21.5 replaced the OpenGL state style rendering with `RenderPipeline`, `RenderPass`, `GpuDevice` and `GpuTexture`.
`CompiledShaderProgram`, `ShaderProgram`, `CoreShaders`, `VertexBuffer`, `BufferUploader` and the shader JSON files are gone.
`GlStateManager` moved to `com.mojang.blaze3d.opengl`.

* **OpenGL states.** Depth test, depth mask, color mask, blending and face culling are now applied from the pipeline
  for every draw. Setting them with OpenGL calls before drawing has no effect, and bypassing `GlStateManager` breaks its cache.
  New class `IPRenderPipelines` defines the pipelines of this mod (portal area, drawing a framebuffer in the portal area,
  screen triangle, framebuffer blit) with the state combinations that the renderers used to set by hand.
  Stencil test, clip plane, depth clamp, cull face direction and depth range are not managed by vanilla.
  They are still changed by direct OpenGL calls and they still apply to vanilla's draws, which the stencil renderer relies on.
* **"Always pass" depth function.** Vanilla's `DepthTestFunction` has no such value, and disabling the depth test also disables depth writing.
  The stencil renderer needs it to reset and restore the depth of the portal area.
  The pipelines that need it are registered in `IPRenderPipelines`, and `MixinGlCommandEncoder` changes the depth function for them.
* **No bound framebuffer.** `RenderTarget.bindWrite` is gone. Every render pass names its target, and vanilla draws to
  `Minecraft.getMainRenderTarget()` (which the mod already swaps). The portal renderers were written around binding.
  `MyRenderHelper.bindWrite / getBoundTarget` keeps track of the target of the mod's own drawing. It is reset every frame.
  For direct OpenGL work (clearing stencil, blitting depth and stencil) `MyRenderHelper.bindGlFramebuffer` binds the
  framebuffer object that vanilla uses for that render target.
* **Stencil buffer.** Vanilla only has a depth format without stencil, and the textures are created by the GPU device.
  When a render target has stencil enabled, its depth texture is created as depth-stencil (`MixinRenderTarget`, `MixinMainTarget`,
  `MixinGlDevice_Stencil`, helper `DepthStencilTextures`) and the framebuffer objects that use it also attach it as stencil (`MixinGlTexture`).
* **Depth copy between different depth formats.** Fabulous graphics copies the depth of the main target into its own targets by blitting.
  That needs equal depth formats. Before 1.21.5 the result was not checked. Now vanilla throws when OpenGL reports an error.
  `MixinRenderTarget` gives the receiving target the same depth format as the source before the copy.
* **Frame clearing.** The clear in `LevelRenderer` is now `CommandEncoder.clearColorAndDepthTextures`. It is wrapped instead of `RenderSystem.clear`.
* **Shader code transformation** (adds the clip plane to the terrain, entity, particle and portal area vertex shaders)
  moved from `CompiledShader.compile` to `GlDevice.compileShader` (`MixinGlDevice_Shader`). The vanilla shader sources did not change in a way that matters.
* **Clipping uniform.** It was updated in `RenderSystem.setShader`, which no longer exists.
  `MixinGlProgram` loads it in `GlProgram.setDefaultUniforms`, which runs before every draw. The rule is unchanged:
  it clips while the mod has clipping enabled (terrain layers, entities, weather, portal area), and with Iris only for the portal area.
  Vanilla registers undeclared uniforms using the uniform index as the location. The mixin sets the real location.
* **Matrices.** Shader programs always take the model view and projection matrices from `RenderSystem`.
  `MyRenderHelper.withMatrices` changes them temporarily where the mod used to set the uniforms directly.
* **Clouds.** `CloudRenderer` holds a nullable `GpuBuffer` and an index count. `MixinCloudRenderer` and `CloudContext` follow that.
* **Others.** `RenderSection.releaseBuffers` -> `reset`, `getOrigin` -> `getRenderOrigin`. `BakedModel` -> `BlockStateModel` and its parts (portal overlay).
  The lambda of `LevelRenderer.addMainPass` has another parameter order. `TextureTarget` takes a name.
  The 3 shader JSON files of the mod are removed, they are not read anymore.

The Iris renderers (`IrisPortalRenderer`, `ExperimentalIrisPortalRenderer`, `IrisCompatibilityPortalRenderer`, `IPIrisHelper`)
are ported with the same helpers. They are only used when a shader pack is active, **which was not tested** (same as in 1.21.4).

### 3.2 Not rendering

* **NBT.** The getters of `CompoundTag` and `ListTag` return `Optional`. The old ones returned a default when the key was missing.
  All uses are converted to `getXOr(key, default)`, `getCompoundOrEmpty`, `getListOrEmpty` with the old defaults (about 80 files, done by a script
  on the lines the compiler reported). Tags are records (`value()`). List tags have no element type.
  UUIDs: `putUUID/getUUID/hasUUID` -> `store/read` with `UUIDUtil.CODEC`, which is the same int array, so old data still loads.
* **Saved data.** `GlobalPortalStorage` uses `SavedDataType` with a codec that wraps the old NBT reading and writing.
  The file format is the same. It has no data fixer type. Fabric API handles the null.
* **Chunk tickets.** Tickets are in `TicketStorage`. `TicketType` is a registered record.
  The mod's type is registered as `immersive_portals:chunk_loading` (no timeout, not saved, loading and simulation, like the old region ticket).
* **Entity position interpolation.** `Entity.lerpTo` is gone, `InterpolationHandler` does it.
  `MixinLivingEntity_C` and `MixinAbstractMinecartEntity` are replaced by `MixinInterpolationHandler`.
  "Set position without interpolation" is `McHelper.cancelPositionInterpolation`.
* `moveTo` -> `snapTo`, `absMoveTo` -> `absSnapTo`. `ClickEvent` is records. `Item.appendHoverText` takes a consumer.
  `setLastHurtByPlayer(null)` is no longer possible, the mixin clears the field. `Entity` has no logger to shadow.
  `WeightedRandomList` -> `WeightedList`. `ChunkAccess.setBlockState` takes flags (the default flags are used, same effect as before).
  The client chunk packet gives the heightmaps as a map.

### 3.3 API differences for other mods

* `OverlayRendering.getQuads` takes `BlockStateModel`.
* `MyRenderHelper`: `PORTAL_AREA`, `PORTAL_DRAW_FB_IN_AREA`, `BLIT_SCREEN_NOBLEND` (shader programs) are replaced by `IPRenderPipelines`.
  `renderScreenTriangle` and `ViewAreaRenderer.renderPortalArea` have overloads that take the depth and color write states.
  `ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer` is now `buildPortalViewAreaMesh` and returns the mesh.
* `IEShader.ip_getClippingEquationUniform` returns `com.mojang.blaze3d.opengl.Uniform`.
* `IEDistanceManager.portal_getTicketSet` returns `List<Ticket>`.
* `CloudContext.cloudsBufferEmpty` -> `cloudsIndexCount`.

## 4. Results

Environment: Windows 10, JDK 21, NVIDIA RTX 2060 SUPER.

| Check | Result |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL.** `build/libs/immersive-portals-6.0.6-mc1.21.5-fabric.jar` |
| Unit tests (`gradlew test -I gradle/unit-tests.init.gradle`) | 3 of 3 passed |
| Client game test, dev, vanilla renderer | **ALL PASSED**, 60 checks (16 on a dedicated server), `MixinEnvironment.audit()` included |
| Client game test, dev, Sodium 0.6.13 + Iris 1.8.11 (no shader pack) | **ALL PASSED**, 58 checks |
| Client game test, production jar (`runProductionClientGameTest`), vanilla renderer | **ALL PASSED**, 44 checks. Only the singleplayer part ran in this run, the dedicated server part did not. |
| Static check of mixin injection targets, shadows and accessors against the 1.21.5 jar | Clean (the same 6 known notes as in 1.21.4) |

Screenshots of the stencil renderer, the compatibility renderer, a cross dimension portal and a mirror were looked at. They show the other side.

### Not done, known gaps

* **Sodium alone (without Iris) was not run** on this version. Sodium with Iris passed.
* **Iris with a shader pack is not tested** (as in 1.21.4).
* **Dedicated server:** not started separately on this version. The dedicated server part of the dev game test passed.
  In the production run it did not execute.
* 50 vanilla methods that mixins inject into have a different body than in 1.21.4 (list from a bytecode comparison).
  The injections still apply and the game test passes. They were not all read one by one for behavior changes.
* Features that the game test does not use are not verified in game: dimension stack, portal wand, command stick,
  end portal modes, datapack portal generation, GUI portals, portal animations, scaled portals, portal overlay blocks.
* The log messages listed in PORTING_NOTES.md section 7 (`Chunk loading failure`, `Received passengers for unknown entity`, the 404) are still there.

## 5. Commands

```
# DimLib for 1.21.5 (once)
git clone -b 1.21.3 https://github.com/iPortalTeam/DimLib.git
cd DimLib
git apply ../ImmersivePortalsMod/misc/dimlib-1.21.5.patch
./gradlew publishToMavenLocal
cd ..

cd ImmersivePortalsMod
git checkout port/1.21.5
./gradlew build
./gradlew test -I gradle/unit-tests.init.gradle
./gradlew runClientGameTest                                              # Sodium + Iris
./gradlew runClientGameTest -Penable_iris=false -Penable_sodium=false    # vanilla renderer
./gradlew runProductionClientGameTest
```
