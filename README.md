# Honeycrisp

A Fabric mod for Minecraft Java **26.3** that adds a native **Apple Metal** rendering backend and a
built-in, Metal-native shader pipeline (sun lighting, real-time shadows, light shafts, color grading).

Minecraft 26.3 split its renderer into swappable backends ("renderpearl": OpenGL and Vulkan).
Honeycrisp adds a third backend that talks to Metal directly through a small Objective-C bridge
(`src/main/native/honeycrisp.m`) called via Java's FFM API. Minecraft's GLSL shaders are compiled
to SPIR-V by the game, then translated to Metal Shading Language with SPIRV-Cross, so vanilla
rendering, resource packs and core-shader packs keep working.

## Results (M1 Pro, 1708x960 window, render distance 16, vsync off, same jungle scene)

| Configuration                    | Median FPS | 1% low |
|----------------------------------|-----------:|-------:|
| Vanilla OpenGL                   |       95.4 |   26.5 |
| Honeycrisp, shaders off          |      215.7 |  109.2 |
| Honeycrisp, shaders on           |      191.9 |  126.5 |
| Honeycrisp, shaders on, raining  |      160.9 |  103.1 |

Reproduce with `./gradlew runClient -Pworld=<save> -Pbench=20` (add `-Pvanilla` or `-Pnoshaders`).

The table predates a later optimisation pass (two frames in flight, cheaper fog march, texture compression left on).
Measured on the `MetalTest` save with the same settings, alternating runs, shaders on went from 164–173 to 205–208
median FPS and 1% lows from 111–121 to 141–150; the frame is now limited by the CPU rather than the GPU.

By default the CPU waits for the GPU to finish the previous frame before it records the next (one frame in flight),
so what is on screen stays close to the mouse when the GPU is the limit. `-PframesInFlight=2` lets it record while the
GPU still renders two earlier frames, which keeps the GPU busier (the figures above were measured that way) at the cost
of a frame of input latency. `-PgpuProfile=true` logs GPU time per
render pass.

## Shaders

Enabled by default. Written for this backend rather than ported from an OptiFine/Iris pack:

- **Lighting**: rebuilt from the lightmap coordinates in the style of Complementary. In the Overworld vanilla's fixed
  per-face shading (east and west faces at 60%) is divided back out of the terrain colour and replaced by light that
  knows where the sun is: a warm direct sun (strongly orange when low) on top of blue light from the open sky, the
  sun about two and a half times as bright as the sky light in shade, so sunlit faces stand clearly apart from shaded
  ones. The sky light keeps a gentle up-to-down falloff, sunlit ground throws a little warm light back onto walls,
  crossed plants are lit by the sun from any side, and lit colours above white roll off instead of clipping. Sprites
  vanilla always shades as if facing up (torches, lanterns, vines, ladders) are left alone. Torches and other block
  light have a steep, warm falloff and fade under open daylight. Night sky light is darker than vanilla, with a
  small cool ambient floor so caves never go fully black.
  Flames (fire, soul fire, campfires, and the flame in torches and lanterns) are drawn at full brightness like the
  light sources they are, whatever the light around them.
- **Held light**: a torch, lantern, glowstone or other light-emitting block in either hand (or a lava bucket) lights
  the ground, mobs and items around the player as they carry it, fading by one light level per block. Other players'
  lights count too (the eight nearest). It is not stopped by walls.
- **Shadows**: the terrain and entity draws Minecraft already issues are captured and replayed from the sun, or
  from the moon at night, into a 4096² depth map (one frame of latency), sampled with hardware PCF. Sub-texel raster jitter and
  reprojected visibility history smooth slowly moving shadow edges. History rejects depth mismatches and resets
  after teleports, world changes, resizing, or abrupt light changes. Moonlight
  is dim and blue, and scales with the moon phase. Foliage (leaves, grass, vines) casts full shadows, so every tuft and canopy
  shows on the ground, and is lit evenly from both sides; stained glass, ice and water tint the light
  that passes through them via a colour map rendered alongside the depth map. Coloured glass
  filters the direct RGB light on terrain/entities and in air/underwater rays; texture transparency
  no longer washes saturated dyes into nearly white light, and vanilla face shading is excluded from
  glass absorption. Ground under coloured glass still receives the sky light around it, so its shadow is a
  soft tint rather than a flat patch of the dye colour. Clear glass and water retain gentler filtering. The colour map's alpha channel
  records the nearest translucent depth, so air in front of glass is not tinted. Stacked translucent layers still share one
  accumulated transmission value; resolving air between individual layers would require a deep shadow map.
