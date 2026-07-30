package paris.headsprites.sprite;

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
import java.util.concurrent.TimeUnit;

public final class MineSkinClient {
    private static final String BASE = "https://api.mineskin.org";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final MediaType PNG = MediaType.get("image/png");
    private static final String USER_AGENT = "HeadSprites/1.0";
    private static final int MAX_POLLS = 30;
    private static final long POLL_INTERVAL_MS = 2000L;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
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

    public Texture generate(String imageUrl, String name) throws MineSkinException {
        String jobId = queue(imageUrl, name);
        String skinUuid = pollUntilComplete(jobId);
        return fetchTexture(skinUuid);
    }

    public Texture generateFromDataUrl(String dataUrl, String name) throws MineSkinException {
        byte[] png = decodeImage(dataUrl);
        String jobId = queueFile(png, name);
        String skinUuid = pollUntilComplete(jobId);
        return fetchTexture(skinUuid);
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

    private String queue(String imageUrl, String name) throws MineSkinException {
        JsonObject body = new JsonObject();
        body.addProperty("url", imageUrl);
        body.addProperty("variant", "classic");
        body.addProperty("visibility", "unlisted");
        if (name != null && !name.isBlank()) {
            body.addProperty("name", name.length() > 20 ? name.substring(0, 20) : name);
        }
        JsonObject response = request(new Request.Builder()
                .url(BASE + "/v2/queue")
                .post(RequestBody.create(body.toString(), JSON)));
        return extractJobId(response);
    }

    private String queueFile(byte[] png, String name) throws MineSkinException {
        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("variant", "classic")
                .addFormDataPart("visibility", "unlisted")
                .addFormDataPart("file", "sprite.png", RequestBody.create(png, PNG));
        if (name != null && !name.isBlank()) {
            body.addFormDataPart("name", name.length() > 20 ? name.substring(0, 20) : name);
        }
        JsonObject response = request(new Request.Builder()
                .url(BASE + "/v2/queue")
                .post(body.build()));
        return extractJobId(response);
    }

    private String extractJobId(JsonObject response) throws MineSkinException {
        JsonObject job = response.getAsJsonObject("job");
        if (job == null || !job.has("id")) {
            throw new MineSkinException("MineSkin did not return a job id.");
        }
        return job.get("id").getAsString();
    }

    private String pollUntilComplete(String jobId) throws MineSkinException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonObject response = request(new Request.Builder().url(BASE + "/v2/queue/" + jobId).get());
            JsonObject job = response.has("job") ? response.getAsJsonObject("job") : response;
            String status = job.has("status") ? job.get("status").getAsString() : "unknown";
            if ("completed".equalsIgnoreCase(status)) {
                if (!job.has("result")) {
                    throw new MineSkinException("Job completed without a result.");
                }
                return job.get("result").getAsString();
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new MineSkinException("MineSkin job failed.");
            }
            sleep();
        }
        throw new MineSkinException("Timed out waiting for MineSkin to finish generating.");
    }

    private Texture fetchTexture(String skinUuid) throws MineSkinException {
        JsonObject response = request(new Request.Builder().url(BASE + "/v2/skins/" + skinUuid).get());
        JsonObject skin = response.has("skin") ? response.getAsJsonObject("skin") : response;
        JsonObject texture = skin.getAsJsonObject("texture");
        if (texture == null) {
            throw new MineSkinException("Skin response missing texture data.");
        }
        JsonObject data = texture.getAsJsonObject("data");
        if (data == null || !data.has("value") || !data.has("signature")) {
            throw new MineSkinException("Skin response missing value/signature.");
        }
        return new Texture(data.get("value").getAsString(), data.get("signature").getAsString());
    }

    private JsonObject request(Request.Builder builder) throws MineSkinException {
        builder.header("User-Agent", USER_AGENT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String raw = responseBody == null ? "" : responseBody.string();
            if (response.code() == 429) {
                throw new MineSkinException("Rate limited by MineSkin; try again shortly.");
            }
            if (raw.isBlank()) {
                throw new MineSkinException("Empty response from MineSkin (HTTP " + response.code() + ").");
            }
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            if (!response.isSuccessful()) {
                throw new MineSkinException("MineSkin error (HTTP " + response.code() + "): " + firstError(json));
            }
            return json;
        } catch (IOException e) {
            throw new MineSkinException("Network error talking to MineSkin: " + e.getMessage());
        }
    }

    private String firstError(JsonObject json) {
        if (json.has("errors") && json.getAsJsonArray("errors").size() > 0) {
            JsonObject first = json.getAsJsonArray("errors").get(0).getAsJsonObject();
            if (first.has("message")) {
                return first.get("message").getAsString();
            }
        }
        return "unknown error";
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
