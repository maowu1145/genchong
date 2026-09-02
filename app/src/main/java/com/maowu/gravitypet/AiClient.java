package com.maowu.gravitypet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiClient {
    public interface Callback {
        void onSentence(String sentence);
        void onSuccess(String reply);
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<JSONObject> HISTORY = new ArrayList<>();
    private static String historyIdentity = "";

    private AiClient() {}

    public static void chat(Context context, int skin, String userText, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                ProviderPreset preset = ProviderPreset.find("mimo");
                String apiKey = ApiKeyStore.load(appContext, "mimo");
                if (apiKey.isEmpty()) throw new IllegalStateException("请先在 AI 设置中填写 API Key");

                JSONObject request = new JSONObject();
                request.put("model", preset.model);
                request.put("stream", true);
                request.put("max_completion_tokens", 160);
                request.put("thinking", new JSONObject().put("type", "disabled"));
                JSONArray messages = new JSONArray();
                String petName = PetPreferences.getPetName(appContext, skin);
                String persona = PetPreferences.getPersona(appContext, skin);
                String identity = skin + "\n" + persona;
                StringBuilder systemPrompt = new StringBuilder()
                        .append("你是用户手机屏幕上的桌宠，名字叫")
                        .append(petName)
                        .append("，也属于小鼻嘎。请始终以这个角色的身份，用自然、简短的中文回答。口语对话优先用1至2句，通常不超过60字。");
                if (!persona.isEmpty()) {
                    systemPrompt.append("\n这是用户为你设定的人设，请按照它的性格、身份、语气和关系进行对话：\n")
                            .append(persona);
                }
                messages.put(new JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt.toString()));
                synchronized (HISTORY) {
                    if (!identity.equals(historyIdentity)) {
                        HISTORY.clear();
                        historyIdentity = identity;
                    }
                    for (JSONObject message : HISTORY) messages.put(message);
                }
                JSONObject userMessage = new JSONObject()
                        .put("role", "user").put("content", userText);
                messages.put(userMessage);
                request.put("messages", messages);

                URL url = new URL(preset.baseUrl + "/chat/completions");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
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
                if (code < 200 || code >= 300) {
                    String response = readAll(stream);
                    String detail = response;
                    try {
                        JSONObject error = new JSONObject(response).optJSONObject("error");
                        if (error != null) detail = error.optString("message", response);
                    } catch (Exception ignored) {}
                    throw new IllegalStateException("接口返回 " + code + "："
                            + shorten(detail, 90));
                }
                String reply = readStreamingReply(stream, callback);
                connection.disconnect();
                if (reply.isEmpty()) throw new IllegalStateException("模型没有返回文字内容");

                synchronized (HISTORY) {
                    HISTORY.add(userMessage);
                    HISTORY.add(new JSONObject().put("role", "assistant").put("content", reply));
                    while (HISTORY.size() > 10) HISTORY.remove(0);
                }
                MAIN.post(() -> callback.onSuccess(reply));
            } catch (Exception error) {
                String message = error.getMessage() == null ? "对话失败" : error.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static String readStreamingReply(InputStream stream, Callback callback)
            throws Exception {
        StringBuilder reply = new StringBuilder();
        StringBuilder pendingSpeech = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject chunk = new JSONObject(data);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject deltaObject = choices.getJSONObject(0).optJSONObject("delta");
                if (deltaObject == null) continue;
                // Reasoning-only and terminal chunks contain JSON null here.
                // JSONObject.optString would turn that into the literal "null".
                if (deltaObject.isNull("content")) continue;
                Object content = deltaObject.opt("content");
                if (!(content instanceof String)) continue;
                String delta = (String) content;
                if (delta.isEmpty()) continue;
                reply.append(delta);
                pendingSpeech.append(delta);
                emitCompleteSentences(pendingSpeech, callback);
            }
        }
        String remainder = pendingSpeech.toString().trim();
        if (!remainder.isEmpty()) MAIN.post(() -> callback.onSentence(remainder));
        return reply.toString().trim();
    }

    private static void emitCompleteSentences(StringBuilder pending,
                                              Callback callback) {
        int splitAt = -1;
        for (int i = 0; i < pending.length(); i++) {
            char value = pending.charAt(i);
            if (value == '。' || value == '！' || value == '？'
                    || value == '!' || value == '?' || value == '\n') {
                splitAt = i + 1;
                break;
            }
        }
        // A very long sentence should not postpone speech indefinitely. Split
        // at a natural comma once enough text is available.
        if (splitAt < 0 && pending.length() >= 42) {
            for (int i = Math.min(pending.length() - 1, 52); i >= 24; i--) {
                char value = pending.charAt(i);
                if (value == '，' || value == ',' || value == '；' || value == ';') {
                    splitAt = i + 1;
                    break;
                }
            }
        }
        if (splitAt <= 0) return;
        String sentence = pending.substring(0, splitAt).trim();
        pending.delete(0, splitAt);
        if (!sentence.isEmpty()) MAIN.post(() -> callback.onSentence(sentence));
        emitCompleteSentences(pending, callback);
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