- **Sky**: a directional Metal sky gradient: a hazy pale blue by day, near white over a broad band above the horizon,
  washing towards lavender and pink while the sun is low, warm sunrise/sunset toward the sun, a softer violet opposite horizon, dim blue nights and muted overcast weather. This is
  an artistic scattering approximation, not an HDR atmospheric transport solver. The actual solar elevation
  drives the palette continuously through the sun/moon shadow handoff. The sky is drawn before vanilla
  sun/moon/stars, and clouds retain their existing geometry. Sky colour also drives fog ambient light,
  surface shadow tint, water's environment reflection fallback and terrain's render-distance fade.
  Fluids, blindness/darkness and non-Overworld skyboxes keep their vanilla sky behavior.
- **Atmosphere**: three world-anchored layers—low mist, a broad veil and thin high-altitude haze—with
  smooth noise-shaped banks, denser twilight/rain and separate height falloffs. Beer–Lambert absorption and shadowed RGB
  scattering are integrated together. Sunlight passing through gaps in trees or buildings lights the mist itself;
  blocked regions retain softer ambient sky light. The veil extends through hills and treetops so shafts
  do not disappear above the low mist. A forward-plus-broad scattering phase makes morning/evening beams
  visible from oblique angles, with subdued fog ambient fill to preserve their contrast. The air around
  the camera is four times as dense, fading over 40 blocks, so nearby scenery sits in mist without the distance whiting out
  (`honeycrisp.fogNear`, `honeycrisp.fogNearRange`). Further out the air takes on the colour of the sky behind it (aerial perspective), so
  distant hills fade into the pale horizon in layers rather than into a dark blue veil; rays into the open sky scatter
  the sky's own colour, so the fog does not grey it. The range follows the render distance, with a
  separate distant shadow cascade covering all of it (2048², or 4096² beyond 320 blocks), refreshed every four frames
  and blended into the near map.
  Both shadow cascades carry glass transmission, so distant fog retains the tint beyond the near map.
  Both radiance and transmittance use bounded, reprojected history and depth-aware half-resolution
  reconstruction. Fog rays include the effective eye from walking/hurt bob, and fog density lives in world
  coordinates, so footsteps do not sway the fog independently of the scenery. Chunk sections
  the camera does not see are included as casters when they stand between the light and something in view (terrain,
  or the open air above it) and the light actually falls on them: it is followed down from the sky through the
  sections it can pass (Minecraft's own cave-culling data says which), so the hillside facing the sun is a caster and
  the rock behind it is not. They are drawn in the shadow passes only. The list is taken from loaded terrain and
  refreshed on movement, chunk changes and as the sun moves. Where the view is closed in (a cave, a mine tunnel) that
  is the ground surface above the sections in view rather than everything around the camera. The shadows are the
  same as with every loaded section drawn (`-PcasterMask=false` draws them all, `-PcasterSweep=false` everything
  between the light and the view, for comparison). This is a single-scattering approximation; clouds do not cast volume shadows.
- **Ambient occlusion**: screen-space GTAO (ground-truth ambient occlusion, implemented from Jimenez et al. 2016,
  "Practical Realtime Strategies for Accurate Indirect Occlusion") adds soft contact darkening in corners, under
  overhangs, between blocks, around plants and under mobs, on top of vanilla's smooth-lighting AO. It is evaluated at
  half resolution with two rotating slices (2-block radius, distance falloff so foreground silhouettes cast no halo),
  averaged over each 3×3 block of the same surface, and accumulated with reprojected, plane-validated history.
  Forward terrain reads last frame's result reprojected and applies it only to sky and ambient light (torch light
  slightly), so sunlit and torch-lit surfaces stay bright; entities take this frame's result in the composite.
  Terrain seen through water or glass uses the depth snapshot taken before translucent terrain.
