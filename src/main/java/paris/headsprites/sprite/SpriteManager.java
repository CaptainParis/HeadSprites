package paris.headsprites.sprite;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class SpriteManager {
    private static final long DEFAULT_INTERVAL_MS = 100L;
    private static final long MIN_INTERVAL_MS = 50L;
    private static final int MAX_KEY_LENGTH = 48;
    private static final int MAX_HASH_CACHE = 256;
    private static final Pattern KEY_STRIP = Pattern.compile("[^a-z0-9_\\-]");
    private static final Map<String, String> HASH_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_HASH_CACHE;
                }
            });

    private static volatile SpriteManager instance;

    private final JavaPlugin plugin;
    private final Object lock = new Object();
    private final ExecutorService ioExecutor;

    private volatile Map<String, Sprite> sprites = Map.of();
    private volatile Map<String, List<String>> sequences = Map.of();
    private volatile Map<String, Animation> animations = Map.of();
    private volatile Map<String, Component> sequenceComponents = Map.of();
    private volatile Map<String, List<Component>> animationFrames = Map.of();

    private FileConfiguration config;
    private File configFile;
    private volatile String apiKey = "";
    private volatile boolean webEnabled;
    private volatile int webPort;
    private volatile String webBind = "0.0.0.0";
    private volatile String webToken = "";

    private SpriteManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HeadSprites-io");
            thread.setDaemon(true);
            return thread;
        });
        load();
    }

    public static void initialize(JavaPlugin plugin) {
        SpriteManager previous = instance;
        if (previous != null) {
            previous.shutdown();
        }
        instance = new SpriteManager(plugin);
    }

    public static SpriteManager getInstance() {
        return instance;
    }

    public static void shutdownInstance() {
        SpriteManager current = instance;
        instance = null;
        if (current != null) {
            current.shutdown();
        }
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("sprites.yml writer did not finish within 5s; forcing shutdown.");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void load() {
        int spriteCount;
        int sequenceCount;
        int animationCount;
        synchronized (lock) {
            File dataFolder = plugin.getDataFolder();
            configFile = new File(dataFolder, "sprites.yml");
            FileConfiguration loaded;
            boolean dirty = false;
            if (!configFile.exists()) {
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    plugin.getLogger().severe("Failed to create plugin data folder; sprites will not persist.");
                }
                loaded = new YamlConfiguration();
                loaded.set("mineskin-api-key", "");
                loaded.createSection("sprites");
                loaded.createSection("sequences");
                loaded.createSection("animations");
                dirty = true;
            } else {
                loaded = YamlConfiguration.loadConfiguration(configFile);
            }

            if (!loaded.contains("web.enabled")) {
                loaded.set("web.enabled", false);
                dirty = true;
            }
            if (!loaded.contains("web.port")) {
                loaded.set("web.port", 8765);
                dirty = true;
            }
            if (!loaded.contains("web.bind-address")) {
                loaded.set("web.bind-address", "0.0.0.0");
                dirty = true;
            }
            if (!loaded.contains("web.token")) {
                loaded.set("web.token", randomToken());
                dirty = true;
            }

            config = loaded;
            apiKey = loaded.getString("mineskin-api-key", "");
            webEnabled = loaded.getBoolean("web.enabled", false);
            webPort = loaded.getInt("web.port", 8765);
            webBind = loaded.getString("web.bind-address", "0.0.0.0");
            webToken = loaded.getString("web.token", "");

            Map<String, Sprite> newSprites = new LinkedHashMap<>();
            ConfigurationSection section = loaded.getConfigurationSection("sprites");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
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
                        String name = key.toLowerCase(Locale.ROOT);
                        newSprites.put(name, Sprite.of(name, value, signature, fallback));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Sprite '" + key + "' is malformed (" + e.getMessage() + "); skipping.");
                    }
                }
            }

            Map<String, List<String>> newSequences = new LinkedHashMap<>();
            ConfigurationSection seqSection = loaded.getConfigurationSection("sequences");
            if (seqSection != null) {
                for (String key : seqSection.getKeys(false)) {
                    try {
                        List<String> normalized = normalizeHeads(seqSection.getStringList(key));
                        if (normalized.isEmpty()) {
                            continue;
                        }
                        newSequences.put(key.toLowerCase(Locale.ROOT), normalized);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Sequence '" + key + "' is malformed (" + e.getMessage() + "); skipping.");
                    }
                }
            }

            Map<String, Animation> newAnimations = new LinkedHashMap<>();
            ConfigurationSection animSection = loaded.getConfigurationSection("animations");
            if (animSection != null) {
                for (String key : animSection.getKeys(false)) {
                    try {
                        ConfigurationSection a = animSection.getConfigurationSection(key);
                        if (a == null) {
                            continue;
                        }
                        List<List<String>> frames = new ArrayList<>();
                        List<?> rawFrames = a.getList("frames");
                        if (rawFrames != null) {
                            for (Object entry : rawFrames) {
                                List<String> row = new ArrayList<>();
                                if (entry instanceof List<?> list) {
                                    for (Object head : list) {
                                        if (head != null) {
                                            row.add(String.valueOf(head).toLowerCase(Locale.ROOT));
                                        }
                                    }
                                } else if (entry != null) {
                                    row.add(String.valueOf(entry).toLowerCase(Locale.ROOT));
                                }
                                if (!row.isEmpty()) {
                                    frames.add(Collections.unmodifiableList(row));
                                }
                            }
                        }
                        if (frames.isEmpty()) {
                            continue;
                        }
                        long intervalMs = clampInterval(a.getLong("interval-ms", DEFAULT_INTERVAL_MS));
                        String animKey = key.toLowerCase(Locale.ROOT);
                        newAnimations.put(animKey, new Animation(animKey, Collections.unmodifiableList(frames), intervalMs));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Animation '" + key + "' is malformed (" + e.getMessage() + "); skipping.");
                    }
                }
            }

            sprites = Collections.unmodifiableMap(newSprites);
            sequences = Collections.unmodifiableMap(newSequences);
            animations = Collections.unmodifiableMap(newAnimations);
            rebuildDerived();
            if (dirty) {
                persist();
            }
            spriteCount = newSprites.size();
            sequenceCount = newSequences.size();
            animationCount = newAnimations.size();
        }
        plugin.getLogger().info("Loaded " + spriteCount + " head sprites, " + sequenceCount
                + " sequences and " + animationCount + " animations from sprites.yml");
    }

    public void reload() {
        load();
    }

    public CompletableFuture<Void> reloadAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                try {
                    load();
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void persist() {
        String yaml;
        try {
            yaml = config.saveToString();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to serialize sprites.yml: " + e.getMessage());
            return;
        }
        File target = configFile;
        try {
            ioExecutor.execute(() -> writeFile(target, yaml));
        } catch (RejectedExecutionException e) {
            writeFile(target, yaml);
        }
    }

    private void writeFile(File target, String yaml) {
        try {
            Path path = target.toPath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(target.getName() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save sprites.yml: " + e.getMessage());
        }
    }

    private void rebuildDerived() {
        Map<String, Sprite> spriteMap = sprites;
        Map<String, Component> seqComponents = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : sequences.entrySet()) {
            Component chained = chainHeads(entry.getValue(), spriteMap);
            if (chained != null) {
                seqComponents.put(entry.getKey(), chained);
            }
        }
        Map<String, List<Component>> frameComponents = new LinkedHashMap<>();
        for (Map.Entry<String, Animation> entry : animations.entrySet()) {
            List<Component> rows = new ArrayList<>(entry.getValue().frames().size());
            for (List<String> row : entry.getValue().frames()) {
                rows.add(chainHeads(row, spriteMap));
            }
            frameComponents.put(entry.getKey(), Collections.unmodifiableList(rows));
        }
        sequenceComponents = Collections.unmodifiableMap(seqComponents);
        animationFrames = Collections.unmodifiableMap(frameComponents);
    }

    private static Component chainHeads(List<String> heads, Map<String, Sprite> spriteMap) {
        Component result = Component.empty();
        int added = 0;
        for (String head : heads) {
            Sprite sprite = spriteMap.get(head);
            if (sprite == null) {
                continue;
            }
            result = result.append(sprite.head());
            added++;
        }
        return added == 0 ? null : result;
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String key = KEY_STRIP.matcher(normalizeLookup(raw)).replaceAll("");
        if (key.length() > MAX_KEY_LENGTH) {
            key = key.substring(0, MAX_KEY_LENGTH);
        }
        return key;
    }

    public static String normalizeLookup(String name) {
        if (name == null) {
            return "";
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.length() >= 2 && key.charAt(0) == '"' && key.charAt(key.length() - 1) == '"') {
            key = key.substring(1, key.length() - 1).trim();
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    private static List<String> normalizeHeads(Collection<String> heads) {
        List<String> normalized = new ArrayList<>();
        if (heads == null) {
            return normalized;
        }
        for (String head : heads) {
            if (head == null) {
                continue;
            }
            String key = normalizeLookup(head);
            if (!key.isBlank()) {
                normalized.add(key);
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    private static long clampInterval(long intervalMs) {
        if (intervalMs <= 0) {
            return DEFAULT_INTERVAL_MS;
        }
        return Math.max(MIN_INTERVAL_MS, intervalMs);
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
        if (name == null) {
            return null;
        }
        return sprites.get(normalizeLookup(name));
    }

    public Collection<Sprite> getSprites() {
        return sprites.values();
    }

    public List<String> getSequence(String name) {
        return name == null ? null : sequences.get(normalizeLookup(name));
    }

    public Map<String, List<String>> getSequences() {
        return sequences;
    }

    public Animation getAnimation(String name) {
        return name == null ? null : animations.get(normalizeLookup(name));
    }

    public Map<String, Animation> getAnimations() {
        return animations;
    }

    public boolean putSprite(String name, String value, String signature, String fallback) {
        String key = normalizeKey(name);
        if (key.isBlank() || value == null || value.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        String safeFallback = fallback == null ? "" : fallback;
        Sprite sprite = Sprite.of(key, value, signature, safeFallback);
        synchronized (lock) {
            config.set("sprites." + key + ".value", value);
            config.set("sprites." + key + ".signature", signature);
            config.set("sprites." + key + ".fallback", safeFallback);
            Map<String, Sprite> copy = new LinkedHashMap<>(sprites);
            copy.put(key, sprite);
            sprites = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public boolean removeSprite(String name) {
        String key = normalizeLookup(name);
        synchronized (lock) {
            if (!sprites.containsKey(key)) {
                return false;
            }
            config.set("sprites." + key, null);
            Map<String, Sprite> copy = new LinkedHashMap<>(sprites);
            copy.remove(key);
            sprites = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public boolean putSequence(String name, List<String> heads) {
        String key = normalizeKey(name);
        List<String> normalized = normalizeHeads(heads);
        if (key.isBlank() || normalized.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            config.set("sequences." + key, new ArrayList<>(normalized));
            Map<String, List<String>> copy = new LinkedHashMap<>(sequences);
            copy.put(key, normalized);
            sequences = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public boolean removeSequence(String name) {
        String key = normalizeLookup(name);
        synchronized (lock) {
            if (!sequences.containsKey(key)) {
                return false;
            }
            config.set("sequences." + key, null);
            Map<String, List<String>> copy = new LinkedHashMap<>(sequences);
            copy.remove(key);
            sequences = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public boolean putAnimation(String name, List<List<String>> frames, long intervalMs) {
        String key = normalizeKey(name);
        if (key.isBlank() || frames == null) {
            return false;
        }
        List<List<String>> normalized = new ArrayList<>();
        List<List<String>> yamlFrames = new ArrayList<>();
        for (List<String> frame : frames) {
            List<String> row = normalizeHeads(frame);
            if (!row.isEmpty()) {
                normalized.add(row);
                yamlFrames.add(new ArrayList<>(row));
            }
        }
        if (normalized.isEmpty()) {
            return false;
        }
        long interval = clampInterval(intervalMs);
        Animation animation = new Animation(key, Collections.unmodifiableList(normalized), interval);
        synchronized (lock) {
            config.set("animations." + key + ".frames", yamlFrames);
            config.set("animations." + key + ".interval-ms", interval);
            Map<String, Animation> copy = new LinkedHashMap<>(animations);
            copy.put(key, animation);
            animations = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public boolean removeAnimation(String name) {
        String key = normalizeLookup(name);
        synchronized (lock) {
            if (!animations.containsKey(key)) {
                return false;
            }
            config.set("animations." + key, null);
            Map<String, Animation> copy = new LinkedHashMap<>(animations);
            copy.remove(key);
            animations = Collections.unmodifiableMap(copy);
            rebuildDerived();
            persist();
        }
        return true;
    }

    public int currentFrameIndex(Animation animation) {
        int count = animation == null ? 0 : animation.frames().size();
        if (count <= 0) {
            return 0;
        }
        long interval = clampInterval(animation.intervalMs());
        return (int) ((System.currentTimeMillis() / interval) % count);
    }

    public String textureHash(Sprite sprite) {
        return sprite == null ? null : sprite.textureHash();
    }

    public static String textureHashFromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cached = HASH_CACHE.get(value);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String hash = computeTextureHash(value);
        HASH_CACHE.put(value, hash == null ? "" : hash);
        return hash;
    }

    private static String computeTextureHash(String value) {
        JsonObject textures = decodeTextures(value);
        if (textures == null) {
            return null;
        }
        try {
            String url = textures.getAsJsonObject("textures")
                    .getAsJsonObject("SKIN")
                    .get("url")
                    .getAsString();
            int slash = url.lastIndexOf('/');
            String hash = slash >= 0 ? url.substring(slash + 1) : url;
            return hash == null || hash.isBlank() ? null : hash;
        } catch (Exception ignored) {
            return null;
        }
    }

    public String tabHeadTag(Sprite sprite) {
        if (sprite == null) {
            return "";
        }
        String hash = sprite.textureHash();
        if (hash != null) {
            return SpriteTags.tabTextureMini(hash);
        }
        if (sprite.fallback() != null && !sprite.fallback().isBlank()) {
            return sprite.fallback();
        }
        return "";
    }

    public Component buildHead(Sprite sprite) {
        return sprite == null ? null : sprite.head();
    }

    private static JsonObject decodeTextures(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static UUID profileId(JsonObject textures) {
        if (textures == null || !textures.has("profileId")) {
            return null;
        }
        try {
            return parseUndashedUuid(textures.get("profileId").getAsString().replace("-", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    static UUID parseUndashedUuid(String raw) {
        if (raw == null || raw.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                    + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Component buildSequence(String name) {
        return name == null ? null : sequenceComponents.get(normalizeLookup(name));
    }

    public Component buildAnimationFrame(Animation animation, int index) {
        if (animation == null || animation.frames().isEmpty()) {
            return null;
        }
        List<Component> rows = animationFrames.get(animation.name());
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        int count = rows.size();
        int wrapped = ((index % count) + count) % count;
        return rows.get(wrapped);
    }

    public Component buildAnimation(String name) {
        Animation animation = getAnimation(name);
        if (animation == null) {
            return null;
        }
        return buildAnimationFrame(animation, currentFrameIndex(animation));
    }

    public Component buildAnimationFirstFrame(String name) {
        Animation animation = getAnimation(name);
        if (animation == null) {
            return null;
        }
        return buildAnimationFrame(animation, 0);
    }

    public record Sprite(String name, String value, String signature, String fallback,
                         String textureHash, Component head) {
        public static Sprite of(String name, String value, String signature, String fallback) {
            String safeFallback = fallback == null ? "" : fallback;
            JsonObject textures = decodeTextures(value);
            UUID id = profileId(textures);
            if (id == null) {
                id = UUID.nameUUIDFromBytes(("headsprites:" + name).getBytes(StandardCharsets.UTF_8));
            }
            PlayerHeadObjectContents contents = ObjectContents.playerHead()
                    .id(id)
                    .profileProperty(PlayerHeadObjectContents.property("textures", value, signature))
                    .hat(false)
                    .build();
            return new Sprite(name, value, signature, safeFallback,
                    textureHashFromValue(value), Component.object(contents));
        }
    }

    public record Animation(String name, List<List<String>> frames, long intervalMs) {
    }
}
