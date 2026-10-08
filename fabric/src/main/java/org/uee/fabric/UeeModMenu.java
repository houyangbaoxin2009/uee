package org.uee.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;
import org.uee.mc.ConfigScreen;
import org.uee.mc.Ui;

/**
 * What the mod list is told about this mod.
 *
 * <p>Separate from the client entrypoint on purpose. This class names the list mod's interface, so it can
 * only be loaded when that mod is there; the client entrypoint is loaded on every client and must not.
 * Keeping them apart is what lets this mod run without it, which is the ordinary case and the one that has
 * to work: nothing here is required, and nothing was added to the build to make it compile.
 */
public final class UeeModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return UeeModMenu::screen;
    }

    /**
     * The settings screen, or the mod list itself when the settings could not be read.
     *
     * <p>A screen that throws would take the list down with it, and a reader who cannot open the settings
     * still wants to see what mods are installed.
     */
    private static Screen screen(Screen parent) {
        try {
            return ConfigScreen.open(parent);
        } catch (RuntimeException e) {
            return parent;
        }
    }
}
