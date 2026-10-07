package dev.ciderlight.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import dev.ciderlight.backend.HitchTrace;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
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

    @Inject(method = "handleKeyEvent", at = @At("HEAD"))
    private void ciderlight$traceKey(final SDL_Event event, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            SDL_KeyboardEvent key = event.key();
            HitchTrace.key(key.scancode(), event.type() == 768, key.repeat(), event.common().timestamp());
        }
    }

    @Inject(method = "handleMouseMotionEvent", at = @At("HEAD"))
    private void ciderlight$traceMouse(final SDL_Event event, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            SDL_MouseMotionEvent motion = event.motion();
            HitchTrace.mouseMotion(motion.xrel(), motion.yrel());
        }
    }
}
