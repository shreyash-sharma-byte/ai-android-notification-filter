package io.github.shreyashsharmabyte.notifycleaner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Choose which apps the classifier never sees.
 *
 * <p>Two sources are merged — apps that can be launched, and apps that have actually posted a
 * notification on this device — so the common case needs no typing. A manual entry is still
 * available, and an entry that matches no installed app is shown rather than hidden: a typo used to
 * disappear into the list, counted but unremovable.
 *
 * <p>The switches re-read the stored list after every change, so the screen and the service cannot
 * disagree about what is protected.
 */
public class ProtectedAppsActivity extends Screen {

    private static final class Entry {
        final String pkg;
        final String label;
        final Drawable icon;

        Entry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final List<Entry> all = new ArrayList<Entry>();
    private LinearLayout listContainer;
    private TextView summary;
    private EditText search;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Protected apps", "Back", "Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) { addByName(); }
        });

        summary = text("", 13, c.muted, false);
        summary.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        root.addView(summary, margins(16, 20, 20, 4));

        TextView explainer = text("The default list protects messaging, phone, bank/UPI and Gmail "
                + "apps: they carry messages, codes and payments.", 13, c.muted, false);
        explainer.setLineSpacing(dp(4), 1f);
        root.addView(explainer, margins(4, 20, 24, 8));

        search = field(root, "Search", "", null, false, 1);
        search.setHint("App name or package");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        root.addView(rule(), margins(18, 20, 20, 0));
        help(root, "You can also add an app by package name. Any app on this list is never read "
                + "and never filtered.");

        loadApps();
        render("");
        refreshSummary();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override
            public void afterTextChanged(Editable s) { render(s.toString()); }
        });

        setScreenContent(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(search == null ? "" : search.getText().toString());
        refreshSummary();
    }

    /** Launchable apps plus anything that has posted a notification here. */
    private void loadApps() {
        Map<String, Entry> byPackage = new HashMap<String, Entry>();
        PackageManager pm = getPackageManager();

        // Declaring a <queries> entry for the launcher intent is the supported way to see installed
        // apps without asking for QUERY_ALL_PACKAGES, which Play restricts.
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName()) || byPackage.containsKey(pkg)) continue;
                CharSequence label = info.loadLabel(pm);
                byPackage.put(pkg, new Entry(pkg,
                        label == null ? pkg : label.toString(), safeIcon(info, pm)));
            }
        } catch (Throwable ignored) {
            // Package manager unavailable in a restricted profile; the seen list still works.
        }

        // Apps that have actually posted a notification, even if they have no launcher entry.
        for (String[] seen : prefs.seenApps()) {
            if (seen[0].equals(getPackageName()) || byPackage.containsKey(seen[0])) continue;
            byPackage.put(seen[0], new Entry(seen[0], seen[1], null));
        }

        all.clear();
        all.addAll(byPackage.values());
        Collections.sort(all, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private Drawable safeIcon(ResolveInfo info, PackageManager pm) {
        try {
            return info.loadIcon(pm);
        } catch (Throwable t) {
            return null;
        }
    }

    private String query() {
        return search == null ? "" : search.getText().toString().trim();
    }

    private void render(String query) {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String list = prefs.allowList();

        int shown = 0;
        for (final Entry entry : all) {
            boolean match = q.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(q)
                    || entry.pkg.toLowerCase(Locale.ROOT).contains(q);
            if (!match) continue;
            if (shown > 0) divider(listContainer);
            listContainer.addView(appRow(entry, list));
            shown++;
        }
        if (shown == 0) {
            listContainer.addView(italic(q.isEmpty() ? "No apps found."
                            : "No app matches \"" + query + "\".", 15, c.muted),
                    margins(18, 20, 20, 16));
        }

        renderUnmatched(listContainer, list, q);
    }

    /**
     * Entries in the stored list that match no installed app. These used to be invisible: counted
     * in "N apps protected", never readable and never removable. Skipping the built-in defaults
     * keeps a fresh install from showing a wall of apps that simply are not on this phone.
     */
    private void renderUnmatched(LinearLayout parent, String list, String query) {
        List<String> orphans = new ArrayList<String>();
        for (String entry : AllowList.entries(list)) {
            if (isDefaultEntry(entry)) continue;
            if (query != null && !query.isEmpty() && !entry.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (!matchesInstalledApp(entry)) orphans.add(entry);
        }
        if (orphans.isEmpty()) return;

        parent.addView(rule(), margins(18, 20, 20, 0));
        parent.addView(kicker("Saved entries with no installed app", c.muted),
                margins(16, 20, 20, 0));
        for (String entry : orphans) {
            parent.addView(unmatchedRow(entry));
        }
    }

    /** @return true when the entry protects at least one app this screen knows about */
    private boolean matchesInstalledApp(String entry) {
        for (Entry e : all) {
            if (Filters.isProtected(entry, e.pkg)) return true;
        }
        return false;
    }

    private boolean isDefaultEntry(String entry) {
        String needle = entry == null ? "" : entry.trim();
        if (needle.isEmpty()) return false;
        for (String part : Prefs.DEFAULT_ALLOW.split(",")) {
            if (part.trim().equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private View unmatchedRow(final String entry) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(56));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(entry, 15, c.ink, false));
        TextView note = text("No installed app matches this saved entry.", 12, c.muted, false);
        note.setLineSpacing(dp(3), 1f);
        labels.addView(note, margins(2, 0, 0, 0));
        r.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = action("Remove", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setAllowList(removeExact(prefs.allowList(), entry));
                render(query());
                refreshSummary();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMarginStart(dp(12));
        r.addView(remove, rp);
        remove.setContentDescription("Remove " + entry);
        return r;
    }

    /**
     * Removes one exact saved entry. {@link AllowList#remove} deliberately removes anything the
     * target matches, which is right for a toggle but would take a neighbouring rule with it here.
     */
    private String removeExact(String list, String entry) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : list.split("[,\\s;]+")) {
            String candidate = part.trim();
            if (candidate.isEmpty() || candidate.equalsIgnoreCase(entry)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(candidate);
        }
        return sb.toString();
    }

    private View appRow(final Entry entry, String list) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(56));

        if (entry.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(entry.icon);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(26), dp(26));
            ip.setMarginEnd(dp(12));
            r.addView(icon, ip);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(entry.label, 15, c.ink, false));
        TextView pkg = text(entry.pkg, 12, c.faint, false);
        pkg.setLetterSpacing(0.02f);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(pkg, margins(2, 0, 0, 0));
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Ornaments.Toggle toggle = new Ornaments.Toggle(this, c.accent, c.onAccent, c.line,
                c.surface, c.muted);
        final boolean already = Filters.isProtected(list, entry.pkg);
        toggle.setChecked(already, false);
        toggle.setLabel(entry.label + (already ? ", protected" : ", not protected"));
        toggle.setListener(new Ornaments.Toggle.Listener() {
            @Override
            public void onChecked(boolean on) {
                apply(entry.pkg, on);
            }
        });
        r.addView(toggle, new LinearLayout.LayoutParams(dp(48), dp(48)));

        // The whole row is a target, not only the pill. The row itself is not a second
        // accessibility node: the switch inside it is the control TalkBack should stop on.
        r.setBackground(ripple());
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !toggle.isChecked();
                toggle.setChecked(next, true);
                apply(entry.pkg, next);
            }
        });
        r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return r;
    }

    /** One write path: store the list, then re-render every row from what was stored. */
    private void apply(String pkg, boolean on) {
        prefs.setAllowList(AllowList.toggle(prefs.allowList(), pkg, on));
        render(query());
        refreshSummary();
    }

    private void refreshSummary() {
        if (summary == null) return;
        int count = ProtectedCount.of(this, prefs.allowList(), prefs.seenApps());
        if (count == 0) {
            summary.setText("No apps protected.");
            summary.setTextColor(c.muted);
        } else {
            summary.setText(count + (count == 1 ? " app protected" : " apps protected"));
            summary.setTextColor(c.muted);
        }
    }

    private void addByName() {
        final EditText input = new EditText(this);
        input.setHint("com.example.app or part of a package name");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout holder = new LinearLayout(this);
        holder.setPaddingRelative(dp(20), dp(8), dp(20), 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Add by name")
                .setView(holder)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (value.length() < Filters.MIN_ENTRY_LENGTH) {
                            android.widget.Toast.makeText(ProtectedAppsActivity.this,
                                    "At least " + Filters.MIN_ENTRY_LENGTH + " characters",
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setAllowList(AllowList.add(prefs.allowList(), value));
                        loadApps();
                        render(query());
                        refreshSummary();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
