package xpncvr.openatlas.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.openatlas.ChunkPipeline;

@Mixin(ClientLevel.class)
public class MixinBlockUpdate {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
        if (old != current) ChunkPipeline.markDirty(pos);
    }
}
