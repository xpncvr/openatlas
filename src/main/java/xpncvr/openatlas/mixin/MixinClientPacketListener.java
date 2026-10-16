package xpncvr.openatlas.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.openatlas.ChunkPipeline;
import xpncvr.openatlas.OpenAtlasClient;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "updateLevelChunk", at = @At("TAIL"))
    private void onChunkData(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) ChunkPipeline.onChunkPacket(mc.level, x, z, data);
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
    private void onCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        if (packet.playerId() == mc.player.getId()) OpenAtlasClient.onPlayerDeath(mc.player);
    }
}
