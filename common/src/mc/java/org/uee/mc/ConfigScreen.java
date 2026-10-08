package org.uee.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.uee.Uee;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;

/**
 * The settings, editable in the game.
 *
 * <h2>Built from the settings surface rather than beside it</h2>
 *
 * <p>The rows come from {@link ConfigFile#values(ExportConfig)} and {@link ConfigFile#renderableKeys()} —
 * the same map the written file and the command line use. A screen with its own list of settings would be a
 * second place to add one, and the interesting failure of that arrangement is not a missing row but a row
 * that shows a default the run does not use.
 *
 * <p>Saving goes through the same three steps the command does: the edited text is parsed by
 * {@link ConfigFile#parse}, laid over the current configuration, and written by {@link Uee#writeConfig}. So
 * the screen has no rules of its own about what a value means, and a value it does not understand is
 * refused by the parser that already refuses it everywhere else.
 *
 * <h2>What it edits</h2>
 *
 * <p>This instance's configuration file, which is the one the runs on this computer read. On a client that
 * is the client's own file, and the screen says so, because changing a setting here does not reach a
 * server — and a screen that looked as though it did would be worse than no screen.
 */
public final class ConfigScreen extends Screen {

    /** The screen this one was opened from, so that closing returns there. */
    private final Screen parent;

    /** Where the profile's settings live, shown so that a reader knows what is being changed. */
    private final Path file;

    /** The rows, in the order the surface lists them, keyed for saving. */
    private final Map<String, EditBox> fields = new LinkedHashMap<>();

    private ConfigList list;
    private Component status = Component.empty();
    private boolean statusIsProblem;

    private ConfigScreen(Screen parent, ExportConfig config, Path file) {
        super(Ui.t("uee.screen.title"));
        this.parent = parent;
        this.file = file;
        rebuild(config);
    }

    /**
     * Opens the screen over the configuration in use.
     *
     * @param parent the screen to return to
     */
    public static ConfigScreen open(Screen parent) {
        try {
            ConfigResolver.Resolved resolved = Uee.resolveForRun(ConfigFile.empty());
            return new ConfigScreen(parent, resolved.config(), Uee.configFile());
        } catch (IOException | IllegalStateException e) {
            // Nothing was read, so there is nothing to show. Returned as-is and reported by the caller.
            throw new IllegalStateException(Ui.format("uee.screen.unreadable", String.valueOf(e)), e);
        }
    }

    /** Fills the field map from a configuration, which is also how a reload works. */
    private void rebuild(ExportConfig config) {
        fields.clear();
        Map<String, String> values = ConfigFile.values(config);
        for (String key : ConfigFile.renderableKeys()) {
            String value = values.get(key);
            if (value == null) {
                continue;
            }
            EditBox box = new EditBox(font, 0, 0, 100, 18, Ui.t("uee.screen.value"));
            box.setValue(value);
            box.setMaxLength(4096);
            fields.put(key, box);
        }
    }

    @Override
    protected void init() {
        int top = 48;
        int bottom = height - 56;
        list = new ConfigList(minecraft, width, bottom - top, top, 22);
        for (Map.Entry<String, EditBox> e : fields.entrySet()) {
            list.add(new Row(e.getKey(), e.getValue()));
        }
        addRenderableWidget(list);

        int y = height - 46;
        int quarter = width / 4;
        addRenderableWidget(Button.builder(Ui.t("uee.screen.save"), b -> save())
                .bounds(8, y, quarter - 12, 20).build());
        addRenderableWidget(Button.builder(Ui.t("uee.screen.reload"), b -> reload())
                .bounds(quarter + 4, y, quarter - 12, 20).build());
        addRenderableWidget(Button.builder(Ui.t("uee.screen.defaults"), b -> defaults())
                .bounds(2 * quarter + 4, y, quarter - 12, 20).build());
        addRenderableWidget(Button.builder(Ui.t("uee.screen.done"), b -> onClose())
                .bounds(3 * quarter, y, quarter - 12, 20).build());
    }

