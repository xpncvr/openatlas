package xpncvr.openatlas.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.openatlas.ChunkPipeline;
import xpncvr.openatlas.OpenAtlasClient;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            shift = At.Shift.AFTER))
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) ChunkPipeline.onChunkPacket(mc.level, packet.x(), packet.z(), packet.chunkData());
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
    private void onCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        if (packet.playerId() == mc.player.getId()) OpenAtlasClient.onPlayerDeath(mc.player);
    }
}
