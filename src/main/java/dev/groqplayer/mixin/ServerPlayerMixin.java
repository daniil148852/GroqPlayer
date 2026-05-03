package dev.groqplayer.mixin;

import dev.groqplayer.GroqPlayerMod;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.server.PlayerManager.class)
public class ServerPlayerMixin {

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
            at = @At("HEAD"))
    private void onPlayerChat(net.minecraft.network.message.SignedMessage message,
                               ServerPlayerEntity sender,
                               net.minecraft.network.message.MessageType.Parameters params,
                               CallbackInfo ci) {
        try {
            String content = message.getSignedContent();
            String senderName = sender.getName().getString();
            GroqPlayerMod.getPlayerManager().onPlayerChat(senderName, content);
        } catch (Exception e) {
            // Don't crash on chat injection errors
        }
    }
}