    // ---------------------------------------------------------------- actions

    /**
     * Parses what was typed, lays it over the current configuration and writes the result.
     *
     * <p>The parser is the validator: a screen that decided for itself which values were acceptable would be
     * a second set of rules, and the first place they would diverge is a value that the file accepts and the
     * screen rejects, or the other way round.
     */
    private void save() {
        String text = editedText();
        ConfigFile edited;
        try {
            edited = ConfigFile.parse(text);
        } catch (RuntimeException e) {
            status("uee.screen.rejected", true, String.valueOf(e.getMessage()));
            return;
        }
        try {
            ConfigResolver.Resolved resolved = Uee.resolveForRun(edited);
            if (!resolved.warnings().isEmpty()) {
                // A warning means part of what was typed did not take effect, which is exactly what the
                // reader needs to be told rather than a cheerful "saved".
                status("uee.screen.partlyApplied", true, String.join("; ", resolved.warnings()));
                return;
            }
            Path written = Uee.writeConfig(resolved.config());
            rebuild(resolved.config());
            init();
            status("uee.screen.saved", false, written.toAbsolutePath().toString());
        } catch (IOException | IllegalStateException e) {
            status("uee.screen.saveFailed", true, String.valueOf(e.getMessage()));
        }
    }

    /** Re-reads the file, discarding what was typed. */
    private void reload() {
        try {
            ConfigResolver.Resolved resolved = Uee.resolveForRun(ConfigFile.empty());
            rebuild(resolved.config());
            init();
            status("uee.screen.reloaded", false);
        } catch (IOException | IllegalStateException e) {
            status("uee.screen.unreadable", true, String.valueOf(e.getMessage()));
        }
    }

    /** Fills every field with what a configuration with nothing set would use. */
    private void defaults() {
        ExportConfig bare = org.uee.config.ExportConfig.builder().build();
        Map<String, String> values = ConfigFile.values(bare);
        for (Map.Entry<String, EditBox> e : fields.entrySet()) {
            String value = values.get(e.getKey());
            if (value != null) {
                e.getValue().setValue(value);
            }
        }
        status("uee.screen.defaultsShown", false);
    }

    /** What was typed, as the td lines a file would contain. */
    private String editedText() {
        StringBuilder out = new StringBuilder(1024);
        for (Map.Entry<String, EditBox> e : fields.entrySet()) {
            String value = e.getValue().getValue().trim();
            if (!value.isEmpty()) {
                out.append(e.getKey()).append(" = ").append(value).append('\n');
            }
        }
        return out.toString();
    }

    private void status(String key, boolean problem, Object... args) {
        status = Ui.t(key, args);
        statusIsProblem = problem;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        // Which file this changes, said plainly: a reader on a server needs to know this is not the server's.
        graphics.drawCenteredString(font, Ui.t("uee.screen.edits", file == null
                ? Ui.lookup("uee.screen.nowhere") : file.toAbsolutePath().toString()),
                width / 2, 26, 0xA0A0A0);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 62,
                    statusIsProblem ? 0xFF7070 : 0x80FF80);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** The scrolling list of settings. */
    private final class ConfigList extends ContainerObjectSelectionList<Row> {

        ConfigList(net.minecraft.client.Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        /** Adds a row. The parent's method is protected, so it is called from inside the subclass. */
        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return width - 40;
        }

        @Override
        protected int getScrollbarPosition() {
            return width - 14;
        }
    }

    /** One setting: its name, and a field holding its value as the file would spell it. */
    private final class Row extends ContainerObjectSelectionList.Entry<Row> {

        private final String key;
        private final EditBox field;

        Row(String key, EditBox field) {
            this.key = key;
            this.field = field;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int labelWidth = Math.min(150, entryWidth / 2);
            graphics.drawString(font, key, x, y + 5, 0xE0E0E0, false);
            field.setX(x + labelWidth);
            field.setY(y + 1);
            field.setWidth(entryWidth - labelWidth - 8);
            field.setHeight(18);
            field.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(field);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(field);
        }
    }
}
