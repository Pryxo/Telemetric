package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import dev.telemetric.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A plain dropdown that only groups sub-settings (e.g. "Debug", "Colors"). It holds no value and is never saved, and
 * clicking the row opens or closes the children below it. Register children with {@code add(group.child(new …))},
 * the same as for {@link GroupToggleSetting}.
 */
public class GroupSetting extends Setting<Boolean> {

    private final List<Setting<?>> children = new ArrayList<>();
    private boolean open;

    public GroupSetting(String name, String description) {
        super(name, description, Boolean.FALSE);
        notPersistent();
    }

    /** Makes {@code setting} a child, so it only shows (indented) while this group is open. */
    public <S extends Setting<?>> S child(S setting) {
        setting.setGroup(this);
        children.add(setting);
        return setting;
    }

    public List<Setting<?>> children() {
        return Collections.unmodifiableList(children);
    }

    /** UI state: whether the children are showing (remembered with the GUI layout, not in profiles). */
    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    @Override
    protected boolean showsChildren() {
        return open && isVisible();
    }

    @Override
    public JsonElement toJson() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void fromJson(JsonElement element) {
        // stateless
    }
}
