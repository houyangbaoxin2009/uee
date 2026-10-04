package org.uee.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Resolves names from the language files in the resource manager.
 *
 * <p>Reading language tables directly is what lets display names be produced off the render thread
 * and on a dedicated server, which is where an exporter most needs to work and where the aligned
 * tools are weakest. Asking the client for a name instead would tie collection to a loaded client
 * language and force a language switch per locale.
 *
 * <p>Tables from every namespace are merged into one map per locale, so a mod's own translations are
 * found without knowing which mod owns the key. A key with no entry returns {@code null} — an absent
 * localization must stay absent rather than be invented.
 */
public final class ResourceManagerTranslator implements Translator {

    private final Map<String, Map<String, String>> tables = new HashMap<>(4);

    public ResourceManagerTranslator(ResourceManager resources) {
        load(resources, ZH_CN);
        load(resources, EN_US);
    }

    private void load(ResourceManager resources, String locale) {
        Map<String, String> table = new HashMap<>(1 << 16);
        for (String namespace : resources.getNamespaces()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace,
                    "lang/" + locale + ".json");
            try {
                Resource resource = resources.getResource(id).orElse(null);
                if (resource == null) {
                    continue;
                }
                try (InputStreamReader reader = new InputStreamReader(resource.open(),
                        StandardCharsets.UTF_8)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (!root.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = root.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                        JsonElement value = entry.getValue();
                        if (value.isJsonPrimitive()) {
                            table.put(entry.getKey(), value.getAsString());
                        }
                    }
                }
            } catch (Exception ignored) {
                // A single unreadable or malformed language file must not abort the load: the
                // remaining namespaces still contribute, and the missing keys simply stay absent.
            }
        }
        tables.put(locale, table);
    }

    @Override
    public String translate(String key, String locale) {
        if (key == null) {
            return null;
        }
        Map<String, String> table = tables.get(locale);
        return table == null ? null : table.get(key);
    }

    /** Number of resolved keys for a locale; used by diagnostics. */
    public int size(String locale) {
        Map<String, String> table = tables.get(locale);
        return table == null ? 0 : table.size();
    }
}
