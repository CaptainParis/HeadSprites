package paris.headsprites.sprite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SpriteWebServer {
    private static final int WORKER_THREADS = 4;
    private static final int MAX_STATIC_CACHE = 16;

    private final JavaPlugin plugin;
    private final SpriteManager manager;
    private final SpriteApiHandler api;
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    public SpriteWebServer(JavaPlugin plugin, SpriteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.api = new SpriteApiHandler(plugin, manager);
    }

    public void start() {
        try {
            AtomicInteger counter = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "HeadSprites-web-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(manager.getWebBind(), manager.getWebPort()), 0);
            created.setExecutor(pool);
            created.createContext("/api/", this::handleApi);
            created.createContext("/", this::handleStatic);
            created.start();
            executor = pool;
            server = created;
            plugin.getLogger().info("Sprite web UI listening on "
                    + manager.getWebBind() + ":" + manager.getWebPort());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start sprite web server on "
                    + manager.getWebBind() + ":" + manager.getWebPort() + ": " + e.getMessage());
            stop();
        }
    }

    public void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            try {
                current.stop(1);
            } catch (Exception e) {
                plugin.getLogger().warning("Error stopping sprite web server: " + e.getMessage());
            }
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean authorized(HttpExchange ex) {
        String expected = manager.getWebToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String header = ex.getRequestHeaders().getFirst("X-Sprite-Token");
        if (header != null && tokenMatches(expected, header)) {
            return true;
        }
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "token".equals(pair.substring(0, eq))
                        && tokenMatches(expected, urlDecode(pair.substring(eq + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tokenMatches(String expected, String given) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    private void handleApi(HttpExchange ex) {
        try {
            if (!authorized(ex)) {
                send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            api.handle(ex);
        } catch (Exception e) {
            plugin.getLogger().warning("Sprite web API error on " + ex.getRequestURI().getPath()
                    + ": " + e.getMessage());
            send(ex, 500, "application/json",
                    "{\"error\":\"" + SpriteApiHandler.jsonEscape(String.valueOf(e.getMessage())) + "\"}");
        } finally {
            ex.close();
        }
    }

    private void handleStatic(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("..") || path.endsWith("/")) {
                send(ex, 400, "text/plain", "bad path");
                return;
            }
            byte[] body = staticResource(path);
            if (body == null) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } catch (Exception e) {
            plugin.getLogger().warning("Sprite web static error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private byte[] staticResource(String path) throws IOException {
        byte[] cached = staticCache.get(path);
        if (cached != null) {
            return cached;
        }
        String resource = "spriteweb" + path;
        try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            byte[] body = in.readAllBytes();
            if (staticCache.size() < MAX_STATIC_CACHE) {
                staticCache.put(path, body);
            }
            return body;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    static void send(HttpExchange ex, int code, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
        }
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
