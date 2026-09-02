package com.maowu.gravitypet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/** Uploads one detected utterance to Xiaomi MiMo-V2.5-ASR. */
final class MimoAsrClient {
    interface Callback {
        void onSuccess(String text);
        void onError(String message);
    }

    private static final String ENDPOINT =
            "https://api.xiaomimimo.com/v1/chat/completions";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MimoAsrClient() {}

    static RequestHandle transcribe(Context context, byte[] wavData,
                                    ExecutorService executor, Callback callback) {
        Context appContext = context.getApplicationContext();
        RequestHandle requestHandle = new RequestHandle();
        requestHandle.attach(executor.submit(() -> {
            HttpURLConnection connection = null;
            try {
                if (requestHandle.isCancelled()) return;
                String apiKey = ApiKeyStore.load(appContext, "mimo");
                if (apiKey.isEmpty()) {
                    throw new IllegalStateException("请先在 AI 设置中填写小米 MiMo 语音 Key");
                }

                String audioData = "data:audio/wav;base64,"
                        + Base64.encodeToString(wavData, Base64.NO_WRAP);
                JSONObject inputAudio = new JSONObject()
                        .put("data", audioData)
                        .put("format", "wav");
                JSONObject audioPart = new JSONObject()
                        .put("type", "input_audio")
                        .put("input_audio", inputAudio);
                JSONObject userMessage = new JSONObject()
                        .put("role", "user")
                        .put("content", new JSONArray().put(audioPart));
                JSONObject request = new JSONObject()
                        .put("model", "mimo-v2.5-asr")
                        .put("messages", new JSONArray().put(userMessage))
                        .put("asr_options", new JSONObject().put("language", "zh"))
                        .put("stream", false);

                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                requestHandle.track(connection);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(60_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream, 1_000_000);
                if (code < 200 || code >= 300) {
                    String detail = response;
                    try {
                        JSONObject error = new JSONObject(response).optJSONObject("error");
                        if (error != null) detail = error.optString("message", response);
                    } catch (Exception ignored) {}
                    throw new IllegalStateException("语音接口返回 " + code + "："
                            + shorten(detail, 160));
                }

                String text = new JSONObject(response).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message")
                        .optString("content", "").trim();
                if (text.isEmpty()) throw new IllegalStateException("没有识别到文字");
                String result = text;
                if (!requestHandle.isCancelled()) {
                    MAIN.post(() -> {
                        if (!requestHandle.isCancelled()) callback.onSuccess(result);
                    });
                }
            } catch (Exception error) {
                if (requestHandle.isCancelled()) return;
                String message = error.getMessage() == null ? "语音识别失败" : error.getMessage();
                MAIN.post(() -> {
                    if (!requestHandle.isCancelled()) callback.onError(message);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                    requestHandle.clear(connection);
                }
            }
        }));
        return requestHandle;
    }

    private static String readAll(InputStream stream, int maxChars) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.length() + line.length() > maxChars) {
                    throw new IllegalStateException("语音接口响应过大");
                }
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
