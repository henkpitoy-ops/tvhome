package com.minimal.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Minimal Apple-TV-style home: rotating wallpapers + one row of app tiles. No dependencies. */
public class HomeActivity extends Activity {
    static final long ROTATE_MS = 5 * 60 * 1000L, FADE_MS = 1500L;
    // Pinned first, in this order (package prefixes)
    static final String[] PIN = {"com.netflix", "com.amazon.amazonvideo", "com.disney", "nl.rtl",
            "com.crunchyroll", "com.formulaone", "com.google.android.youtube"};

    final Handler h = new Handler(Looper.getMainLooper());
    ImageView bgA, bgB;
    boolean frontA = true;
    TextView clock;
    LinearLayout row;
    HorizontalScrollView scroll;
    View first;
    final List<File> walls = new ArrayList<>();
    int wi = -1;
    String sig = "";
    float d;

    int dp(int v) { return Math.round(v * d); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bgA = bg(); bgB = bg(); bgB.setAlpha(0f);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0xCC000000}));
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE); clock.setTextSize(28); clock.setShadowLayer(8, 0, 2, 0xAA000000);
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setPadding(dp(48), dp(40), dp(48), dp(48));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setClipChildren(false);
        scroll.addView(row);
        root.addView(bgA, -1, -1); root.addView(bgB, -1, -1); root.addView(scrim, -1, -1);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(32), dp(48), 0);
        root.addView(clock, cp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    ImageView bg() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    @Override protected void onResume() {
        super.onResume();
        loadWalls(); buildRow(); tick();
        h.removeCallbacksAndMessages(null);
        nextWall();
        h.postDelayed(rot, ROTATE_MS);
        h.postDelayed(tick, 20000);
    }

    @Override protected void onPause() { super.onPause(); h.removeCallbacksAndMessages(null); }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        scroll.smoothScrollTo(0, 0);
        if (first != null) first.requestFocus();
    }

    @Override public void onBackPressed() { /* home screen: ignore */ }

    final Runnable rot = new Runnable() { public void run() { nextWall(); h.postDelayed(this, ROTATE_MS); } };
    final Runnable tick = new Runnable() { public void run() { tick(); h.postDelayed(this, 20000); } };

    void tick() { clock.setText(DateFormat.getTimeFormat(this).format(new Date())); }

    void loadWalls() {
        File[] fs = new File(getFilesDir(), "wallpapers").listFiles();
        List<File> n = new ArrayList<>();
        if (fs != null) for (File f : fs) {
            String s = f.getName().toLowerCase(Locale.ROOT);
            if (s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp")) n.add(f);
        }
        if (n.size() != walls.size()) { walls.clear(); walls.addAll(n); Collections.shuffle(walls); wi = -1; }
    }

    void nextWall() {
        if (walls.isEmpty()) return;
        wi = (wi + 1) % walls.size();
        final File f = walls.get(wi);
        new Thread(new Runnable() { public void run() {
            final Bitmap bm = decode(f);
            if (bm == null) return;
            h.post(new Runnable() { public void run() {
                final ImageView in = frontA ? bgB : bgA, out = frontA ? bgA : bgB;
                in.setImageBitmap(bm);
                in.animate().alpha(1f).setDuration(FADE_MS).start();
                out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
                    public void run() { out.setImageDrawable(null); } }).start();
                frontA = !frontA;
            }});
        }}).start();
    }

    Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int w = getResources().getDisplayMetrics().widthPixels, s = 1;
            while (o.outWidth / (s * 2) >= w) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s; o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) { return null; }
    }

    List<ResolveInfo> query(PackageManager pm, String cat) {
        return pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
    }

    int rank(ResolveInfo r) {
        for (int i = 0; i < PIN.length; i++) if (r.activityInfo.packageName.startsWith(PIN[i])) return i;
        return PIN.length;
    }

    void buildRow() {
        final PackageManager pm = getPackageManager();
        String cat = Intent.CATEGORY_LEANBACK_LAUNCHER;
        List<ResolveInfo> l = query(pm, cat);
        if (l.isEmpty()) { cat = Intent.CATEGORY_LAUNCHER; l = query(pm, cat); }
        Map<String, ResolveInfo> m = new LinkedHashMap<>();
        for (ResolveInfo r : l) {
            String p = r.activityInfo.packageName;
            if (!p.equals(getPackageName()) && !m.containsKey(p)) m.put(p, r);
        }
        List<ResolveInfo> apps = new ArrayList<>(m.values());
        Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                int x = rank(a), y = rank(b);
                if (x != y) return x - y;
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }});
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo r : apps) sb.append(r.activityInfo.packageName).append(',');
        if (sb.toString().equals(sig)) return;
        sig = sb.toString();
        row.removeAllViews(); first = null;
        for (ResolveInfo r : apps) {
            Drawable img = r.activityInfo.loadBanner(pm);
            if (img == null) img = r.activityInfo.applicationInfo.loadBanner(pm);
            boolean banner = img != null;
            if (!banner) img = r.loadIcon(pm);
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            View c = tile(r.loadLabel(pm), img, banner, li);
            if (first == null) first = c;
        }
        tile("Settings", null, false, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if (first != null) first.requestFocus();
    }

    View tile(CharSequence label, Drawable img, boolean banner, final Intent launch) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2A2A2E); g.setCornerRadius(dp(14));
        card.setBackground(g); card.setClipToOutline(true); card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        if (img == null) {
            TextView t = new TextView(this);
            t.setText(label); t.setTextColor(Color.WHITE); t.setTextSize(22); t.setGravity(Gravity.CENTER);
            card.addView(t, -1, -1);
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(img);
            if (banner) { iv.setScaleType(ImageView.ScaleType.CENTER_CROP); card.addView(iv, -1, -1); }
            else { iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(iv, new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER)); }
        }
        final TextView lab = new TextView(this);
        lab.setText(label); lab.setTextColor(Color.WHITE); lab.setTextSize(16); lab.setSingleLine(true);
        lab.setEllipsize(TextUtils.TruncateAt.END); lab.setMaxWidth(dp(280)); lab.setAlpha(0f);
        lab.setPadding(0, dp(14), 0, 0); lab.setGravity(Gravity.CENTER);
        card.setFocusable(true); card.setClickable(true);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(120).start();
                lab.animate().alpha(f ? 1f : 0f).setDuration(120).start();
            }});
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(launch); }
                catch (Throwable e) { Toast.makeText(HomeActivity.this, "Can't open", Toast.LENGTH_SHORT).show(); }
            }});
        col.addView(card, dp(280), dp(158));
        col.addView(lab, new LinearLayout.LayoutParams(dp(280), -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(14), 0, dp(14), 0);
        row.addView(col, lp);
        return card;
    }
}