- **Entities**: shadowed from the depth buffer in the composite pass, with a block-light marker pass so mobs next
  to torches stay lit; gold-coloured block entities such as bells get a sun highlight.
- **Waving foliage**: leaves sway as a whole; grass, ferns, flowers, saplings, crops and bushes bend from an anchored
  base; two-block plants bend continuously across both halves; vines, hanging roots, cave/weeping/twisting vines and
  sugar cane drift gently as whole blocks so stacks never split. The wind is a smooth world-anchored field (rolling
  gusts plus sway, with a faster flutter and larger amplitude in rain) and fades out without sky light. The sway fades
  out between 64 and 96 blocks from the camera, where it is under a pixel. Shadows sway
  with the plants: the shadow-map passes apply the same offset. Which blocks wave comes from the block registry
  (the leaves tag and plant block classes); every sprite their models use is marked in a small grid over the block
  atlas, rebuilt whenever block models reload (resource packs). The vertex shader identifies its quad's sprite from
  the middle of the quad's UVs, reading the diagonal vertex straight from the chunk vertex buffer, so grass-block
  side overlays and other tinted blocks never move. Motion tuned after Complementary Shaders; the code is original.
- **Clouds**: drawn opaque out to about two thirds of their range, so stars no longer show through them, and shaded
  as volumes: white tops, slightly cooler walls and blue-grey undersides. They take less of the haze than the ground.
  Each face is also lit by where the sun is: walls facing it are a little brighter than those facing away. At golden
  hour the whole cloud takes on the low sun's colour, the faces turned to it most, while the far side stays cooler,
  and clouds between the camera and a low sun glow warm with forward-scattered light.
- **Water surface**: seen from above, water is shaded completely in the translucent terrain shader, which blends
  through Apple-GPU framebuffer fetch instead of fixed-function blending. Layered, world-anchored value-noise waves
  (six octaves turned by the golden angle, smaller and faster with each octave) give the normal; octaves fade before
  they get smaller than a few pixels, and the filtered-out slope is added back as roughness, so distant water is calm
  and its glitter spreads instead of aliasing. Rain adds chop and expanding drop rings on water open to the sky.
  The scene behind the water is refracted by the waves (never sampling foreground objects in front of the surface)
  and absorbed per RGB channel by the water's thickness, taken from the biome colour (Beer–Lambert), so shallow water
  is clear and deep water turns blue-green; a soft animated foam line marks where the water is only a sliver deep.
  A GGX sun (or dim moon) glint follows the wave normal and is masked by the shadow map. Covered water does not
  mirror the sky. The look was tuned after Complementary and Bliss; the Metal code is original. Ice, glass and water
  seen from below keep their previous blending. `-PbenchWater=open` builds an open tank with a beach for testing
  (with `-PbenchCamX`, `-PbenchCamY`, `-PbenchYaw`, `-PbenchPitch`).
- **Water reflections**: screen-space reflections on water following the wave normals, reflecting the opaque scene
  snapshotted before translucent terrain. Hits require a refined front-to-back crossing with a bounded
  linear-depth residual, so foreground silhouettes cannot masquerade as reflected surfaces. Invalid,
  hidden or off-screen hits fall back to the environment colour; screen-space reflections cannot recover
  geometry missing from the camera depth buffer. Ice and glass keep their vanilla look.
- **Underwater light**: RGB Beer–Lambert absorption, refracted sun/moon directions, shadowed single
  scattering and animated, world-anchored caustics. The half-resolution volume integrates the complete
  water path, with depth-aware reconstruction and bounded temporal history. Water replaces the vanilla
  linear water fog and scrolling overlay; blindness/darkness and other graphics backends retain vanilla
  behavior. History resets when entering/leaving water or changing surface levels.
  The visual reference for the layered atmosphere and underwater effects is **Bliss v2.1.2 (Chocapic13 Shaders edit)** by X0nk / Chocapic13; the Metal implementation
  is original and does not embed GLSL from the pack. It uses a local water-surface plane and the existing
  directional shadow map, so waterfalls, shore crossings and refraction around submerged occluders are
  approximations. This volume applies while the camera is underwater; above-water views retain the water reflections.
