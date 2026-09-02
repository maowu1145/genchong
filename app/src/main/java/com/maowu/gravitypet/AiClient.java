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

public final class AiClient {
    private static final int MAX_STREAMED_REPLY_CHARS = 100_000;
    public interface Callback {
        void onSentence(String sentence);
        void onSuccess(String reply);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final class ConversationHistory {
        private final List<JSONObject> messages = new ArrayList<>();
        private String identity = "";

        synchronized void reset() {
            messages.clear();
            identity = "";
        }
    }

    private AiClient() {}

    public static RequestHandle chat(Context context, int skin, String userText,
                                     ConversationHistory history, ExecutorService executor,
                                     Callback callback) {
        Context appContext = context.getApplicationContext();
        RequestHandle requestHandle = new RequestHandle();
        requestHandle.attach(executor.submit(() -> {
            HttpURLConnection connection = null;
            try {
                if (requestHandle.isCancelled()) return;
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
                String identity = skin + "\n" + petName + "\n" + persona;
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
                synchronized (history) {
                    if (!identity.equals(history.identity)) {
                        history.messages.clear();
                        history.identity = identity;
                    }
                    for (JSONObject message : history.messages) messages.put(message);
                }
                JSONObject userMessage = new JSONObject()
                        .put("role", "user").put("content", userText);
                messages.put(userMessage);
                request.put("messages", messages);

                URL url = new URL(preset.baseUrl + "/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
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
                if (code < 200 || code >= 300) {
                    String response = readAll(stream, 64_000);
                    String detail = response;
                    try {
                        JSONObject error = new JSONObject(response).optJSONObject("error");
                        if (error != null) detail = error.optString("message", response);
                    } catch (Exception ignored) {}
                    throw new IllegalStateException("接口返回 " + code + "："
                            + shorten(detail, 90));
                }
                String reply = readStreamingReply(stream, callback, requestHandle);
                if (reply.isEmpty()) throw new IllegalStateException("模型没有返回文字内容");

                synchronized (history) {
                    history.messages.add(userMessage);
                    history.messages.add(new JSONObject().put("role", "assistant").put("content", reply));
                    while (history.messages.size() > 10) history.messages.remove(0);
                }
                if (!requestHandle.isCancelled()) {
                    MAIN.post(() -> {
                        if (!requestHandle.isCancelled()) callback.onSuccess(reply);
                    });
                }
            } catch (Exception error) {
                if (requestHandle.isCancelled()) return;
                String message = error.getMessage() == null ? "对话失败" : error.getMessage();
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

    private static String readStreamingReply(InputStream stream, Callback callback,
                                             RequestHandle requestHandle)
            throws Exception {
        StringBuilder reply = new StringBuilder();
        StringBuilder pendingSpeech = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (requestHandle.isCancelled()) return "";
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
                if (reply.length() + delta.length() > MAX_STREAMED_REPLY_CHARS) {
                    throw new IllegalStateException("模型回复过长");
                }
                reply.append(delta);
                pendingSpeech.append(delta);
                emitCompleteSentences(pendingSpeech, callback, requestHandle);
            }
        }
        String remainder = pendingSpeech.toString().trim();
        if (!remainder.isEmpty() && !requestHandle.isCancelled()) {
            MAIN.post(() -> {
                if (!requestHandle.isCancelled()) callback.onSentence(remainder);
            });
        }
        return reply.toString().trim();
    }

    private static void emitCompleteSentences(StringBuilder pending, Callback callback,
                                              RequestHandle requestHandle) {
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
        if (!sentence.isEmpty() && !requestHandle.isCancelled()) {
            MAIN.post(() -> {
                if (!requestHandle.isCancelled()) callback.onSentence(sentence);
            });
        }
        emitCompleteSentences(pending, callback, requestHandle);
    }

    private static String readAll(InputStream stream, int maxChars) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.length() + line.length() > maxChars) {
                    throw new IllegalStateException("接口响应过大");
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
