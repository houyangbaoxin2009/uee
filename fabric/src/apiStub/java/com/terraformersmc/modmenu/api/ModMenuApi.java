package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * A stand-in for the mod list's entrypoint interface. See {@link ConfigScreenFactory} for why it is declared
 * here rather than depended on, and why it is not shipped.
 *
 * <p>Only the one method this mod answers is declared. The real interface's other methods are all default,
 * so implementing this one method is enough to satisfy the real interface. Read the current shape with
 * {@code javap} against the mod list's artifact before changing anything here.
 */
public interface ModMenuApi {

    /** The settings screen for this mod, or null when it has none. */
    ConfigScreenFactory<?> getModConfigScreenFactory();
}