- **Composite**: deferred shadows for entities, integrated atmospheric scattering, screen-space sun rays
  streaming past far occluders (clouds, distant terrain) when the sun or moon is on screen, a gentle filmic
  grade and vignette, using Apple-GPU framebuffer fetch so the scene never leaves tile memory. Vanilla's round
  blob shadow under entities is skipped.

These shaders are Metal-only: they need the Honeycrisp backend's draw capture and composite pass, so they do
not run on the OpenGL or Vulkan backends and are not an Iris/OptiFine pack.

**Quality.** On phone-class GPUs (the A-series chip in the MacBook Neo) the shaders run at low quality: a 2048² near
shadow map and a 1024² distant one (2048² beyond 320 blocks), fog worked out at about 320 rows and ambient occlusion at
about 400, and 16-step fog, underwater and reflection marches with 12 screen-space shaft samples. The world (and the
held item) is also drawn at two thirds of the window's resolution in each direction, about 45% of the pixels, and
stretched over the window with a little sharpening; the HUD and menus are still drawn at full resolution.
`honeycrisp.renderScale=<0.25–1>` sets that fraction at either quality. Every other GPU gets high quality, as described
above.
`honeycrisp.quality=low` or `=high` overrides the choice (dev client: `-Pquality=low`); the log names the one in use.
`honeycrisp.waterReflections=false` leaves water mirroring only the sky, at any quality.

Options (JVM `-D` flags): `honeycrisp.quality=low|high`, `honeycrisp.renderScale=0.67`, `honeycrisp.shaders=false`, `honeycrisp.shadowSize=2048`,
`honeycrisp.shadowDistance=96`, `honeycrisp.fogDistance=256` (caps how far fog and its light shafts reach, 32–1024 blocks;
by default they follow the render distance),
`honeycrisp.fogNear=3` (extra mist density at the camera, 0 for none) and `honeycrisp.fogNearRange=40` (blocks it fades over), `honeycrisp.shadowHistory=false` (disable temporal shadow filtering for comparison),
`honeycrisp.waving=false` (no waving foliage), `honeycrisp.wavingDebug=true` (colour terrain by foliage kind),
`honeycrisp.ao=false` (disable ambient occlusion), `honeycrisp.aoDebug=true` (show the AO term alone),
`honeycrisp.disable=true` (fall back to vanilla backends).

Dev-client flags: `-Pvanilla`, `-Pnoshaders`, `-Pfullscreen`, `-Pvalidate` (Metal API validation), `-Ptrace`
(dump one frame's passes and draws), `-Pbench=<s>`, `-PbenchScene` (sky test scene with pillars,
water and mobs), `-PbenchTime=<ticks>`, `-PbenchWeather=rain`, `-PbenchShot=true` (screenshot near the end of
a bench run), `-PbenchDayCycle=true` (let the sun move during the run), `-PshadowHistory=false`
(compare without temporal shadow filtering), `-Pao=false` / `-PaoDebug=true` (ambient occlusion off / AO only), `-PbenchWalk=true` with `-PbenchScene` (real footsteps towards
  the morning sun, including view bob), `-PbenchSprint=true` with `-PbenchWalk=true`, or `-PbenchJump=true`
  (repeated jumps). These motion checks also work at a `-PbenchX`/`-PbenchZ` ground location in a disposable world copy.
  `-PbenchTrees=true` with `-PbenchScene` builds isolated tree canopies; `-PbenchSequence=true` captures frames during
  movement. `-PbenchWater=under|covered|surface|open` with `-PbenchScene`
(an isolated water tank; use a disposable save), `-PbenchReflection=true` with `-PbenchScene`
(foreground tree and far-bank reflection regression scene), `-PbenchFog=true` with `-PbenchScene`
(distant slatted wall for fog/shadow testing; use a disposable save), `-PbenchGlass=true` with `-PbenchScene`
(red/green/blue glass projection test; use a disposable save), `-PbenchFoliage=true` with `-PbenchScene`
(a garden of grass, two-block plants, crops, sugar cane, leaves and vines; add `-PbenchShot2=true` for a second
screenshot a second earlier to compare motion), `-Pwaving=false`, `-PbenchX=<x> -PbenchZ=<z>` (stand on clear ground at that spot; with `-PbenchItem=<id>` the player
is in survival and holds that item, so its own shadow shows), `-PbenchThirdPerson=true` (view from behind the player),
`-PbenchPos=<x>,<y>,<z>` (hover exactly there as a spectator: in a cave, under water), `-PbenchRun=<command>;<command>`
(run these at the player once it is in place), `-PbenchClock=<seconds>` (stop the clock that wind, drifting haze and
caustics run on).

