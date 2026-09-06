package paris.headsprites.sprite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SpriteApiHandler {
    private static final int MAX_BODY_BYTES = 6 * 1024 * 1024;
    private static final String CONTENT_JSON = "application/json";

    private final JavaPlugin plugin;
    private final SpriteManager manager;
    private final Gson gson = new Gson();

    SpriteApiHandler(JavaPlugin plugin, SpriteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        switch (path) {
            case "/api/state" -> ok(ex, state());
            case "/api/generate" -> {
                if (post(ex, method)) generate(ex);
            }
            case "/api/sprite/delete" -> {
                if (post(ex, method)) deleteSprite(ex);
            }
            case "/api/sequence" -> {
                if (post(ex, method)) sequence(ex);
            }
            case "/api/sequence/delete" -> {
                if (post(ex, method)) deleteSequence(ex);
            }
            case "/api/animation" -> {
                if (post(ex, method)) animation(ex);
            }
            case "/api/animation/delete" -> {
                if (post(ex, method)) deleteAnimation(ex);
            }
            case "/api/reload" -> {
                if (post(ex, method)) {
                    manager.reload();
                    ok(ex, state());
                }
            }
            default -> error(ex, 404, "unknown endpoint");
        }
    }

    private boolean post(HttpExchange ex, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            error(ex, 405, "method not allowed");
            return false;
        }
        return true;
    }

    private String state() {
        JsonObject root = new JsonObject();
        root.addProperty("apiKeySet", manager.getApiKey() != null && !manager.getApiKey().isBlank());
        JsonArray spriteArr = new JsonArray();
        for (SpriteManager.Sprite s : manager.getSprites()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name());
            o.addProperty("fallback", s.fallback());
            spriteArr.add(o);
        }
        root.add("sprites", spriteArr);
        JsonObject seqObj = new JsonObject();
        manager.getSequences().forEach((name, heads) -> {
            JsonArray a = new JsonArray();
            heads.forEach(a::add);
            seqObj.add(name, a);
        });
        root.add("sequences", seqObj);
        JsonObject animObj = new JsonObject();
        manager.getAnimations().forEach((name, animation) -> {
            JsonObject o = new JsonObject();
            JsonArray frames = new JsonArray();
            for (List<String> row : animation.frames()) {
                JsonArray rowArr = new JsonArray();
                row.forEach(rowArr::add);
                frames.add(rowArr);
            }
            o.add("frames", frames);
            o.addProperty("intervalMs", animation.intervalMs());
            animObj.add(name, o);
        });
        root.add("animations", animObj);
        return gson.toJson(root);
    }

    private void generate(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String name = SpriteManager.normalizeKey(getString(body, "name"));
        String image = getString(body, "image");
        String fallback = getString(body, "fallback");
        if (name.isBlank() || image.isBlank()) {
            error(ex, 400, "name (letters, digits, _ or -) and image are required");
            return;
        }
        String apiKey = manager.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            error(ex, 400, "no MineSkin API key set in sprites.yml");
            return;
        }
        try {
            String skinData = ImageNormalizer.normalizeToSkinDataUrl(image);
            MineSkinClient client = new MineSkinClient(apiKey);
            MineSkinClient.Texture tex = client.generateFromDataUrl(skinData, name);
            if (!manager.putSprite(name, tex.value(), tex.signature(), fallback)) {
                error(ex, 502, "MineSkin returned an unusable texture");
                return;
            }
            ok(ex, state());
        } catch (MineSkinClient.MineSkinException | IOException e) {
            error(ex, 502, e.getMessage());
        }
    }

    private void deleteSprite(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        manager.removeSprite(getString(body, "name"));
        ok(ex, state());
    }

    private void sequence(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String name = getString(body, "name");
        List<String> heads = strings(body.get("heads"));
        if (name.isBlank() || heads == null) {
            error(ex, 400, "name and heads[] are required");
            return;
        }
        if (heads.isEmpty()) {
            error(ex, 400, "heads[] cannot be empty");
            return;
        }
        if (!manager.putSequence(name, heads)) {
            error(ex, 400, "invalid sequence name or heads");
            return;
        }
        ok(ex, state());
    }

    private void deleteSequence(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        manager.removeSequence(getString(body, "name"));
        ok(ex, state());
    }

    private void animation(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String name = getString(body, "name");
        JsonElement framesEl = body.get("frames");
        if (name.isBlank() || framesEl == null || !framesEl.isJsonArray()) {
            error(ex, 400, "name and frames[] are required");
            return;
        }
        List<List<String>> frames = new ArrayList<>();
        for (JsonElement el : framesEl.getAsJsonArray()) {
            List<String> row;
            if (el.isJsonArray()) {
                row = strings(el);
            } else {
                row = new ArrayList<>();
                String single = asString(el);
                if (!single.isBlank()) {
                    row.add(single);
                }
            }
            if (row != null && !row.isEmpty()) {
                frames.add(row);
            }
        }
        if (frames.isEmpty()) {
            error(ex, 400, "frames[] cannot be empty");
            return;
        }
        long intervalMs = asLong(body.get("intervalMs"), 100L);
        if (!manager.putAnimation(name, frames, intervalMs)) {
            error(ex, 400, "invalid animation name or frames");
            return;
        }
        ok(ex, state());
    }

    private void deleteAnimation(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null) {
            return;
        }
        manager.removeAnimation(getString(body, "name"));
        ok(ex, state());
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            error(ex, 413, "request body too large");
            return null;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) {
                error(ex, 400, "body must be a JSON object");
                return null;
            }
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            error(ex, 400, "malformed JSON body");
            return null;
        }
    }

    private static String getString(JsonObject o, String key) {
        return o == null ? "" : asString(o.get(key));
    }

    private static String asString(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return "";
        }
        return el.getAsString();
    }

    private static long asLong(JsonElement el, long fallback) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            try {
                return (long) Double.parseDouble(el.getAsString().trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    private static List<String> strings(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            String value = asString(item);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    private void ok(HttpExchange ex, String json) {
        SpriteWebServer.send(ex, 200, CONTENT_JSON, json);
    }

    private void error(HttpExchange ex, int code, String message) {
        SpriteWebServer.send(ex, code, CONTENT_JSON, "{\"error\":\"" + jsonEscape(message) + "\"}");
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
