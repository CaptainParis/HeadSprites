package paris.headsprites.sprite;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SpriteManager {
    private static SpriteManager instance;

    private final JavaPlugin plugin;
    private final Map<String, Sprite> sprites = new LinkedHashMap<>();
    private final Map<String, List<String>> sequences = new LinkedHashMap<>();
    private FileConfiguration config;
    private File configFile;
    private String apiKey;
    private boolean webEnabled;
    private int webPort;
    private String webBind;
    private String webToken;

    private SpriteManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public static void initialize(JavaPlugin plugin) {
        if (instance == null) {
            instance = new SpriteManager(plugin);
        }
    }

    public static SpriteManager getInstance() {
        return instance;
    }

    private void load() {
        sprites.clear();
        sequences.clear();
        configFile = new File(plugin.getDataFolder(), "sprites.yml");
        if (!configFile.exists()) {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().severe("Failed to create plugin data folder");
                return;
            }
            config = new YamlConfiguration();
            config.set("mineskin-api-key", "");
            config.createSection("sprites");
            config.createSection("sequences");
            save();
        } else {
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        apiKey = config.getString("mineskin-api-key", "");

        boolean dirty = false;
        if (!config.contains("web.enabled")) {
            config.set("web.enabled", false);
            dirty = true;
        }
        if (!config.contains("web.port")) {
            config.set("web.port", 8765);
            dirty = true;
        }
        if (!config.contains("web.bind-address")) {
            config.set("web.bind-address", "0.0.0.0");
            dirty = true;
        }
        if (!config.contains("web.token")) {
            config.set("web.token", "change-me-" + Long.toHexString(System.nanoTime()));
            dirty = true;
        }
        if (dirty) {
            save();
        }

        webEnabled = config.getBoolean("web.enabled", false);
        webPort = config.getInt("web.port", 8765);
        webBind = config.getString("web.bind-address", "0.0.0.0");
        webToken = config.getString("web.token", "");

        ConfigurationSection section = config.getConfigurationSection("sprites");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String value = s.getString("value", "");
                String signature = s.getString("signature", "");
                String fallback = s.getString("fallback", "");
                if (value.isBlank() || signature.isBlank()) {
                    plugin.getLogger().warning("Sprite '" + key + "' is missing value/signature; skipping.");
                    continue;
                }
                sprites.put(key.toLowerCase(Locale.ROOT), new Sprite(key.toLowerCase(Locale.ROOT), value, signature, fallback));
            }
        }

        ConfigurationSection seqSection = config.getConfigurationSection("sequences");
        if (seqSection != null) {
            for (String key : seqSection.getKeys(false)) {
                List<String> heads = seqSection.getStringList(key);
                if (heads.isEmpty()) {
                    continue;
                }
                List<String> normalized = new ArrayList<>();
                for (String head : heads) {
                    normalized.add(head.toLowerCase(Locale.ROOT));
                }
                sequences.put(key.toLowerCase(Locale.ROOT), normalized);
            }
        }
        plugin.getLogger().info("Loaded " + sprites.size() + " head sprites and " + sequences.size() + " sequences from sprites.yml");
    }

    public void reload() {
        load();
    }

    private void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save sprites.yml: " + e.getMessage());
        }
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public int getWebPort() {
        return webPort;
    }

    public String getWebBind() {
        return webBind;
    }

    public String getWebToken() {
        return webToken;
    }

    public Sprite getSprite(String name) {
        return name == null ? null : sprites.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Sprite> getSprites() {
        return sprites.values();
    }

    public void putSprite(String name, String value, String signature, String fallback) {
        String key = name.toLowerCase(Locale.ROOT);
        config.set("sprites." + key + ".value", value);
        config.set("sprites." + key + ".signature", signature);
        config.set("sprites." + key + ".fallback", fallback == null ? "" : fallback);
        save();
        sprites.put(key, new Sprite(key, value, signature, fallback == null ? "" : fallback));
    }

    public List<String> getSequence(String name) {
        return name == null ? null : sequences.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, List<String>> getSequences() {
        return sequences;
    }

    public void putSequence(String name, List<String> heads) {
        String key = name.toLowerCase(Locale.ROOT);
        List<String> normalized = new ArrayList<>();
        for (String head : heads) {
            normalized.add(head.toLowerCase(Locale.ROOT));
        }
        config.set("sequences." + key, normalized);
        save();
        sequences.put(key, normalized);
    }

    public boolean removeSequence(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!sequences.containsKey(key)) {
            return false;
        }
        config.set("sequences." + key, null);
        save();
        sequences.remove(key);
        return true;
    }

    public Component buildHead(Sprite sprite) {
        PlayerHeadObjectContents contents = ObjectContents.playerHead()
                .profileProperty(PlayerHeadObjectContents.property("textures", sprite.value(), sprite.signature()))
                .hat(true)
                .build();
        return Component.object(contents);
    }

    public Component buildSequence(String name) {
        List<String> heads = getSequence(name);
        if (heads == null || heads.isEmpty()) {
            return null;
        }
        Component result = null;
        for (String head : heads) {
            Sprite sprite = getSprite(head);
            if (sprite == null) {
                continue;
            }
            Component glyph = buildHead(sprite);
            result = result == null ? glyph : result.append(glyph);
        }
        return result;
    }

    public record Sprite(String name, String value, String signature, String fallback) {
    }
}
