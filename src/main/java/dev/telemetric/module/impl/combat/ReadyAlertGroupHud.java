package dev.telemetric.module.impl.combat;

/**
 * A separate, movable Ready Alerts popup ("Ready Alerts 2", "3", …). A Ready Alert tab whose Position is set to
 * "Separate N" shows its popups here instead of in the main Ready Alerts element. Only listed in the ClickGUI and
 * drawn / shown in the HUD editor while at least one tab uses it. Turning the main Ready Alerts off still stops
 * every popup.
 */
public final class ReadyAlertGroupHud extends ReadyAlertDisplay {

    private final int number;

    ReadyAlertGroupHud(int number, double defaultY) {
        super("Ready Alerts " + number, "Separate Ready Alerts popup: tabs whose Position is \"Separate " + number
                + "\" show here. Move it in the HUD editor", 0.5, defaultY);
        this.number = number;
    }

    /** The Position option of a tab that targets this element. */
    String option() {
        return ReadyAlertTab.SEPARATE + number;
    }

    private boolean used() {
        return ReadyAlertsHud.get().uses(option());
    }

    @Override
    protected String previewText() {
        return "Ready Alerts " + number;
    }

    @Override
    public boolean isVisibleInGui() {
        return used();
    }

    @Override
    public boolean isShown() {
        return isEnabled() && used();
    }
}
