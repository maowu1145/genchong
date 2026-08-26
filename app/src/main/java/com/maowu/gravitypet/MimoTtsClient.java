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
import java.util.concurrent.Executors;

/** Generates XiaoBiGa's spoken reply with Xiaomi MiMo-V2.5-TTS. */
final class MimoTtsClient {
    interface Callback {
        void onSuccess(byte[] wavData);
        void onError(String message);
    }

    private static final String ENDPOINT =
            "https://api.xiaomimimo.com/v1/chat/completions";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MimoTtsClient() {}

    static void synthesize(Context context, String text, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String apiKey = ApiKeyStore.load(appContext, "mimo");
                if (apiKey.isEmpty()) throw new IllegalStateException("缺少小米 MiMo API Key");

                // Keep the synthesis context strictly identical to the text
                // shown in the bubble. An optional style/history message gives
                // a generative TTS model room to continue beyond that text.
                JSONArray messages = new JSONArray()
                        .put(new JSONObject()
                                .put("role", "assistant")
                                .put("content", text));
                JSONObject request = new JSONObject()
                        .put("model", "mimo-v2.5-tts")
                        .put("messages", messages)
                        .put("audio", new JSONObject()
                                .put("format", "wav")
                                .put("voice", "mimo_default"))
                        .put("stream", false);

                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(90_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("语音合成接口返回 " + code + "："
                            + shorten(response, 160));
                }

                JSONObject audio = new JSONObject(response).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").optJSONObject("audio");
                String encoded = audio == null ? "" : audio.optString("data", "");
                if (encoded.isEmpty()) throw new IllegalStateException("模型没有返回声音");
                byte[] wav = Base64.decode(encoded, Base64.DEFAULT);
                MAIN.post(() -> callback.onSuccess(wav));
            } catch (Exception error) {
                String message = error.getMessage() == null ? "语音合成失败" : error.getMessage();
                MAIN.post(() -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
