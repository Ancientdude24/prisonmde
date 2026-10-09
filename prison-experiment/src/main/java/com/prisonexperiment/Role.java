package com.prisonexperiment;

import net.minecraft.util.Formatting;

public enum Role {
    PRISONER("prisoner", "Prisoner", Formatting.GOLD, 0xFF7A00),
    GUARD("guard", "Guard", Formatting.BLUE, 0x1E4FD8),
    WARDEN("warden", "Warden", Formatting.DARK_GRAY, 0x151515);

    public final String id;
    public final String displayName;
    public final Formatting formatting;
    public final int color;

    Role(String id, String displayName, Formatting formatting, int color) {
        this.id = id;
        this.displayName = displayName;
        this.formatting = formatting;
        this.color = color;
    }

    public boolean isStaff() {
        return this != PRISONER;
    }

    public static Role byName(String name) {
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(name) || r.id.equalsIgnoreCase(name)) return r;
        }
        return null;
    }
}
