package dev.ciderlight.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import dev.ciderlight.backend.HitchTrace;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the hitch trace when each key and mouse event happened, so it can measure input lag. */
@Mixin(SDLEventHandler.class)
public class SDLEventHandlerMixin {
    @Inject(method = {"handleKeyEvent", "handleMouseButtonEvent", "handleMouseMotionEvent", "handleMouseWheelEvent"}, at = @At("HEAD"))
    private void ciderlight$traceInput(final SDL_Event event, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            HitchTrace.input(event.common().timestamp());
        }
    }
}
