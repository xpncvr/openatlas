package xpncvr.openatlas.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.openatlas.ChunkPipeline;
import xpncvr.openatlas.WaypointHud;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void runMapUpdates(DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) ChunkPipeline.frame(mc.level);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
            ordinal = 0))
    private void captureMatrices(DeltaTracker deltaTracker, CallbackInfo ci,
                                 @Local(ordinal = 0) Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
        WaypointHud.capture(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
    }
}
