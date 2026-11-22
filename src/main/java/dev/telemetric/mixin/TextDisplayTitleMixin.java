package dev.telemetric.mixin;

import dev.telemetric.game.NametagAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Status Title → Show Status and Visual → Player Currency: put extra lines into a Telos name tag, inside the text
 * display itself, so they're laid out, backed and billboarded exactly like the lines Telos sends. Only the client's
 * line cache changes, and the synced text stays untouched. The logic is in {@link NametagAdapter#extrasFor} and
 * {@link NametagAdapter#decorate}.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayTitleMixin {

    @Shadow private Display.TextDisplay.CachedInfo clientDisplayCache;

    /** The lines baked into {@link #clientDisplayCache}, or null for none. Same instance as long as nothing changes. */
    @Unique private NametagAdapter.Extras telemetric$extras;

    @Inject(method = "cacheDisplay", at = @At("HEAD"))
    private void telemetric$refreshTitle(Display.TextDisplay.LineSplitter splitter,
                                         CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        NametagAdapter.Extras extras = NametagAdapter.extrasFor((Display.TextDisplay) (Object) this);
        if (extras != telemetric$extras) {
            telemetric$extras = extras;
            clientDisplayCache = null; // split again with (or without) the extra lines
        }
    }

    @ModifyArg(method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 0)
    private Component telemetric$prependTitle(Component text) {
        NametagAdapter.Extras extras = telemetric$extras;
        return extras == null ? text : NametagAdapter.decorate(text, extras);
    }

    /** Widens the wrap width to fit the extra lines, so they never get split across lines. */
    @ModifyArg(method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 1)
    private int telemetric$fitTitle(int width) {
        return NametagAdapter.lineWidthFor(telemetric$extras, width);
    }
}
