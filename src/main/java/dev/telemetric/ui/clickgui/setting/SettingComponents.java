package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ChoiceSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.framework.GuiContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Registry mapping setting types → UI components. Lookup walks the class hierarchy, so a subclass of a
 * registered setting reuses its parent's component automatically.
 * <p>
 * New setting type? {@code SettingComponents.register(MySetting.class, MyComponent::new);}
 */
public final class SettingComponents {

    private static final Map<Class<?>, BiFunction<GuiContext, Setting<?>, SettingComponent<?>>> FACTORIES = new HashMap<>();

    static {
        register(BooleanSetting.class, BooleanComponent::new);
        register(NumberSetting.class, NumberComponent::new);
        register(ColorSetting.class, ColorComponent::new);
        register(KeybindSetting.class, KeybindComponent::new);
        register(TextSetting.class, TextComponent::new);
        register(ActionSetting.class, ActionComponent::new);
        register(ChoiceSetting.class, SettingComponents::choice);
        register(MultiChoiceSetting.class, MultiChoiceComponent::new);
        register(GroupToggleSetting.class, GroupToggleComponent::new);
        register(GroupSetting.class, GroupComponent::new);
    }

    private SettingComponents() {}

    @SuppressWarnings("unchecked")
    public static <S extends Setting<?>> void register(Class<S> type, BiFunction<GuiContext, S, ? extends SettingComponent<?>> factory) {
        FACTORIES.put(type, (ctx, setting) -> factory.apply(ctx, (S) setting));
    }

    public static SettingComponent<?> create(GuiContext ctx, Setting<?> setting) {
        for (Class<?> type = setting.getClass(); type != null; type = type.getSuperclass()) {
            BiFunction<GuiContext, Setting<?>, SettingComponent<?>> factory = FACTORIES.get(type);
            if (factory != null) return factory.apply(ctx, setting);
        }
        throw new IllegalArgumentException("No UI component registered for " + setting.getClass().getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SettingComponent<?> choice(GuiContext ctx, ChoiceSetting setting) {
        return new ChoiceComponent<>(ctx, setting);
    }
}
