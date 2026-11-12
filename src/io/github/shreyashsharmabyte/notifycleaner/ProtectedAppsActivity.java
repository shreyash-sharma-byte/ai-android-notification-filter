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
import android.text.TextWatcher;
import android.view.Gravity;
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
import java.util.Map;

/**
 * Choose which apps the classifier never sees.
 *
 * <p>This replaces a text box that asked the user to type package names from memory, which is why the
 * protected list looked like a clump of text and was unpleasant to change. Now it is a list of real
 * apps with switches: label, package name, icon. Two sources are merged — apps that can be launched,
 * and apps that have actually posted a notification on this device — so the common case needs no
 * typing at all. A manual entry is still available for anything missing.
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
        header(root, "Protected apps", "\u2039 Back", "Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) { addByName(); }
        });

        summary = text("", 13, c.muted, false);
        summary.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        root.addView(summary, margins(16, 20, 20, 4));

        search = field(root, "Search", "", null, false, 1);
        search.setHint("App name or package");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        root.addView(rule(), margins(18, 20, 20, 0));
        help(root, "Partial names work: \u201Cpaytm\u201D protects every package containing it.");

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

    private void render(String query) {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);

        int shown = 0;
        for (final Entry entry : all) {
            boolean match = q.isEmpty()
                    || entry.label.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || entry.pkg.toLowerCase(java.util.Locale.ROOT).contains(q);
            if (!match) continue;
            if (shown > 0) divider(listContainer);
            listContainer.addView(appRow(entry));
            shown++;
        }
        if (shown == 0) {
            listContainer.addView(italic(q.isEmpty() ? "No apps found."
                            : "No app matches \"" + query + "\".", 15, c.muted),
                    margins(18, 20, 20, 16));
        }
    }

    private View appRow(final Entry entry) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(56));

        if (entry.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(entry.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(26), dp(26));
            ip.rightMargin = dp(12);
            r.addView(icon, ip);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(entry.label, 15, c.ink, false));
        TextView pkg = text(entry.pkg, 10.5f, c.faint, false);
        pkg.setLetterSpacing(0.02f);
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        labels.addView(pkg, margins(2, 0, 0, 0));
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Ornaments.Toggle toggle = new Ornaments.Toggle(this, c.accent, c.onAccent, c.line,
                c.surface, c.muted);
        boolean already = prefs.allowList() != null
                && Filters.isProtected(prefs.allowList(), entry.pkg);
        toggle.setChecked(already, false);
        toggle.setContentDescription(entry.label + (already ? ", protected" : ", not protected"));
        toggle.setListener(new Ornaments.Toggle.Listener() {
            @Override
            public void onChecked(boolean on) {
                prefs.setAllowList(AllowList.toggle(prefs.allowList(), entry.pkg, on));
                toggle.setContentDescription(entry.label + (on ? ", protected" : ", not protected"));
                refreshSummary();
            }
        });
        r.addView(toggle, new LinearLayout.LayoutParams(dp(44), dp(26)));
        return r;
    }

    private void refreshSummary() {
        if (summary == null) return;
        int count = Filters.protectedCount(prefs.allowList());
        summary.setText(count == 0
                ? "No apps protected. Messages and OTP codes will be classified."
                : count + (count == 1 ? " app protected" : " apps protected"));
        summary.setTextColor(count == 0 ? c.bad : c.muted);
    }

    private void addByName() {
        final EditText input = new EditText(this);
        input.setHint("com.example.app or part of a package name");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
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
                        render(search == null ? "" : search.getText().toString());
                        refreshSummary();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
