package dev.ciderlight.backend;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/**
 * Translates the SPIR-V that renderpearl produces from Minecraft's GLSL into Metal Shading
 * Language with SPIRV-Cross. Bindings follow the fixed argument-table layout in ciderlight.m:
 * uniform i -> buffer/texture/sampler i, push constants -> buffer 15, vertex buffers -> 16+.
 */
public final class MetalShaderCompiler {
    static final int PUSH_CONSTANT_BUFFER = 15;
    private static final int MSL_VERSION_3_0 = 30000;
    private static final int EXECUTION_MODEL_VERTEX = 0;
    private static final int EXECUTION_MODEL_FRAGMENT = 4;

    public record Result(String source, String entryPoint) {
    }

    private MetalShaderCompiler() {
    }

    public static Result translate(final BackendRenderPipeline.CreateInfo.Shader shader, final int uniformCount, final boolean points) {
        SpvModule module = shader.module();
        int model = module.type() == ShaderType.VERTEX ? EXECUTION_MODEL_VERTEX : EXECUTION_MODEL_FRAGMENT;
        IntBuffer spirv = module.spv().asIntBuffer();
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ptr = stack.callocPointer(1);
            check(Spvc.spvc_context_create(ptr), "create context");
            context = ptr.get(0);
            check(Spvc.spvc_context_parse_spirv(context, spirv, spirv.remaining(), ptr), "parse SPIR-V");
            long ir = ptr.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), "create compiler");
            long compiler = ptr.get(0);

            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), "create options");
            long options = ptr.get(0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            // Metal rejects a [[point_size]] output on non-point pipelines, so only emit it for points.
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, points);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_DEFAULT, points);
            if (model == EXECUTION_MODEL_VERTEX) {
                // Metal's framebuffer Y runs opposite to Vulkan/GL memory order; flipping here keeps
                // render targets laid out exactly like the other backends, so sampling just works.
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
            }
            check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install options");

            SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
            for (int i = 0; i < uniformCount; i++) {
                Spvc.spvc_msl_resource_binding_init(binding);
                binding.stage(model).desc_set(0).binding(i).msl_buffer(i).msl_texture(i).msl_sampler(i);
                check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add resource binding");
            }
            Spvc.spvc_msl_resource_binding_init(binding);
            binding.stage(model)
                .desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                .binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING)
                .msl_buffer(PUSH_CONSTANT_BUFFER);
            check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add push constant binding");

            check(Spvc.spvc_compiler_set_entry_point(compiler, shader.entryPoint(), model), "set entry point");
            check(Spvc.spvc_compiler_compile(compiler, ptr), "compile to MSL");
            String source = MemoryUtil.memUTF8(ptr.get(0));
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, shader.entryPoint(), model);
            return new Result(source, entry);
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void check(final int result, final String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross failed to " + what + " (" + result + ")");
        }
    }
}
