package dev.groqplayer.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

// Client-side mixin placeholder — AI player rendering is handled server-side
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerMixin {
    // No client-side modifications needed
    // AI players appear as normal players to other clients via vanilla networking
}
