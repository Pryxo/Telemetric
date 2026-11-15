package dev.telemetric.mixin;

import dev.telemetric.Telemetric;
import dev.telemetric.game.SoundFiles;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/** {@link SoundFiles}: streams for sound files on disk are opened by Telemetric instead of the resource manager. */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void telemetric$fileStream(Identifier id, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (!SoundFiles.isFileStream(id)) return;
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                return SoundFiles.open(id);
            } catch (IOException e) {
                Telemetric.LOGGER.warn("Sound file not played: {}", e.toString());
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()));
    }
}
