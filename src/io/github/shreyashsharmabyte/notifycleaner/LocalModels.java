package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Small models worth running locally for one-word classification. Pure data.
 *
 * <p>The task is trivial by LLM standards — read a sentence, answer one of two words — so the
 * smallest usable model wins: less RAM, faster response, and the notification is dismissed before it
 * scrolls away. These are Ollama tags, because that is one command to install and serves an
 * OpenAI-compatible endpoint out of the box.
 *
 * <p>None of this runs on the phone. The app talks to a server on your own network; see LocalServer
 * in the documentation. Running a model inside the app needs native inference libraries and a
 * multi-hundred-megabyte model, which is a different build.
 *
 * <p><b>No longer offered.</b> The app now classifies on-device with {@link LocalClassifier}, which
 * outperformed every model in this list, so neither this list nor the Local server preset is
 * reachable from the UI. The class is kept for reference and for its tests.
 */
public final class LocalModels {

    public static final class Model {
        public final String tag;
        public final String download;
        public final String size;
        public final String note;

        Model(String tag, String size, String note) {
            this.tag = tag;
            this.download = "ollama pull " + tag;
            this.size = size;
            this.note = note;
        }
    }

    public static final Model[] ALL = {
            new Model("qwen2.5:0.5b", "about 400 MB",
                    "Smallest usable option. Fast enough for a slow phone. Occasionally unsure, "
                    + "which means it keeps notifications rather than removing them."),
            new Model("llama3.2:1b", "about 1.3 GB",
                    "Best balance for this task. Recommended starting point."),
            new Model("qwen2.5:1.5b", "about 1.0 GB",
                    "Slightly better at promotional wording than 1b, similar speed."),
            new Model("gemma2:2b", "about 1.6 GB",
                    "Strongest of the small options. Needs roughly 4 GB of free RAM on the server."),
    };

    private LocalModels() {}

    /** Suggested default for a new local setup. */
    public static String recommended() { return ALL[1].tag; }
}
