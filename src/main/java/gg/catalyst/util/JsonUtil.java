// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public final class JsonUtil {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();

    private JsonUtil() {}

    /**
     * Warms Jackson up at startup instead of inside the first command. Also catches anything
     * the shadow jar's minimizer stripped now, not mid-upload.
     */
    public static void setup(Plugin plugin) {
        try {
            final String json = MAPPER.writeValueAsString(new WarmupData(plugin.getName(), "warmup"));
            MAPPER.readValue(json, WarmupData.class);
            MAPPER.readTree(json);
        } catch (Throwable t) {
            // Deliberately logged rather than swallowed: a broken relocation here means
            // every JSON path in the plugin is dead, and silence would hide that.
            plugin.getLogger().log(Level.WARNING,
                    "JSON support failed to initialise; features that parse responses will not work.", t);
        }
    }

    public static final class WarmupData {
        public String plugin;
        public String version;

        public WarmupData() {}

        public WarmupData(String plugin, String version) {
            this.plugin = plugin;
            this.version = version;
        }
    }
}
