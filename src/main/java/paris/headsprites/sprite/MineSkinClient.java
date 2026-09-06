package paris.headsprites.sprite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MineSkinClient {
    private static final String BASE = "https://api.mineskin.org";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final MediaType PNG = MediaType.get("image/png");
    private static final String USER_AGENT = "HeadSprites/1.4";
    private static final int MAX_POLLS = 40;
    private static final long POLL_INTERVAL_MS = 1500L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 4000L;
    private static final int POOL_THREADS = 2;
    private static final int POOL_QUEUE = 4;

    private static final Object LIFECYCLE = new Object();
    private static OkHttpClient http;
    private static ExecutorService pool;

    private final String apiKey;

    public MineSkinClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public record Texture(String value, String signature) {
    }

    public static final class MineSkinException extends Exception {
        public MineSkinException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface TextureAttempt {
        Texture run() throws MineSkinException;
    }

    private static OkHttpClient http() {
        synchronized (LIFECYCLE) {
            if (http == null || http.dispatcher().executorService().isShutdown()) {
                http = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build();
            }
            return http;
        }
    }

    private static ExecutorService pool() {
        synchronized (LIFECYCLE) {
            if (pool == null || pool.isShutdown()) {
                AtomicInteger counter = new AtomicInteger();
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                        POOL_THREADS, POOL_THREADS, 30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(POOL_QUEUE),
                        runnable -> {
                            Thread thread = new Thread(runnable, "HeadSprites-mineskin-" + counter.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
                executor.allowCoreThreadTimeOut(true);
                pool = executor;
            }
            return pool;
        }
    }

    public static void shutdown() {
        ExecutorService currentPool;
        OkHttpClient currentHttp;
        synchronized (LIFECYCLE) {
            currentPool = pool;
            currentHttp = http;
            pool = null;
            http = null;
        }
        if (currentPool != null) {
            currentPool.shutdownNow();
            try {
                currentPool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (currentHttp != null) {
            currentHttp.dispatcher().executorService().shutdown();
            currentHttp.connectionPool().evictAll();
        }
    }

    public CompletableFuture<Texture> generateAsync(String imageUrl, String name) {
        CompletableFuture<Texture> future = new CompletableFuture<>();
        try {
            pool().execute(() -> {
                try {
                    future.complete(generate(imageUrl, name));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(
                    new MineSkinException("Too many MineSkin generations in progress; try again shortly."));
        }
        return future;
    }

    public Texture generate(String imageUrl, String name) throws MineSkinException {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new MineSkinException("Empty image URL.");
        }
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new MineSkinException("Image URL must start with http:// or https://.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("url", imageUrl);
        body.addProperty("variant", "classic");
        body.addProperty("visibility", "unlisted");
        if (name != null && !name.isBlank()) {
            body.addProperty("name", name.length() > 20 ? name.substring(0, 20) : name);
        }
        return withRetry(() -> {
            JsonObject response = request(new Request.Builder()
                    .url(BASE + "/v2/queue")
                    .post(RequestBody.create(body.toString(), JSON)));
            return resolveQueueResponse(response);
        });
    }

    public Texture generateFromDataUrl(String dataUrl, String name) throws MineSkinException {
        return generateUpload(decodeImage(dataUrl), name);
    }

    public Texture generateUpload(byte[] png, String name) throws MineSkinException {
        if (png == null || png.length == 0) {
            throw new MineSkinException("Empty PNG.");
        }
        return withRetry(() -> {
            MultipartBody.Builder form = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "sprite.png", RequestBody.create(png, PNG))
                    .addFormDataPart("variant", "classic")
                    .addFormDataPart("visibility", "unlisted");
            if (name != null && !name.isBlank()) {
                form.addFormDataPart("name", name.length() > 20 ? name.substring(0, 20) : name);
            }
            JsonObject response = request(new Request.Builder()
                    .url(BASE + "/v2/queue")
                    .post(form.build()));
            return resolveQueueResponse(response);
        });
    }

    private static byte[] decodeImage(String dataUrl) throws MineSkinException {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new MineSkinException("Empty image data.");
        }
        String base64 = dataUrl;
        int comma = dataUrl.indexOf(',');
        if (dataUrl.startsWith("data:") && comma >= 0) {
            base64 = dataUrl.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new MineSkinException("Invalid base64 image data.");
        }
    }

    private Texture withRetry(TextureAttempt attempt) throws MineSkinException {
        MineSkinException last = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try {
                return attempt.run();
            } catch (MineSkinException e) {
                last = e;
                if (i == MAX_ATTEMPTS - 1 || !retryable(e.getMessage())) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static boolean retryable(String message) {
        if (message == null) {
            return false;
        }
        String low = message.toLowerCase(Locale.ROOT);
        return low.contains("timeout") || low.contains("skin_change")
                || low.contains("failed_to_create_id") || low.contains("no_account_available");
    }

    private Texture resolveQueueResponse(JsonObject response) throws MineSkinException {
        if (response.has("skin") && response.get("skin").isJsonObject()) {
            return textureFromSkin(response.getAsJsonObject("skin"));
        }
        String jobId = extractJobId(response);
        String skinUuid = pollUntilComplete(jobId);
        return fetchTexture(skinUuid);
    }

    private String extractJobId(JsonObject response) throws MineSkinException {
        JsonElement jobEl = response.get("job");
        if (jobEl == null || !jobEl.isJsonObject() || !jobEl.getAsJsonObject().has("id")) {
            throw new MineSkinException("MineSkin did not return a job id.");
        }
        return jobEl.getAsJsonObject().get("id").getAsString();
    }

    private String pollUntilComplete(String jobId) throws MineSkinException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonObject response = request(new Request.Builder().url(BASE + "/v2/queue/" + jobId).get());
            JsonObject job = response.has("job") && response.get("job").isJsonObject()
                    ? response.getAsJsonObject("job") : response;
            String status = job.has("status") ? job.get("status").getAsString() : "unknown";
            if ("completed".equalsIgnoreCase(status)) {
                if (!job.has("result")) {
                    throw new MineSkinException("Job completed without a result.");
                }
                return job.get("result").getAsString();
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new MineSkinException("MineSkin job failed: " + firstError(response));
            }
            sleep();
        }
        throw new MineSkinException("Timed out waiting for MineSkin to finish generating.");
    }

    private Texture fetchTexture(String skinUuid) throws MineSkinException {
        JsonObject response = request(new Request.Builder().url(BASE + "/v2/skins/" + skinUuid).get());
        JsonObject skin = response.has("skin") && response.get("skin").isJsonObject()
                ? response.getAsJsonObject("skin") : response;
        return textureFromSkin(skin);
    }

    private Texture textureFromSkin(JsonObject skin) throws MineSkinException {
        JsonElement textureEl = skin.get("texture");
        if (textureEl == null || !textureEl.isJsonObject()) {
            throw new MineSkinException("Skin response missing texture data.");
        }
        JsonElement dataEl = textureEl.getAsJsonObject().get("data");
        if (dataEl == null || !dataEl.isJsonObject()) {
            throw new MineSkinException("Skin response missing value/signature.");
        }
        JsonObject data = dataEl.getAsJsonObject();
        if (!data.has("value") || !data.has("signature")) {
            throw new MineSkinException("Skin response missing value/signature.");
        }
        return new Texture(data.get("value").getAsString(), data.get("signature").getAsString());
    }

    private JsonObject request(Request.Builder builder) throws MineSkinException {
        builder.header("User-Agent", USER_AGENT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = http().newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String raw = responseBody == null ? "" : responseBody.string();
            if (response.code() == 429) {
                throw new MineSkinException("Rate limited by MineSkin; try again shortly.");
            }
            if (raw.isBlank()) {
                throw new MineSkinException("Empty response from MineSkin (HTTP " + response.code() + ").");
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(raw);
            } catch (RuntimeException e) {
                throw new MineSkinException("Unreadable response from MineSkin (HTTP " + response.code() + ").");
            }
            if (!parsed.isJsonObject()) {
                throw new MineSkinException("Unexpected response from MineSkin (HTTP " + response.code() + ").");
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!response.isSuccessful()) {
                throw new MineSkinException("MineSkin error (HTTP " + response.code() + "): " + firstError(json));
            }
            return json;
        } catch (IOException e) {
            throw new MineSkinException("Network error talking to MineSkin: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new MineSkinException("Unexpected MineSkin client error: " + e.getMessage());
        }
    }

    private String firstError(JsonObject json) {
        String top = firstErrorMessage(json);
        if (top != null) {
            return top;
        }
        if (json.has("job") && json.get("job").isJsonObject()) {
            String nested = firstErrorMessage(json.getAsJsonObject("job"));
            if (nested != null) {
                return nested;
            }
        }
        return "unknown error";
    }

    private static String firstErrorMessage(JsonObject json) {
        try {
            if (json.has("errors") && json.get("errors").isJsonArray()
                    && json.getAsJsonArray("errors").size() > 0) {
                JsonElement first = json.getAsJsonArray("errors").get(0);
                if (first.isJsonObject() && first.getAsJsonObject().has("message")) {
                    return first.getAsJsonObject().get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void sleep() throws MineSkinException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineSkinException("Interrupted while waiting for MineSkin.");
        }
    }
}
