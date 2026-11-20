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
 * Nametags → Show Status: puts a title line on top of a Telos name tag, inside the text display itself, so it's laid
 * out, backed and billboarded exactly like the lines Telos sends. Only the client's line cache changes, and the
 * synced text stays untouched. The logic is in {@link NametagAdapter#titleFor}.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayTitleMixin {

    @Shadow private Display.TextDisplay.CachedInfo clientDisplayCache;

    /** The title baked into {@link #clientDisplayCache}, or null for none. Same instance as long as the title doesn't change. */
    @Unique private Component telemetric$title;
    @Unique private NametagAdapter.TitlePosition telemetric$position = NametagAdapter.TitlePosition.TOP;

    @Inject(method = "cacheDisplay", at = @At("HEAD"))
    private void telemetric$refreshTitle(Display.TextDisplay.LineSplitter splitter,
                                         CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        Component title = NametagAdapter.titleFor((Display.TextDisplay) (Object) this);
        NametagAdapter.TitlePosition position = NametagAdapter.titlePosition();
        if (title != telemetric$title || position != telemetric$position) {
            telemetric$title = title;
            telemetric$position = position;
            clientDisplayCache = null; // split again with (or without) the title line
        }
    }

    @ModifyArg(method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 0)
    private Component telemetric$prependTitle(Component text) {
        Component title = telemetric$title;
        return title == null ? text : NametagAdapter.withTitle(text, title, telemetric$position);
    }

    /** Widens the wrap width to fit the title, so it never gets split across lines. */
    @ModifyArg(method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 1)
    private int telemetric$fitTitle(int width) {
        return NametagAdapter.lineWidthFor(telemetric$title, width);
    }
}
