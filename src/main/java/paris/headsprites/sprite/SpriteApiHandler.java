package paris.headsprites.sprite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SpriteApiHandler {
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
            case "/api/sequence" -> {
                if (post(ex, method)) sequence(ex);
            }
            case "/api/sequence/delete" -> {
                if (post(ex, method)) deleteSequence(ex);
            }
            case "/api/reload" -> {
                if (post(ex, method)) {
                    manager.reload();
                    ok(ex, state());
                }
            }
            default -> SpriteWebServer.send(ex, 404, "application/json", "{\"error\":\"unknown endpoint\"}");
        }
    }

    private boolean post(HttpExchange ex, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            SpriteWebServer.send(ex, 405, "application/json", "{\"error\":\"method not allowed\"}");
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
        return gson.toJson(root);
    }

    private void generate(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        String name = getString(body, "name");
        String image = getString(body, "image");
        String fallback = body.has("fallback") ? body.get("fallback").getAsString() : "";
        if (name.isBlank() || image.isBlank()) {
            SpriteWebServer.send(ex, 400, "application/json", "{\"error\":\"name and image are required\"}");
            return;
        }
        String apiKey = manager.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            SpriteWebServer.send(ex, 400, "application/json", "{\"error\":\"no MineSkin API key set in sprites.yml\"}");
            return;
        }
        try {
            String skinData = ImageNormalizer.normalizeToSkinDataUrl(image);
            MineSkinClient client = new MineSkinClient(apiKey);
            MineSkinClient.Texture tex = client.generateFromDataUrl(skinData, name);
            manager.putSprite(name, tex.value(), tex.signature(), fallback);
            ok(ex, state());
        } catch (MineSkinClient.MineSkinException | IOException e) {
            SpriteWebServer.send(ex, 502, "application/json",
                    "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void sequence(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        String name = getString(body, "name");
        if (name.isBlank() || !body.has("heads") || !body.get("heads").isJsonArray()) {
            SpriteWebServer.send(ex, 400, "application/json", "{\"error\":\"name and heads[] are required\"}");
            return;
        }
        List<String> heads = new ArrayList<>();
        for (JsonElement el : body.getAsJsonArray("heads")) {
            heads.add(el.getAsString());
        }
        if (heads.isEmpty()) {
            SpriteWebServer.send(ex, 400, "application/json", "{\"error\":\"heads[] cannot be empty\"}");
            return;
        }
        manager.putSequence(name, heads);
        ok(ex, state());
    }

    private void deleteSequence(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        manager.removeSequence(getString(body, "name"));
        ok(ex, state());
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement el = JsonParser.parseString(raw.isBlank() ? "{}" : raw);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private String getString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private void ok(HttpExchange ex, String json) throws IOException {
        SpriteWebServer.send(ex, 200, "application/json", json);
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
