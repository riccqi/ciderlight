# Honeycrisp

**Minecraft, rendered natively on Apple Silicon.**

Honeycrisp adds an Apple Metal renderer to Minecraft 26.3, next to the game's own OpenGL and Vulkan ones. On a Mac it runs the game at about double the frame rate of vanilla, and adds built-in shaders you can use without OptiFine or Iris.

> **Requires an Apple Silicon Mac (M1 or newer) on macOS 15 or later.**
> On any other computer, Honeycrisp switches itself off and the game runs normally.

## Performance

M1 Pro, render distance 16, vsync off, same scene:

| | Median FPS | 1% low |
|---|---:|---:|
| Vanilla | 95 | 27 |
| Honeycrisp, shaders off | 216 | 109 |
| Honeycrisp, shaders on | 192 | 127 |

## Built-in shaders

On by default, written for Metal from scratch:

- **Real-time shadows** from the sun and moon, including coloured shadows through stained glass
- **Light shafts** through trees and gaps in buildings, with layered mist and haze
- **Warm torch lighting**, and torches or lanterns in your hand light up the world around you
- **Ambient occlusion** for soft contact shadows in corners and under mobs
- **Water** with reflections, waves, and light that fades and tints as you go deeper
- **Waving leaves, grass and crops**
- **A new sky** with sunrises, sunsets and darker nights, and clouds that glow warm on the side facing a low sun

Vanilla resource packs keep working.

## Installing

**Easiest: Modrinth App or Prism Launcher**
1. Create a new instance for **Minecraft 26.3** with **Fabric** as the loader.
2. Add Honeycrisp to that instance (search for it, or drag the `.jar` into the instance's Mods tab).
3. Play.

**Official Minecraft Launcher**
1. Install Fabric Loader for 26.3 from [fabricmc.net/use](https://fabricmc.net/use/). The installer needs Java.
2. Put the Honeycrisp `.jar` in `~/Library/Application Support/minecraft/mods/`. In Finder, press ⌘⇧G and paste that path.
3. Pick the Fabric 26.3 profile in the launcher and play.

Honeycrisp doesn't need Fabric API. It is client-side only, so it works on any server.

## Good to know

- It hasn't been tested with other rendering mods (Sodium, Iris and similar). Try it on its own first.
- OptiFine and Iris shader packs aren't supported. Honeycrisp has its own shaders instead.
- Turn it off without uninstalling by adding `-Dhoneycrisp.disable=true` to the JVM arguments.

## License

Honeycrisp is open source under the Apache License 2.0. You can use, modify and fork it, as long as you keep the
copyright and NOTICE file crediting Richard Qi and mark what you changed.
