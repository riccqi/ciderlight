package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class MetalRenderPipeline implements BackendRenderPipeline, Destroyable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEPTH_FORMAT = MetalConst.format(GpuFormat.D32_FLOAT);

    private final MetalDevice device;
    private final String name;
    private final boolean blobShadow;
    private final long vertexLibrary;
    private final String vertexEntry;
    private final long fragmentLibrary;
    private final String fragmentEntry;
    private final int[] baseDescriptor;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final int[] uniformStages;
    private final int pushConstantStages;
    private final long depthState;
    private final boolean cull;
    private final boolean wireframe;
    private final float depthBias;
    private final float depthSlopeScale;
    private final int primitive;
    private final boolean fan;
    private final int terrainKind;
    private final int @org.jspecify.annotations.Nullable [] skyUniforms;
    private final int @org.jspecify.annotations.Nullable [] terrainUniforms;
    private final int @org.jspecify.annotations.Nullable [] shadowDescriptor;
    private final int @org.jspecify.annotations.Nullable [] entityUniforms;
    private final int @org.jspecify.annotations.Nullable [] cloudUniforms;
    private final boolean litParticle;
    private final boolean emissiveLayer;
    /** Terrain pipelines: stride of the per-section instance data and where the section's block position is in it (-1: unknown). */
    private final int sectionStride;
    private final int sectionPosOffset;
    /** Pipeline states by variant (variant()): with or without a depth attachment, for the world or the hand. */
    private final long[] states = new long[4];
    /** Variants started in the background when the pipeline was compiled (prebuildVariants), taken by stateFor. */
    @SuppressWarnings("unchecked")
    private final java.util.concurrent.CompletableFuture<Long>[] variantBuilds = new java.util.concurrent.CompletableFuture[4];
    /** Fragment library with the metal shine for items held in first person (0: this pipeline has none). */
    private long handFragmentLibrary;
    private boolean closed;

    private MetalRenderPipeline(
        final MetalDevice device, final BackendRenderPipeline.CreateInfo info, final long vertexLibrary, final String vertexEntry,
        final long fragmentLibrary, final String fragmentEntry, final int[] uniformStages, final int pushConstantStages
    ) {
        this.device = device;
        this.name = info.name();
        this.blobShadow = MetalShaders.ENABLED && info.name().equals("minecraft:pipeline/entity_shadow");
        this.vertexLibrary = vertexLibrary;
        this.vertexEntry = vertexEntry;
        this.fragmentLibrary = fragmentLibrary;
        this.fragmentEntry = fragmentEntry;
        this.uniforms = info.uniforms();
        this.uniformStages = uniformStages;
        this.pushConstantStages = pushConstantStages;
        this.baseDescriptor = describe(
            info, MetalShaders.terrainKind(info.name()) == MetalShaders.KIND_TRANSLUCENT ? ALPHA_FROM_SHADER : isOpaqueCloud(info.name()) ? ALPHA_ZERO : ALPHA_VANILLA
        );
        DepthStencilState depth = info.depthStencilState();
        this.depthState = depth != null ? device.depthState(depth.depthTest().ordinal(), depth.writeDepth()) : device.depthState(0, false);
        this.depthBias = depth != null ? depth.depthBiasConstant() : 0.0F;
        this.depthSlopeScale = depth != null ? depth.depthBiasScaleFactor() : 0.0F;
        this.cull = info.cull();
        this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
        this.primitive = MetalConst.primitive(info.primitiveTopology());
        this.fan = info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        this.skyUniforms = MetalShaders.ENABLED && info.name().equals("minecraft:pipeline/sky")
            ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "Projection") : null;
        this.terrainKind = MetalShaders.terrainKind(info.name());
        this.terrainUniforms = this.terrainKind != MetalShaders.KIND_NONE ? MetalShaders.indicesFor(info.uniforms()) : null;
        this.litParticle = MetalShaders.isLitParticle(info.name());
        int sectionStride = -1;
        int sectionPosOffset = -1;
        if (this.terrainKind != MetalShaders.KIND_NONE) {
            for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
                if (vb.bufferSlot() == 1 && vb.stepRate() == 1) {
                    sectionStride = vb.stride();
                }
            }
            for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
                // ChunkPosition (terrain.metal, attribute 4): three 32-bit ints.
                if (attrib.location() == 4 && attrib.bufferSlot() == 1 && attrib.format() == GpuFormat.RGB32_SINT) {
                    sectionPosOffset = attrib.offset();
                }
            }
            if (sectionStride <= 0 || sectionPosOffset < 0) {
                LOGGER.warn("Ciderlight: unexpected chunk section layout in {}, its shadow casters are not culled", info.name());
                sectionStride = -1;
            }
        }
        this.sectionStride = sectionStride;
        this.sectionPosOffset = sectionPosOffset;
        this.emissiveLayer = MetalShaders.isEmissiveLayer(info.name());
        boolean entity = MetalShaders.castsEntityShadow(info.name()) || this.litParticle || this.emissiveLayer;
        this.shadowDescriptor = this.terrainKind != MetalShaders.KIND_NONE || entity ? MetalShaders.shadowDescriptor(this.baseDescriptor) : null;
        this.entityUniforms = entity ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "Sampler0") : null;
        this.cloudUniforms = isOpaqueCloud(info.name()) ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "CloudInfo", "CloudFaces") : null;
    }

    /**
     * Vanilla draws clouds at 80% opacity and fades that linearly to zero over the whole cloud range, so stars and
     * the moon show through almost every cloud. With Ciderlight shaders on, clouds are opaque out to about two thirds
     * of their range and only fade beyond that (alpha = 0.8 * (1 - fog), so 0.8 * 0.35 maps to 1).
     */
    private static MetalShaderCompiler.Result opaqueClouds(final String pipeline, final MetalShaderCompiler.Result msl) {
        if (!isOpaqueCloud(pipeline)) {
            return msl;
        }
        if (!msl.source().contains("out.fragColor") || !msl.source().contains("return out;")) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, leaving clouds translucent", pipeline);
            return msl;
        }
        String patched = msl.source().replace("return out;", "out.fragColor.w = saturate(out.fragColor.w * 3.5);\n    return out;");
        return new MetalShaderCompiler.Result(patched, msl.entryPoint());
    }

    /** Vanilla's cloud face brightness (bottom, top, north, south, west, east), as SPIR-V Cross writes the array. */
    private static final java.util.regex.Pattern CLOUD_FACES = java.util.regex.Pattern.compile(
        "(constant spvUnsafeArray<float4, 6> _\\d+ = spvUnsafeArray<float4, 6>\\(\\{ )float4\\(0\\.69999998[^;]*?(\\}\\);)");

    /**
     * Vanilla shades clouds in flat greys (undersides 0.7, walls 0.8 and 0.9). With Ciderlight shaders on they are
     * shaded like the sky lights them: sunlit white tops, walls a little cooler and the undersides a blue grey, so
     * they read as volumes against the pale sky instead of flat cut-outs.
     */
    private static MetalShaderCompiler.Result shadedClouds(final String pipeline, final MetalShaderCompiler.Result msl) {
        if (!isOpaqueCloud(pipeline)) {
            return msl;
        }
        java.util.regex.Matcher m = CLOUD_FACES.matcher(msl.source());
        if (!m.find()) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, leaving cloud shading as it is", pipeline);
            return msl;
        }
        String faces = "float4(0.76, 0.76, 0.83, 1.0), float4(1.0, 0.99, 0.97, 1.0), float4(0.86, 0.865, 0.90, 1.0), "
            + "float4(0.86, 0.865, 0.90, 1.0), float4(0.93, 0.93, 0.95, 1.0), float4(0.93, 0.93, 0.95, 1.0) ";
        String patched = msl.source().substring(0, m.start()) + m.group(1) + faces + m.group(2) + msl.source().substring(m.end());
        return new MetalShaderCompiler.Result(patched, msl.entryPoint());
    }

    /** Added to the cloud vertex shader; MetalShaders.bindCloudLight supplies the buffer. */
    private static final String CLOUD_LIGHT_MSL = """
        struct McCloudLight
        {
            float4 sun;   // xyz: direction to the sun (or moon), w: how strongly it lights the clouds
            float4 warm;  // rgb: tint of the faces turned to a low sun, w: golden hour (1 with the sun at the horizon)
            float4 cool;  // rgb: tint of the faces turned away from it
        };

        // Clouds scatter most of their light, so at noon the walls facing the sun are only a little brighter than
        // those facing away. As the sun drops it lights them side-on: the sun-facing walls (and, grazed, the tops)
        // glow warm while the far side falls into a slightly cooler shade. The whole cloud takes on some of the low
        // sun's colour, the faces turned to it most. Looking toward a low sun, light scattered
        // forward through the clouds makes the whole cloud glow warm. `direction` is vanilla's face index (down, up,
        // north, south, west, east), `face` the base shade of that face, `pos` the vertex relative to the camera.
        static float3 mc_cloud_light(constant McCloudLight& light, int direction, bool top, float3 face, float3 pos)
        {
            const float3 normals[6] = { float3(0, -1, 0), float3(0, 1, 0), float3(0, 0, -1), float3(0, 0, 1), float3(-1, 0, 0), float3(1, 0, 0) };
            float3 n = top ? float3(0, 1, 0) : normals[clamp(direction, 0, 5)];
            float ndl = dot(n, light.sun.xyz);
            float k = light.sun.w;
            float golden = light.warm.w;
            float lit = saturate(ndl);
            float away = saturate(-ndl);
            float side = 1.0 + k * (0.08 + 0.18 * golden) * lit - k * (0.05 + 0.04 * golden) * away;
            float forward = golden * pow(saturate(dot(normalize(pos), light.sun.xyz)), 3.0);
            float3 warm = mix(float3(1.0), light.warm.xyz, saturate(golden * (0.5 + 0.5 * saturate(0.2 + 0.8 * ndl)) + 0.8 * forward));
            float3 cool = mix(float3(1.0), light.cool.xyz, golden * away * (1.0 - forward));
            return face * (side + 0.12 * forward) * warm * cool;
        }

        """;

    private static final java.util.regex.Pattern CLOUD_COLOR = java.util.regex.Pattern.compile("out\\.vertexColor = (_\\d+) \\* (_\\d+)\\.CloudColor;");

    /**
     * Shades each cloud face by where the sun is (see mc_cloud_light), on top of shadedClouds' fixed face greys, so a
     * low sun gives the clouds a warm lit side and a cool shaded one.
     */
    private static MetalShaderCompiler.Result sunlitClouds(final String pipeline, final MetalShaderCompiler.Result msl) {
        if (!MetalShaders.ENABLED || !pipeline.equals("minecraft:pipeline/clouds")) {
            return msl;
        }
        String source = msl.source();
        java.util.regex.Matcher color = CLOUD_COLOR.matcher(source);
        String entry = "vertex main0_out main0(";
        int entryAt = source.indexOf(entry);
        int entryEnd = entryAt < 0 ? -1 : source.indexOf(")\n{", entryAt);
        if (entryEnd < 0 || !color.find() || !source.contains("int direction =") || !source.contains("bool useTopColor =") || !source.contains("float3 pos =")) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, clouds will not be lit by the sun", pipeline);
            return msl;
        }
        String patched = source.substring(0, color.start())
            + "out.vertexColor = float4(mc_cloud_light(mcCloud, direction, useTopColor, " + color.group(1) + ".xyz, pos), " + color.group(1) + ".w) * "
            + color.group(2) + ".CloudColor;" + source.substring(color.end());
        patched = patched.substring(0, entryAt) + CLOUD_LIGHT_MSL + patched.substring(entryAt, entryEnd)
            + ", constant McCloudLight& mcCloud [[buffer(14)]]" + patched.substring(entryEnd);
        return new MetalShaderCompiler.Result(patched, msl.entryPoint());
    }

    /**
     * Vanilla's lightning bolt is a pale, mostly transparent grey. With Ciderlight shaders on it is drawn several
     * times brighter, so its additive layers build up to a white-hot core with a wide glow.
     */
    private static MetalShaderCompiler.Result brightLightning(final String pipeline, final MetalShaderCompiler.Result msl) {
        if (!MetalShaders.ENABLED || !pipeline.equals("minecraft:pipeline/lightning")) {
            return msl;
        }
        if (!msl.source().contains("out.fragColor") || !msl.source().contains("return out;")) {
            LOGGER.warn("Ciderlight: unexpected lightning shader layout in {}, leaving the bolt as it is", pipeline);
            return msl;
        }
        String patched = msl.source().replace("return out;", "out.fragColor.xyz *= float3(3.2, 3.4, 4.0);\n    return out;");
        return new MetalShaderCompiler.Result(patched, msl.entryPoint());
    }

    /** Added to an item fragment shader for the first-person hand pass; MetalShaders.bindHand supplies the two buffers. */
    private static final String HAND_SHINE_MSL = """
        struct McHandShine
        {
            float4 light;  // x: where the highlight band lies across the screen, z: its strength
            float4 color;  // rgb: the light's colour, a: a steady sheen under any light
            float4 screen; // xy: 1 / the target's size in pixels
        };

        // Metal tools, weapons and armour catch the light: a bright band slides across them as the view turns or the
        // item swings, over a faint steady sheen. `map` marks the metal sprites of the item atlas (the sprite-map layout
        // of foliage.metal); brighter texels shine more, so a wooden handle stays dull.
        static float3 mc_hand_shine(constant McHandShine& shine, const device uchar* map, float2 uv, float4 fragCoord, float3 lit)
        {
            float2 scale = *(const device float2*)map;
            uint2 grid = *(const device uint2*)(map + 8);
            int2 cell = int2(floor(uv * scale));
            if (cell.x < 0 || cell.y < 0 || uint(cell.x) >= grid.x || uint(cell.y) >= grid.y || map[16 + uint(cell.y) * grid.x + uint(cell.x)] == 0)
            {
                return float3(0.0);
            }
            float2 p = fragCoord.xy * shine.screen.xy;
            float across = p.x + (1.0 - p.y) * 0.6;
            float offset = (across - shine.light.x) / 0.09;
            float band = exp(-offset * offset);
            float brightness = dot(lit, float3(0.3, 0.6, 0.1));
            return shine.color.xyz * ((band * shine.light.z + shine.color.w) * smoothstep(0.08, 0.6, brightness) * (0.4 + brightness));
        }

        """;

    /**
     * The fragment shader of an item pipeline with the metal shine added, for the first-person hand pass; null for
     * other pipelines, or when the translated shader does not look as expected.
     */
    private static @Nullable String handShine(final String pipeline, final MetalShaderCompiler.Result msl) {
        if (!MetalShaders.ENABLED
            || !(pipeline.equals("minecraft:pipeline/item_cutout") || pipeline.equals("minecraft:pipeline/item_translucent")
                || pipeline.equals("minecraft:pipeline/item_translucent_glint"))) {
            return null;
        }
        String source = msl.source();
        String inputs = "struct main0_in\n{\n";
        String entry = "fragment main0_out main0(";
        int entryAt = source.indexOf(entry);
        int entryEnd = entryAt < 0 ? -1 : source.indexOf(")\n{", entryAt);
        if (!source.contains(inputs) || entryEnd < 0 || !source.contains("in.texCoord0") || !source.contains("out.fragColor")
            || !source.contains("return out;") || source.contains("gl_FragCoord")) {
            LOGGER.warn("Ciderlight: unexpected item shader layout in {}, held items will not shine", pipeline);
            return null;
        }
        String patched = source.substring(0, entryAt) + HAND_SHINE_MSL + source.substring(entryAt, entryEnd)
            + ", constant McHandShine& mcShine [[buffer(14)]], const device uchar* mcSprites [[buffer(13)]]" + source.substring(entryEnd);
        patched = patched.replace(inputs, inputs + "    float4 gl_FragCoord [[position]];\n");
        return patched.replace("return out;",
            "out.fragColor.xyz += mc_hand_shine(mcShine, mcSprites, in.texCoord0, in.gl_FragCoord, out.fragColor.xyz);\n    return out;");
    }

    /** -Dciderlight.dumpShaders=<directory>: write every translated Metal shader there, for writing patches like the above. */
    private static void dumpShader(final String pipeline, final String stage, final String source) {
        String directory = System.getProperty("ciderlight.dumpShaders");
        if (directory == null) {
            return;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(directory, pipeline.replaceAll("[^A-Za-z0-9_]+", "_") + "." + stage + ".metal");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, source);
        } catch (java.io.IOException e) {
            LOGGER.warn("Ciderlight: could not dump shader for {}", pipeline, e);
        }
    }

    public static MetalRenderPipeline compile(final MetalDevice device, final BackendRenderPipeline.CreateInfo info) {
        int uniformCount = info.uniforms().size();
        if (uniformCount > MetalShaderCompiler.PUSH_CONSTANT_BUFFER) {
            throw new IllegalStateException("Pipeline " + info.name() + " has more uniforms than the Metal backend supports");
        }
        int[] uniformStages = new int[uniformCount];
        int pushStages = 0;
        int kind = MetalShaders.terrainKind(info.name());
        if (kind != MetalShaders.KIND_NONE) {
            // Ciderlight shader replacement: one library holds both entry points.
            long lib = device.shaders().compileTerrainLibrary(info.uniforms(), kind, MetalShaders.vertexLayoutDefines(info));
            java.util.Arrays.fill(uniformStages, MetalConst.STAGE_VERTEX | MetalConst.STAGE_FRAGMENT);
            MetalRenderPipeline pipeline = new MetalRenderPipeline(device, info, lib, "terrain_vertex", lib, "terrain_fragment", uniformStages, 0);
            pipeline.stateFor(true);
            pipeline.prebuildVariants();
            device.shaders().prebuildFor(pipeline);
            return pipeline;
        }
        if (MetalShaders.isLitParticle(info.name())) {
            // Ciderlight shader replacement: particles lit like terrain (particle.metal).
            try {
                long lib = device.shaders().compileParticleLibrary(info.uniforms());
                java.util.Arrays.fill(uniformStages, MetalConst.STAGE_VERTEX | MetalConst.STAGE_FRAGMENT);
                MetalRenderPipeline pipeline = new MetalRenderPipeline(device, info, lib, "particle_vertex", lib, "particle_fragment", uniformStages, 0);
                pipeline.stateFor(info.depthStencilState() != null);
                pipeline.prebuildVariants();
                device.shaders().prebuildFor(pipeline);
                return pipeline;
            } catch (IllegalStateException e) {
                LOGGER.warn("Ciderlight: particles keep vanilla lighting, the particle shader could not be used for {}: {}", info.name(), e.getMessage());
            }
        }
        long vertLib = 0L;
        long fragLib = 0L;
        String handSource = null;
        String vertEntry = "";
        String fragEntry = "";
        try {
            for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
                SpvModule module = shader.module();
                int stage = module.type() == ShaderType.VERTEX ? MetalConst.STAGE_VERTEX : MetalConst.STAGE_FRAGMENT;
                SpvModule.Reflection reflection;
                try {
                    reflection = module.reflect();
                } catch (ShaderCompileException e) {
                    throw new IllegalStateException("Couldn't reflect " + shader.name(), e);
                }
                for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
                    if (descriptor.binding() < uniformCount) {
                        uniformStages[descriptor.binding()] |= stage;
                    }
                }
                if (!reflection.pushConstants().isEmpty()) {
                    pushStages |= stage;
                }
                MetalShaderCompiler.Result msl = MetalShaderCompiler.translate(shader, uniformCount, info.primitiveTopology() == PrimitiveTopology.POINTS);
                dumpShader(info.name(), module.type() == ShaderType.VERTEX ? "vert" : "frag", msl.source());
                if (module.type() != ShaderType.VERTEX) {
                    handSource = handShine(info.name(), msl);
                }
                if (module.type() != ShaderType.VERTEX) {
                    msl = opaqueClouds(info.name(), msl);
                    msl = brightLightning(info.name(), msl);
                } else {
                    msl = shadedClouds(info.name(), msl);
                    msl = sunlitClouds(info.name(), msl);
                }
                long lib;
                try {
                    lib = MetalNative.libraryCreate(device.context(), msl.source());
                } catch (IllegalStateException e) {
                    LOGGER.error("Metal rejected translated {} shader {} for pipeline {}:\n{}", module.type().getName(), shader.name(), info.name(), msl.source());
                    throw e;
                }
                if (module.type() == ShaderType.VERTEX) {
                    vertLib = lib;
                    vertEntry = msl.entryPoint();
                } else {
                    fragLib = lib;
                    fragEntry = msl.entryPoint();
                }
            }
            if (vertLib == 0L) {
                throw new IllegalStateException("Pipeline " + info.name() + " has no vertex shader");
            }
            MetalRenderPipeline pipeline = new MetalRenderPipeline(device, info, vertLib, vertEntry, fragLib, fragEntry, uniformStages, pushStages);
            if (handSource != null) {
                try {
                    pipeline.handFragmentLibrary = MetalNative.libraryCreate(device.context(), handSource);
                    dumpShader(info.name(), "hand.frag", handSource);
                } catch (IllegalStateException e) {
                    LOGGER.warn("Ciderlight: held items will not shine, Metal rejected the patched {} shader: {}", info.name(), e.getMessage());
                }
            }
            // Build the variant the pipeline will most likely be used with now, the other on demand.
            pipeline.stateFor(info.depthStencilState() != null);
            pipeline.prebuildVariants();
            device.shaders().prebuildFor(pipeline);
            return pipeline;
        } catch (RuntimeException e) {
            if (vertLib != 0L) {
                MetalNative.release(vertLib);
            }
            if (fragLib != 0L) {
                MetalNative.release(fragLib);
            }
            throw e;
        }
    }

    /** Encodes vertex layout and color targets in the int layout mc_pipeline_create expects (minus the depth format). */
    private static final int ALPHA_VANILLA = 0;
    /**
     * Translucent terrain blends in its own shader (framebuffer fetch), so fixed-function blending is off: water
     * replaces what is behind it with a refracted view, and the written alpha is the shader's marker for the
     * composite pass.
     */
    private static final int ALPHA_FROM_SHADER = 1;
    /** Alpha 0 tells the composite pass the pixel is already lit (no deferred shadows or puddles on clouds). */
    private static final int ALPHA_ZERO = 2;

    private static boolean isOpaqueCloud(final String pipeline) {
        return MetalShaders.ENABLED && (pipeline.equals("minecraft:pipeline/clouds") || pipeline.equals("minecraft:pipeline/flat_clouds"));
    }

    private static int[] describe(final BackendRenderPipeline.CreateInfo info, final int alphaMode) {
        boolean markerAlpha = alphaMode != ALPHA_VANILLA;
        IntArrayList d = new IntArrayList();
        d.add(info.vertexBuffers().size());
        for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
            d.add(vb.bufferSlot());
            d.add(vb.stride());
            d.add(vb.stepRate());
        }
        d.add(info.attribBindings().size());
        for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
            d.add(attrib.location());
            d.add(attrib.bufferSlot());
            d.add(attrib.offset());
            d.add(MetalConst.format(attrib.format()));
        }
        List<ColorTargetState> targets = info.colorTargetStates();
        d.add(targets.size());
        for (ColorTargetState target : targets) {
            if (target == null) {
                d.addElements(d.size(), new int[]{-1, 0, 0, 0, 0, 0, 0, 0, 0});
                continue;
            }
            d.add(MetalConst.format(target.format()));
            d.add(target.writeMask());
            if (target.blendFunction().isPresent() && alphaMode != ALPHA_FROM_SHADER) {
                BlendFunction blend = target.blendFunction().get();
                d.add(1);
                d.add(blend.color().sourceFactor().ordinal());
                d.add(blend.color().destFactor().ordinal());
                d.add(blend.color().op().ordinal());
                // With marker alpha the written alpha is the shader's own output, not a blend of it.
                d.add(markerAlpha ? (alphaMode == ALPHA_ZERO ? BlendFactor.ZERO : BlendFactor.ONE).ordinal() : blend.alpha().sourceFactor().ordinal());
                d.add(markerAlpha ? BlendFactor.ZERO.ordinal() : blend.alpha().destFactor().ordinal());
                d.add(markerAlpha ? BlendOp.ADD.ordinal() : blend.alpha().op().ordinal());
            } else {
                d.addElements(d.size(), new int[]{0, 0, 0, 0, 0, 0, 0});
            }
        }
        d.add(-1); // depth format placeholder, filled per variant
        d.add(MetalConst.primitive(info.primitiveTopology()));
        return d.toIntArray();
    }

    /** Pipeline state matching a pass with or without a depth attachment. */
    long stateFor(final boolean hasDepth) {
        return this.stateFor(hasDepth, false);
    }

    /** The state for a pass with or without depth, for the first-person hand pass with the metal shine where this pipeline has it. */
    long stateFor(final boolean hasDepth, final boolean hand) {
        boolean shine = hand && this.handFragmentLibrary != 0L;
        int variant = variant(hasDepth, shine);
        long state = this.states[variant];
        if (state == 0L) {
            java.util.concurrent.CompletableFuture<Long> build = this.variantBuilds[variant];
            state = build != null ? Prebuild.take(build, this.name) : this.createState(hasDepth, shine);
            this.states[variant] = state;
        }
        return state;
    }

    private static int variant(final boolean hasDepth, final boolean hand) {
        return (hand ? 2 : 0) + (hasDepth ? 0 : 1);
    }

    /**
     * Starts building the variants nobody has asked for yet in the background: the other depth variant and the hand
     * ones. Pipelines are not always drawn into the kind of pass their depth state suggests (the sky, the vignette, the
     * crosshair), and building that variant on the render thread when it first came up took a frame's time with a cold
     * shader cache. Called while the pipeline is compiled, before anything draws with it.
     */
    private void prebuildVariants() {
        for (boolean hand : new boolean[]{false, true}) {
            if (hand && this.handFragmentLibrary == 0L) {
                continue;
            }
            for (boolean hasDepth : new boolean[]{true, false}) {
                int variant = variant(hasDepth, hand);
                if (this.states[variant] == 0L) {
                    this.variantBuilds[variant] = Prebuild.start(() -> this.createState(hasDepth, hand));
                }
            }
        }
    }

    private long createState(final boolean hasDepth, final boolean hand) {
        int[] desc = this.baseDescriptor.clone();
        desc[desc.length - 2] = hasDepth ? DEPTH_FORMAT : -1;
        return MetalNative.pipelineCreate(
            this.device.context(), this.vertexLibrary, this.vertexEntry, hand ? this.handFragmentLibrary : this.fragmentLibrary, this.fragmentEntry, desc,
            hand ? this.name + " (hand)" : this.name
        );
    }

    int @org.jspecify.annotations.Nullable [] skyUniforms() { return this.skyUniforms; }

    boolean isSunrise() { return this.name.equals("minecraft:pipeline/sunrise_sunset"); }

    /** Stride of the per-section instance data, or -1 when the layout is not the expected one. */
    int sectionStride() {
        return this.sectionStride;
    }

    int sectionPosOffset() {
        return this.sectionPosOffset;
    }

    int terrainKind() {
        return this.terrainKind;
    }

    int @org.jspecify.annotations.Nullable [] terrainUniforms() {
        return this.terrainUniforms;
    }

    /** Opaque particles: captured like entities for the block-light marker, but cast no shadow. */
    boolean isLitParticle() {
        return this.litParticle;
    }

    /** Glowing eyes: captured only to be marked as light sources (MetalShaders.markEntityLight), never as shadow casters. */
    boolean isEmissiveLayer() {
        return this.emissiveLayer;
    }

    /** Slots of DynamicTransforms and Sampler0 for shadow-casting entity pipelines and lit particles, else null. */
    int @org.jspecify.annotations.Nullable [] entityUniforms() {
        return this.entityUniforms;
    }

    /** Slots of DynamicTransforms, CloudInfo and CloudFaces for the cloud pipelines (they cast shadows), else null. */
    int @org.jspecify.annotations.Nullable [] cloudUniforms() {
        return this.cloudUniforms;
    }

    int @org.jspecify.annotations.Nullable [] shadowDescriptor() {
        return this.shadowDescriptor;
    }

    /** Vanilla's round blob shadow under entities; with the shader's real shadows on it is redundant and skipped. */
    boolean isBlobShadow() {
        return this.blobShadow;
    }

    String name() {
        return this.name;
    }

    long depthState() {
        return this.depthState;
    }

    boolean cull() {
        return this.cull;
    }

    boolean wireframe() {
        return this.wireframe;
    }

    float depthBias() {
        return this.depthBias;
    }

    float depthSlopeScale() {
        return this.depthSlopeScale;
    }

    int primitive() {
        return this.primitive;
    }

    boolean isFan() {
        return this.fan;
    }

    int pushConstantStages() {
        return this.pushConstantStages;
    }

    int uniformCount() {
        return this.uniforms.size();
    }

    int uniformStages(final int index) {
        return this.uniformStages[index];
    }

    String uniformName(final int index) {
        return this.uniforms.get(index).name();
    }

    UniformType uniformType(final int index) {
        return this.uniforms.get(index).type();
    }

    @Nullable
    GpuFormat uniformFormat(final int index) {
        return this.uniforms.get(index).gpuFormat();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.encoder().queueForDestroy(this);
        }
    }

    @Override
    public void destroy() {
        long fragment = this.fragmentLibrary == this.vertexLibrary ? 0L : this.fragmentLibrary;
        for (int variant = 0; variant < this.states.length; variant++) {
            if (this.variantBuilds[variant] != null) {
                Prebuild.releaseWhenDone(this.variantBuilds[variant], this.states[variant]);
            }
        }
        for (long handle : new long[]{this.states[0], this.states[1], this.states[2], this.states[3], this.handFragmentLibrary, this.vertexLibrary, fragment}) {
            if (handle != 0L) {
                MetalNative.release(handle);
            }
        }
    }
}
