package com.maowu.gravitypet;

public final class ProviderPreset {
    public final String id;
    public final String name;
    public final String baseUrl;
    public final String model;

    private ProviderPreset(String id, String name, String baseUrl, String model) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public static final ProviderPreset[] ALL = new ProviderPreset[]{
            new ProviderPreset("deepseek", "DeepSeek",
                    "https://api.deepseek.com", "deepseek-v4-flash"),
            new ProviderPreset("mimo", "小米 MiMo",
                    "https://api.xiaomimimo.com/v1", "mimo-v2.5"),
            new ProviderPreset("kimi", "Kimi",
                    "https://api.moonshot.cn/v1", "kimi-k2.6"),
            new ProviderPreset("doubao", "豆包 / 火山方舟",
                    "https://ark.cn-beijing.volces.com/api/v3",
                    "doubao-seed-2-0-lite-260215"),
            new ProviderPreset("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash"),
            new ProviderPreset("qwen", "通义千问",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus")
    };

    public static ProviderPreset find(String id) {
        for (ProviderPreset preset : ALL) {
            if (preset.id.equals(id)) return preset;
        }
        return ALL[0];
    }
}
