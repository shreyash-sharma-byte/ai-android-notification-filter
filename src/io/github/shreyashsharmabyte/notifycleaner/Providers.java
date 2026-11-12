package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Endpoint presets. Pure data.
 *
 * <p>The provider is now a stored choice rather than something inferred from the base URL. Inferring
 * it was a real bug: an empty base URL meant "Anthropic", and choosing Custom also produced an empty
 * base URL, so Custom could never be selected — it immediately read back as Anthropic. Keeping an
 * explicit id removes the ambiguity.
 *
 * <p>Every preset here is a cloud provider the user has to opt into. Classification is on-device by
 * default, so this list is reached only from the advanced answers screen.
 */
public final class Providers {

    public static final class Preset {
        public final String id;
        public final String name;
        public final String base;
        public final String model;
        /** Uses the Anthropic Messages API rather than OpenAI-compatible chat completions. */
        public final boolean anthropic;
        /** Runs on the user's own network: no API key, plain HTTP permitted. */
        public final boolean local;

        Preset(String id, String name, String base, String model, boolean anthropic, boolean local) {
            this.id = id;
            this.name = name;
            this.base = base;
            this.model = model;
            this.anthropic = anthropic;
            this.local = local;
        }
    }

    public static final String OPENAI_SUFFIX = "/chat/completions";

    /**
     * Anthropic's endpoint is fixed: {@code Classifier} builds it from the provider flag rather than
     * from a stored URL, which is why {@link #ANTHROPIC} carries an empty {@code base}.
     *
     * <p>That collides with {@code Prefs.configured()}, which requires {@code Urls.allowed(base())} to
     * pass for every provider — and refuses an empty string. The default provider therefore read as
     * unconfigured forever, and the listener returned early for it. The UI stores this URL when
     * Anthropic is selected so the preference and the classifier agree. It is never sent: the
     * Anthropic branch ignores the field.
     */
    public static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com";

    public static final Preset ANTHROPIC =
            new Preset("anthropic", "Anthropic", "", "claude-haiku-5-5", true, false);
    public static final Preset DEEPSEEK =
            new Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", false, false);
    public static final Preset OPENAI =
            new Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", false, false);
    public static final Preset GROQ =
            new Preset("groq", "Groq", "https://api.groq.com/openai/v1",
                    "llama-3.3-70b-versatile", false, false);
    public static final Preset OPENROUTER =
            new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                    "google/gemini-2.0-flash-001", false, false);
    public static final Preset GEMINI =
            new Preset("gemini", "Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai",
                    "gemini-3.6-flash", false, false);
    /**
     * Retained only so a pre-2.1.7 install that stored {@code local} still deserialises; it is not
     * in {@link #ALL}, so it can no longer be chosen, and {@link #byId} never returns it. Running a
     * model server on your own network is no longer a supported path: the app classifies on-device.
     */
    public static final Preset LOCAL =
            new Preset("local", "Local server", "http://192.168.1.10:11434/v1",
                    "llama3.2:1b", false, true);
    public static final Preset CUSTOM =
            new Preset("custom", "Custom", "", "", false, false);

    /** The provider picker's list. Cloud providers only — nothing here runs on your own hardware. */
    public static final Preset[] ALL = {
            ANTHROPIC, DEEPSEEK, OPENAI, GROQ, OPENROUTER, GEMINI, CUSTOM
    };

    private Providers() {}

    /** Never null: unknown ids fall back to Anthropic, which is also the default. */
    public static Preset byId(String id) {
        if (id != null) {
            for (Preset p : ALL) {
                if (p.id.equalsIgnoreCase(id.trim())) return p;
            }
        }
        return ANTHROPIC;
    }

    /**
     * The base URL to store for a preset. Presets that talk to a fixed host carry an empty
     * {@code base}, because the classifier ignores it; storing the documented URL keeps
     * {@code Prefs.configured()} — which validates the field for every provider — honest.
     */
    public static String baseFor(Preset preset) {
        if (preset == null) return "";
        return preset.anthropic ? ANTHROPIC_ENDPOINT : preset.base;
    }

    /** Strips trailing slashes and appends the OpenAI chat path. */
    public static String chatUrl(String base) {
        return Urls.normalise(base) + OPENAI_SUFFIX;
    }
}