Two screenshots only compare pixel for pixel when they were taken with `-PbenchClock`, `-Pwaving=false`, clouds off and
the same frame rate (cap it with `maxFps` in `run/options.txt`): the haze drifts with the time of day on the wall, and
the temporal filters settle slightly differently at different frame rates.

Diagnostics are off in normal play. `-Dhoneycrisp.debug=true` (dev client: `-Pdebug`) traces the first 30 seconds after
joining a world in the game log (lines starting `Honeycrisp trace`): frame times, time spent waiting for the GPU or the
display, shader compiles, uploads, GPU time per pass (and the slowest frame's breakdown), how long frames stayed on screen,
and the size of the section list with how much of it is there for shadows only; it also logs the details of shader
setup. `-Dhoneycrisp.hitchTraceSeconds=<s>` changes the length of the trace. `-PautoTurn=<degrees per second>` and
`-PautoWalk=true` move the player for such a trace.

With vsync on, frames go on screen as soon as they are done, up to the display's refresh rate; Max Framerate caps them
as usual (60 on a 120 Hz display gives an even rate). `-Dhoneycrisp.framePacing=true` instead picks an even rate
automatically from what the display can show (on a ProMotion display 120, 80 or 60 fps) and what frames cost.

## Building

`./gradlew installMod` builds the jar and copies it into the launcher's mods folder, so the next launch of the
Fabric profile uses the current shaders.


Requires macOS on Apple Silicon, Xcode command-line tools and a Java 25 JDK: Gradle itself must run on Java 25, so set
`JAVA_HOME` to one. If Gradle cannot find it as a toolchain, add `org.gradle.java.installations.paths=<its home>` to
`~/.gradle/gradle.properties`.

On other machines (Intel Macs, an Intel Java under Rosetta, Windows, Linux) the mod turns itself off at startup,
logs one line saying why, and the game uses its default renderer.

```
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev client
./gradlew checkBackendBindings # binding cache regressions (also included in build)
./gradlew checkShadowCasters    # shadow coverage while walking/turning (also included in build)
python3 tests/check_shaders.py  # compile all shader variants and run GPU regressions (requires Metal access)
```

## Installing

1. Install Fabric Loader for Minecraft 26.3 (https://fabricmc.net/use/installer/).
2. Copy `build/libs/honeycrisp-0.1.1.jar` into `~/Library/Application Support/minecraft/mods/`.
3. Launch the Fabric profile. Honeycrisp is client-side only, so it works on any server.
4. Optional: add `--enable-native-access=ALL-UNNAMED` to the profile's JVM arguments to silence
   Java's native-access warning.

## Not done yet

- Shadow capture is limited to loaded terrain and submitted entities; off-screen terrain is included within the shadow area, but unseen entities are not separately collected.
- Ambient occlusion only sees what is on screen: occluders outside the view or hidden behind other geometry do not
  count, translucent surfaces (water, glass, ice) receive none, and terrain lags entities by one frame (a mob's
  contact shadow on the ground follows it with one frame of delay).
- Entities in caves get shadowed like everything else: there is no per-pixel sky-light value for
  non-terrain geometry, so an entity in a torch-lit cave is darkened by the sun shadow test.
- No bloom/HDR (the vanilla target is 8-bit).
- GPU timestamp queries report nothing (affects only the F3 GPU timing chart).
- OptiFine/Iris shader packs (e.g. Complementary) are not supported.
- Profiling shows the CPU side is now mostly Minecraft's own chunk/draw extraction, with the GPU
  busy about a third of the frame; the backend itself is ~5% of render-thread time.

## License

Copyright 2026 Richard Qi. Licensed under the [Apache License, Version 2.0](LICENSE): you may use, modify and
redistribute Honeycrisp, including in forks, as long as you keep the copyright and [NOTICE](NOTICE) file, include the
licence, and mark the files you changed.
