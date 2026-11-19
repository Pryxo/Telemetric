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
 * Nametags → Show Status: puts a title line on top of a Telos name tag inside the text display itself, so it is laid
 * out, backed and billboarded exactly like the lines Telos sends. Only the client's line cache changes — the synced
 * text stays untouched. Logic: {@link NametagAdapter#titleFor}.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayTitleMixin {

    @Shadow private Display.TextDisplay.CachedInfo clientDisplayCache;

    /** Title baked into {@link #clientDisplayCache}; null = none. Same instance while the title is unchanged. */
    @Unique private Component telemetric$title;

    @Inject(method = "cacheDisplay", at = @At("HEAD"))
    private void telemetric$refreshTitle(Display.TextDisplay.LineSplitter splitter,
                                         CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        Component title = NametagAdapter.titleFor((Display.TextDisplay) (Object) this);
        if (title != telemetric$title) {
            telemetric$title = title;
            clientDisplayCache = null; // re-split with (or without) the title line
        }
    }

    @ModifyArg(method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 0)
    private Component telemetric$prependTitle(Component text) {
        Component title = telemetric$title;
        return title == null ? text : Component.empty().append(title).append("\n").append(text);
    }
}
