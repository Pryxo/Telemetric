package dev.telemetric.setting.impl;

import dev.telemetric.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A toggle that owns its own sub-settings (e.g. one puzzle inside a "Puzzle Solver" module). The value is the on/off
 * switch, and right-clicking the row in the ClickGUI opens or closes the children below it. Children are ordinary
 * settings of the same module; register them with {@code add(group.child(new …))}.
 */
public class GroupToggleSetting extends BooleanSetting {

    private final List<Setting<?>> children = new ArrayList<>();
    private boolean open;

    public GroupToggleSetting(String name, String description, boolean defaultValue) {
        super(name, description, defaultValue);
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
    @Override
    public boolean isGroup() {
        return true;
    }

    @Override
    public boolean isGroupExpanded() {
        return open;
    }

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
}
