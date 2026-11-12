package io.github.shreyashsharmabyte.notifycleaner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The only class that talks to a provider.
 *
 * <p>Policy order, cheapest first: configuration check → circuit breaker → rate limiter → request
 * (with at most one short retry). Every rejection returns KEEP. Nothing here throws out to the
 * caller, because the caller is a notification listener that must never crash and must never guess.
 */
final class Classifier {

    /** Token budget. Generous so reasoning models can finish thinking and still answer. */
    static final int MAX_TOKENS = 1024;
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 15000;

    private final String apiKey;
    private final String model;
    private final String base;
    private final String prompt;
    private final boolean anthropic;
    private final boolean local;
    private final CircuitBreaker breaker;
    private final RateLimiter limiter;

    Classifier(String apiKey, String model, String base, String prompt, String providerId,
               CircuitBreaker breaker, RateLimiter limiter) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.base = base == null ? "" : base.trim();
        this.prompt = Prompt.effective(prompt);
        Providers.Preset preset = Providers.byId(providerId);
        this.anthropic = preset.anthropic;
        this.local = preset.local;
        this.breaker = breaker;
        this.limiter = limiter;
    }

    /** Result of one classification. {@code note} is null on a clean JUNK/KEEP. */
    static final class Outcome {
        final Decision.Verdict verdict;
        final String note;
        final boolean problem;

        Outcome(Decision.Verdict verdict, String note, boolean problem) {
            this.verdict = verdict;
            this.note = note;
            this.problem = problem;
        }

        static Outcome keep(String note) {
            return new Outcome(Decision.Verdict.UNCLEAR, note, true);
        }
    }

    /**
     * Classifies one notification. Never throws and never blocks longer than roughly 20 seconds.
     *
     * @return an outcome whose verdict is only JUNK when the model clearly said so
     */
    Outcome classify(String app, String title, String text, long nowMs) {
        // A local server has no key; requiring one would make it unusable. Anthropic has a fixed
        // endpoint, so its empty base URL is not "unconfigured".
        if (!local && apiKey.isEmpty()) return Outcome.keep("No API key set");
        if (model.isEmpty()) return Outcome.keep("No model set");
        if (!anthropic && !Urls.allowed(base)) {
            return Outcome.keep("Endpoint must be https, or http on your own network");
        }

        if (!breaker.allowsRequest(nowMs)) {
            long left = breaker.retryAfterMs(nowMs);
            return Outcome.keep("Paused after " + CircuitBreaker.THRESHOLD + " errors, retrying in "
                    + Math.max(1, left / 1000) + "s");
        }
        if (!limiter.tryAcquire(nowMs)) {
            return Outcome.keep("Rate limit reached (" + RateLimiter.CAPACITY + "/min)");
        }

        String user = Prompt.userMessage(app, title, text);
        String failure = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String raw = call(user);
                Decision.Verdict verdict = Decision.parse(raw);
                breaker.recordSuccess();
                if (verdict == Decision.Verdict.UNCLEAR) {
                    String shown = raw.trim().isEmpty()
                            ? "Empty answer"
                            : "Unexpected answer \"" + Prompt.cut(raw.trim(), 40) + "\"";
                    return new Outcome(verdict, shown, true);
                }
                return new Outcome(verdict, null, false);
            } catch (HttpFailure f) {
                failure = f.describe();
                long wait = Backoff.retryWaitMs(f.status, f.retryAfter, attempt);
                boolean worthRetrying = wait >= 0 && wait <= Backoff.MAX_RETRY_WAIT_MS;
                if (attempt == 0 && worthRetrying) {
                    if (!sleep(wait)) return Outcome.keep("Interrupted");
                    continue;
                }
                break;
            } catch (IOException io) {
                failure = "Network: "
                        + (io.getMessage() == null ? io.getClass().getSimpleName() : io.getMessage());
                if (attempt == 0) {
                    if (!sleep(Backoff.delayMs(0))) return Outcome.keep("Interrupted");
                    continue;
                }
                break;
            } catch (Exception e) {
                // Parse failures and anything unforeseen: keep, and say something useful.
                failure = "Bad response from provider";
                break;
            }
        }

        breaker.recordFailure(nowMs);
        return Outcome.keep(failure == null ? "Request failed" : failure);
    }

    /** Single attempt. Anthropic when the base URL is empty, OpenAI-compatible otherwise. */
    private String call(String user) throws IOException, HttpFailure {
        if (anthropic) {
            JSONObject body = new JSONObject();
            put(body, "model", model);
            put(body, "max_tokens", MAX_TOKENS);
            put(body, "system", prompt);
            put(body, "messages", messages(null, user));
            Response r = Http.post("https://api.anthropic.com/v1/messages", body.toString(), apiKey, true);
            if (r.status != 200) throw new HttpFailure(r.status, r.retryAfter, errorMessage(r.body));
            return extractAnthropic(r.body);
        }

        String url = Providers.chatUrl(base);
        String bearer = local && apiKey.isEmpty() ? "local" : apiKey;
        // Some OpenAI models renamed the token field; retry once on that specific complaint.
        Response r = Http.post(url, buildOpenAiBody(user, "max_tokens"), bearer, false);
        if (r.status == 400 && r.body.contains("max_completion_tokens")) {
            r = Http.post(url, buildOpenAiBody(user, "max_completion_tokens"), bearer, false);
        }
        if (r.status != 200) throw new HttpFailure(r.status, r.retryAfter, errorMessage(r.body));
        return extractOpenAi(r.body);
    }

    private String buildOpenAiBody(String user, String tokenField) {
        JSONObject body = new JSONObject();
        put(body, "model", model);
        put(body, tokenField, MAX_TOKENS);
        put(body, "messages", messages(prompt, user));
        return body.toString();
    }

    /**
     * Builds the messages array. Android's {@code org.json} declares {@code JSONException} on put,
     * so the checked noise is contained here rather than sprayed through the call path.
     */
    private static JSONArray messages(String system, String user) {
        JSONArray a = new JSONArray();
        try {
            if (system != null) a.put(new JSONObject().put("role", "system").put("content", system));
            a.put(new JSONObject().put("role", "user").put("content", user));
        } catch (Exception ignored) {
            // Only reachable for non-finite doubles, which cannot occur here.
        }
        return a;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
            // JSONObject.put only throws on non-finite doubles; impossible for these values.
        }
    }

    static String extractAnthropic(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray content = o.optJSONArray("content");
            if (content == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    sb.append(block.optString("text", ""));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String extractOpenAi(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject first = choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            if (message == null) return "";
            Object content = message.opt("content");
            if (content == null || content == JSONObject.NULL) return "";
            if (content instanceof JSONArray) {
                // Newer OpenAI shape: content is a list of typed parts.
                JSONArray parts = (JSONArray) content;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) sb.append(part.optString("text", ""));
                }
                return sb.toString().trim();
            }
            return String.valueOf(content).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Pulls {@code error.message} out of a provider error body, falling back to the raw text. */
    static String errorMessage(String body) {
        if (body == null) return "";
        try {
            JSONObject o = new JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof JSONObject) {
                String m = ((JSONObject) err).optString("message", "");
                if (!m.isEmpty()) return Prompt.cut(m, 160);
            } else if (err instanceof String) {
                return Prompt.cut((String) err, 160);
            }
            String m = o.optString("message", "");
            if (!m.isEmpty()) return Prompt.cut(m, 160);
        } catch (Exception ignored) {
            // Not JSON: fall through to the raw body.
        }
        return Prompt.cut(body.trim(), 160);
    }

    private static boolean sleep(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A non-200 HTTP response. */
    static final class HttpFailure extends Exception {
        private static final long serialVersionUID = 1L;

        final int status;
        final String retryAfter;

        HttpFailure(int status, String retryAfter, String message) {
            super("HTTP " + status + (message.isEmpty() ? "" : ": " + message));
            this.status = status;
            this.retryAfter = retryAfter;
        }

        String describe() {
            return getMessage();
        }
    }

    /** Transport result. */
    static final class Response {
        final int status;
        final String body;
        final String retryAfter;

        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.retryAfter = retryAfter;
        }
    }

    /** Minimal HTTPS POST. Kept separate so the policy above stays readable. */
    static final class Http {

        private Http() {}

        static Response post(String url, String body, String apiKey, boolean anthropic)
                throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Accept", "application/json");
                if (anthropic) {
                    c.setRequestProperty("x-api-key", apiKey);
                    c.setRequestProperty("anthropic-version", "2023-06-01");
                } else {
                    c.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = read(is);
                return new Response(code, text, c.getHeaderField("Retry-After"));
            } finally {
                c.disconnect();
            }
        }

        private static String read(InputStream is) throws IOException {
            if (is == null) return "";
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            } finally {
                r.close();
            }
        }
    }
}
