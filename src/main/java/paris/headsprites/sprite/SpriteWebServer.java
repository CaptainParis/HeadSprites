package paris.headsprites.sprite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class SpriteWebServer {
    private final JavaPlugin plugin;
    private final SpriteManager manager;
    private final SpriteApiHandler api;
    private HttpServer server;

    public SpriteWebServer(JavaPlugin plugin, SpriteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.api = new SpriteApiHandler(plugin, manager);
    }

    public void start() {
        try {
            server = HttpServer.create(
                    new InetSocketAddress(manager.getWebBind(), manager.getWebPort()), 0);
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.createContext("/api/", this::handleApi);
            server.createContext("/", this::handleStatic);
            server.start();
            plugin.getLogger().info("Sprite web UI listening on "
                    + manager.getWebBind() + ":" + manager.getWebPort());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start sprite web server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private boolean authorized(HttpExchange ex) {
        String expected = manager.getWebToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String header = ex.getRequestHeaders().getFirst("X-Sprite-Token");
        if (expected.equals(header)) {
            return true;
        }
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "token".equals(pair.substring(0, eq))
                        && expected.equals(urlDecode(pair.substring(eq + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            api.handle(ex);
        } catch (Exception e) {
            plugin.getLogger().warning("Sprite web API error: " + e.getMessage());
            send(ex, 500, "application/json",
                    "{\"error\":\"" + SpriteApiHandler.jsonEscape(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "bad path");
            return;
        }
        String resource = "spriteweb" + path;
        try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
