package network.vonix.vonixcore.config;

import network.vonix.vonixcore.VonixCore;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base configuration class that handles YAML loading/saving.
 * All config classes should extend this.
 */
public abstract class BaseConfig {

    protected Map<String, Object> data = new LinkedHashMap<>();
    protected final String fileName;
    protected Path configFile;

    protected BaseConfig(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Load configuration from file, creating defaults if not exists.
     */
    public void load(Path configDir) {
        configFile = configDir.resolve(fileName);

        // Create config directory if needed
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to create config directory: {}", e.getMessage());
        }

        if (Files.exists(configFile)) {
            // Load existing config
            try (InputStream is = Files.newInputStream(configFile);
                    Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Yaml yaml = new Yaml();
                Map<String, Object> loaded = yaml.load(reader);
                if (loaded != null) {
                    data = loaded;
                }
                VonixCore.LOGGER.debug("[VonixCore] Loaded config: {}", fileName);
            } catch (IOException e) {
                VonixCore.LOGGER.error("[VonixCore] Failed to load {}: {}", fileName, e.getMessage());
            }
        }

        // Apply defaults for any missing values
        setDefaults();

        // Save to ensure all defaults are written
        save();
    }

    /**
     * Save configuration to file.
     */
    public void save() {
        if (configFile == null)
            return;

        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);

            Yaml yaml = new Yaml(options);
            String output = getHeader() + "\n" + yaml.dump(data);

            Files.writeString(configFile, output, StandardCharsets.UTF_8);
            VonixCore.LOGGER.debug("[VonixCore] Saved config: {}", fileName);
        } catch (IOException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to save {}: {}", fileName, e.getMessage());
        }
    }

    /**
     * Reload configuration from file.
     */
    public void reload() {
        if (configFile != null) {
            load(configFile.getParent());
        }
    }

    /**
     * Get the header comment for this config file.
     */
    protected String getHeader() {
        return "# VonixCore Configuration - " + fileName + "\n";
    }

    /**
     * Set default values. Subclasses override this.
     */
    protected abstract void setDefaults();

    // ============ Helper Methods ============

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = data;

        for (int i = 0; i < parts.length - 1; i++) {
            Object value = current.get(parts[i]);
            if (value instanceof Map) {
                current = (Map<String, Object>) value;
            } else {
                return defaultValue;
            }
        }

        Object value = current.get(parts[parts.length - 1]);
        if (value == null) {
            return defaultValue;
        }

        if (defaultValue instanceof String && value != null && !(value instanceof String)) {
            return (T) String.valueOf(value);
        }

        if (defaultValue instanceof Integer && value instanceof Number) {
            return (T) Integer.valueOf(((Number) value).intValue());
        }
        if (defaultValue instanceof Long && value instanceof Number) {
            return (T) Long.valueOf(((Number) value).longValue());
        }
        if (defaultValue instanceof Double && value instanceof Number) {
            return (T) Double.valueOf(((Number) value).doubleValue());
        }
        if (defaultValue instanceof Float && value instanceof Number) {
            return (T) Float.valueOf(((Number) value).floatValue());
        }

        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = data;

        for (int i = 0; i < parts.length - 1; i++) {
            Object existing = current.get(parts[i]);
            if (existing instanceof Map) {
                current = (Map<String, Object>) existing;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                current = newMap;
            }
        }

        current.put(parts[parts.length - 1], value);
    }

    /**
     * Set a value only if it doesn't already exist.
     */
    public void setDefault(String key, Object value) {
        if (get(key, null) == null) {
            set(key, value);
        }
    }

    public String getString(String key, String defaultValue) {
        return get(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return get(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return get(key, defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return get(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key, defaultValue);
    }
}
