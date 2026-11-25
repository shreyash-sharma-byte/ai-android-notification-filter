package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Positive promotional evidence: the gate that stands between a positive model score and a
 * dismissal.
 *
 * <p>A positive dot product is not evidence that a notification is an advert. It is evidence
 * that the text shares tokens with the promotional corpus the model was trained on — and the
 * shipped model learned generic English ({@code is}, {@code this}, {@code get}, {@code up}) as
 * promotional, so ordinary sentences score positive. This class answers the narrower, more
 * honest question: <em>does the text actually contain the language of an offer?</em>
 *
 * <p>Only {@link LocalClassifier} uses this. The provider path has no dot product to gate: its
 * answer is already an explicit one-word verdict, and {@link Decision} is what contains it.
 *
 * <h2>The cue list, and why it is shaped this way</h2>
 *
 * <p>Two tiers. A <b>strong</b> cue is unambiguous in a notification: a percentage, an
 * explicit call to action ({@code shop now}, {@code download}), a discount noun
 * ({@code cashback}, {@code coupon}) or an opt-out ({@code unsubscribe}). One is enough.
 * A <b>weak</b> cue ({@code off}, {@code sale}, {@code offer}, {@code deal}, {@code limited},
 * {@code percent}…) is ordinary English in other contexts — "I got a great deal on the
 * tickets", "are you free tonight?" — so two distinct weak cues are required. That is what
 * keeps a personal message that merely mentions a deal out of the dismissal path while still
 * catching "Deals starting at Rs 99. Limited period offer."
 *
 * <p>Deliberate omissions are as important as inclusions:
 * <ul>
 *   <li>{@code free} on its own is <b>not</b> a cue. "Are you free tonight?" is the single most
 *       common false positive in the shipped model; only {@code free trial},
 *       {@code free delivery} and {@code free shipping} count.</li>
 *   <li>{@code code} is <b>not</b> a cue. "Use code CRAVE50" is caught by {@code use code}, and
 *       a bare {@code code} would collide with verification codes, which are guarded anyway.</li>
 *   <li>{@code win}/{@code won} are not cues. "Who won the match?" is a conversation; only
 *       {@code you have won} counts.</li>
 *   <li>{@code rs} followed by digits is not a cue. "Rs 500 debited" is a transaction, and
 *       "I sent you Rs 500" is a message. Only the promotional shapes {@code flat rs},
 *       {@code just rs}, {@code starting at rs} and {@code up to rs} count.</li>
 *   <li>{@code today}, {@code now} and {@code new} are not cues on their own; they appear in
 *       ordinary conversation constantly.</li>
 * </ul>
 *
 * <p>Matching is on a normalised copy of title + text: lowercased, every run of punctuation
 * turned into a single space, then whole-word/phrase matches. {@code office} therefore does not
 * match {@code off}, and {@code coffee} matches nothing. No regex is used, so Android's
 * ICU-backed engine cannot reject a pattern at class load.
 */
public final class Evidence {

    /** How many distinct weak cues are needed before the gate opens. */
    public static final int MIN_WEAK_CUES = 2;

    /** Cues that are strong enough to open the gate on their own. Normalised (lowercase, spaces). */
    private static final String[] STRONG_PHRASES = {
            "free trial", "free delivery", "free shipping",
            "shop now", "buy now", "order now", "download now", "install now",
            "claim now", "join now", "sign up now", "upgrade now",
            "limited time", "limited period", "today only", "last chance",
            "while stocks last", "you have won",
            "flat rs", "just rs", "starting at rs", "up to rs",
            "use code", "promo code", "coupon code", "buy 1 get 1",
            // Retail headline shapes. Each is a phrase, not a word, precisely so that ordinary
            // conversation ("I got a great deal") does not match.
            "special offer", "mega sale", "flash sale", "season sale", "clearance sale",
            "sale is live", "sale ends", "deal of the day", "deals of the day", "price drop",
            "new arrivals",
            // Added at integration, after the first pass missed "Refer a friend and earn rewards"
            // — a model score of 1.40 with no cue. Phrases rather than words, and measured: the
            // 30-message personal corpus still keeps 30/30 and one more promotion is caught.
            "refer a friend", "refer friends", "earn rewards",
    };

    /** Single strong words. Matched on word boundaries. */
    private static final String[] STRONG_WORDS = {
            "cashback", "coupon", "download", "install", "upto", "unsubscribe",
    };

    /** Ambiguous in isolation: at least {@link #MIN_WEAK_CUES} distinct ones must appear. */
    private static final String[] WEAK_WORDS = {
            "off", "sale", "offer", "deal", "discount", "promo", "voucher", "limited",
            "redeem", "newsletter", "prize", "winner", "hurry", "exclusive", "upgrade",
            "flat", "percent", "savings", "bargain",
    };

    private Evidence() {}

    /**
     * @param title notification title, may be null
     * @param text  notification body, may be null
     * @return true when the text contains enough promotional language to justify a dismissal
     */
    public static boolean hasPromotionalEvidence(String title, String text) {
        String blob = normalise(title, text);
        if (blob.isEmpty()) return false;

        // A percentage is the strongest single promotional signal there is, and it is never
        // produced by accident in a chat message.
        if (blob.indexOf('%') >= 0) return true;

        for (int i = 0; i < STRONG_PHRASES.length; i++) {
            if (hasCue(blob, STRONG_PHRASES[i])) return true;
        }
        for (int i = 0; i < STRONG_WORDS.length; i++) {
            if (hasCue(blob, STRONG_WORDS[i])) return true;
        }

        int weak = 0;
        for (int i = 0; i < WEAK_WORDS.length; i++) {
            if (hasCue(blob, WEAK_WORDS[i])) weak++;
        }
        return weak >= MIN_WEAK_CUES;
    }

    /**
     * Lowercases and collapses every non-word run to one space. {@code %} is kept as a word so
     * a percentage survives; every other punctuation mark becomes a separator.
     */
    static String normalise(String title, String text) {
        String raw = (title == null ? "" : title) + " " + (text == null ? "" : text);
        StringBuilder out = new StringBuilder(raw.length());
        boolean gap = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = Character.toLowerCase(raw.charAt(i));
            boolean word = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '%' || (ch > 127 && Character.isLetterOrDigit(ch));
            if (word) {
                if (gap && out.length() > 0) out.append(' ');
                out.append(ch);
                gap = false;
            } else {
                gap = true;
            }
        }
        return out.toString();
    }

    /** Whole-word (or whole-phrase) search over the normalised string. */
    static boolean hasCue(String blob, String cue) {
        int from = 0;
        while (true) {
            int i = blob.indexOf(cue, from);
            if (i < 0) return false;
            boolean left = i == 0 || blob.charAt(i - 1) == ' ';
            int end = i + cue.length();
            boolean right = end == blob.length() || blob.charAt(end) == ' ';
            if (left && right) return true;
            from = i + 1;
        }
    }
}
