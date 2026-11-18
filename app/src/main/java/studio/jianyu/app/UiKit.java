package studio.jianyu.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiKit {
    static final int PAPER = 0xFFF7F9FC;
    static final int CARD = 0xFFFFFFFF;
    static final int GLASS = 0xE8FFFFFF;
    static final int GLASS_STRONG = 0xF5FFFFFF;
    static final int GLASS_BORDER = 0xFFE6EEF8;
    static final int BORDER = 0xFFE2E9F4;
    static final int SOFT = 0xFFECF1F8;
    static final int BLUE = 0xFF2F5CE0;
    static final int BLUE_SOFT = 0xFFEAF0FF;
    static final int INK = 0xFF182638;
    static final int MUTED = 0xFF62748A;
    static final int DANGER = 0xFFD64545;
    static final int DANGER_SOFT = 0xFFFDECEC;

    interface IndexClick {
        void onSelect(int index);
    }

    static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static Drawable auroraCanvasBg(Context context) {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFEEF4FC, 0xFFF7F9FC, 0xFFF9F7F8});
        GradientDrawable glowTopLeft = new GradientDrawable();
        glowTopLeft.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowTopLeft.setGradientCenter(0.18f, 0.04f);
        glowTopLeft.setGradientRadius(dp(context, 280));
        glowTopLeft.setColors(new int[]{0x3884B8FF, 0x00FFFFFF});

        GradientDrawable glowTopRight = new GradientDrawable();
        glowTopRight.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowTopRight.setGradientCenter(0.86f, 0.08f);
        glowTopRight.setGradientRadius(dp(context, 250));
        glowTopRight.setColors(new int[]{0x30C8B0FF, 0x00FFFFFF});

        return new LayerDrawable(new Drawable[]{base, glowTopLeft, glowTopRight});
    }

    static GradientDrawable round(Context context, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    static GradientDrawable card(Context context, int fill, int stroke, int radiusDp) {
        GradientDrawable d = round(context, fill, radiusDp);
        if (stroke != 0) d.setStroke(dp(context, 1), stroke);
        return d;
    }

    static GradientDrawable liquidGlass(Context context, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(GLASS);
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(dp(context, 1), GLASS_BORDER);
        return d;
    }

    static GradientDrawable floatingPillBg(Context context, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(GLASS_STRONG);
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(dp(context, 1), 0xFFE4ECF6);
        return d;
    }

    static GradientDrawable prismBg(Context context, int radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A58E4, 0xFF4472F5, 0xFF6A85FA});
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(dp(context, 1), 0x55FFFFFF);
        return d;
    }

    static GradientDrawable darkFrosted(Context context, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x88141E2E);
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(dp(context, 1), 0x38FFFFFF);
        return d;
    }

    static GradientDrawable scrimBottom(int radiusDp, Context context) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB8101828, 0x42101828, 0x00101828});
        float r = dp(context, radiusDp);
        d.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        return d;
    }

    static void applySoftShadow(View view, Context context, float elevationDp, int tintColor) {
        view.setElevation(dp(context, elevationDp));
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineSpotShadowColor(tintColor);
            view.setOutlineAmbientShadowColor(tintColor);
        }
    }

    static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static ImageView icon(Context context, int resId, int sizeDp, int tintColor) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(resId);
        if (tintColor != 0) iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return iv;
    }

    static LinearLayout circleIconBtn(Context context, int iconRes, int iconTint, int bgColor, Runnable onClick) {
        LinearLayout btn = new LinearLayout(context);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(card(context, bgColor, 0xFFE4ECF6, 22));
        applySoftShadow(btn, context, 5, 0x2B182638);
        btn.setFocusable(true);
        ImageView iv = icon(context, iconRes, 19, iconTint);
        btn.addView(iv);
        MotionKit.attachPressSpring(btn, 0.90f);
        btn.setOnClickListener(v -> {
            if (iconRes == R.drawable.ic_settings) MotionKit.spinIcon(iv, 60f);
            else if (iconRes == R.drawable.ic_refresh) MotionKit.spinIcon(iv, 360f);
            else MotionKit.popIcon(iv);
            onClick.run();
        });
        return btn;
    }

    static LinearLayout prismButton(Context context, int iconRes, String label, Runnable onClick) {
        LinearLayout btn = new LinearLayout(context);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(prismBg(context, 28));
        applySoftShadow(btn, context, 10, 0x662F5CE0);
        btn.setFocusable(true);
        ImageView iv = null;
        if (iconRes != 0) {
            iv = icon(context, iconRes, 17, Color.WHITE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(context, 17), dp(context, 17));
            ilp.rightMargin = dp(context, 7);
            btn.addView(iv, ilp);
        }
        btn.addView(text(context, label, 15, Color.WHITE, true));
        final ImageView finalIv = iv;
        MotionKit.attachPressSpring(btn, 0.96f);
        btn.setOnClickListener(v -> {
            if (finalIv != null) MotionKit.popIcon(finalIv);
            onClick.run();
        });
        return btn;
    }

    static LinearLayout glassButton(Context context, int iconRes, String label, int bgColor, int fgColor, Runnable onClick) {
        LinearLayout btn = new LinearLayout(context);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(card(context, bgColor, 0xFFE4ECF6, 24));
        applySoftShadow(btn, context, 4, 0x22182638);
        btn.setFocusable(true);
        ImageView iv = null;
        if (iconRes != 0) {
            iv = icon(context, iconRes, 16, fgColor);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16));
            ilp.rightMargin = dp(context, 6);
            btn.addView(iv, ilp);
        }
        btn.addView(text(context, label, 13.5f, fgColor, true));
        final ImageView finalIv = iv;
        MotionKit.attachPressSpring(btn, 0.96f);
        btn.setOnClickListener(v -> {
            if (finalIv != null) MotionKit.popIcon(finalIv);
            onClick.run();
        });
        return btn;
    }

    static LinearLayout pillTagButton(Context context, int iconRes, String label, int bgColor, int fgColor, Runnable onClick) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(round(context, bgColor, 18));
        pill.setPadding(dp(context, 13), dp(context, 8), dp(context, 13), dp(context, 8));
        pill.setMinimumHeight(dp(context, 36));
        pill.setFocusable(true);
        ImageView iv = null;
        if (iconRes != 0) {
            iv = icon(context, iconRes, 14, fgColor);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(context, 14), dp(context, 14));
            ilp.rightMargin = dp(context, 5);
            pill.addView(iv, ilp);
        }
        pill.addView(text(context, label, 12.5f, fgColor, true));
        final ImageView finalIv = iv;
        MotionKit.attachPressSpring(pill, 0.93f);
        pill.setOnClickListener(v -> {
            if (finalIv != null) MotionKit.popIcon(finalIv);
            onClick.run();
        });
        return pill;
    }

    static TextView chip(Context context, String label, boolean active, Runnable onClick) {
        TextView view = text(context, label, 12.5f, active ? Color.WHITE : MUTED, active);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 15), dp(context, 8), dp(context, 15), dp(context, 8));
        view.setMinimumHeight(dp(context, 36));
        view.setBackground(active ? prismBg(context, 18) : liquidGlass(context, 18));
        if (active) applySoftShadow(view, context, 5, 0x442F5CE0);
        view.setFocusable(true);
        MotionKit.attachPressSpring(view, 0.94f);
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    static TextView frostedBadge(Context context, String label) {
        TextView view = text(context, label, 10.5f, Color.WHITE, true);
        view.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        view.setBackground(darkFrosted(context, 12));
        return view;
    }

    static TextView softBadge(Context context, String label, int bg, int fg) {
        TextView view = text(context, label, 11, fg, true);
        view.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        view.setBackground(round(context, bg, 12));
        return view;
    }

    /** 样式 1（仅首页）：无框极光灯塔微标 + 「见屿 · JIANYU」 + 苹果大标题 */
    static LinearLayout styleOneHeader(Context context, String pageTitle, View rightView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout kicker = new LinearLayout(context);
        kicker.setOrientation(LinearLayout.HORIZONTAL);
        kicker.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.ic_brand_lighthouse);
        kicker.addView(mark, new LinearLayout.LayoutParams(dp(context, 19), dp(context, 19)));
        TextView brand = text(context, "见屿 · JIANYU", 11, BLUE, true);
        brand.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(context, 5);
        kicker.addView(brand, blp);
        left.addView(kicker);

        TextView title = text(context, pageTitle, 29, INK, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(context, 2);
        left.addView(title, tlp);

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (rightView != null) row.addView(rightView);
        return row;
    }

    /** 灵感 / 作品库原生大标题顶栏（不重复堆叠品牌眉标） */
    static LinearLayout pageTitleHeader(Context context, String pageTitle, String subtitle, View rightView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(context, pageTitle, 29, INK, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(context, subtitle, 12, MUTED, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = dp(context, 2);
            left.addView(sub, slp);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (rightView != null) row.addView(rightView);
        return row;
    }

    static LinearLayout segmentedControl(Context context, String[] labels, int activeIndex, IndexClick listener) {
        return segmentedControl(context, labels, activeIndex, 13.5f, listener);
    }

    static LinearLayout segmentedControl(Context context, String[] labels, int activeIndex, float textSize, IndexClick listener) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(card(context, 0xE2E9F0FA, 0xE6FFFFFF, 22));
        int pad = dp(context, 4);
        bar.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean active = i == activeIndex;
            TextView item = text(context, labels[i], textSize, active ? BLUE : MUTED, true);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            if (active) {
                item.setBackground(round(context, CARD, 18));
                applySoftShadow(item, context, 3, 0x26182638);
            }
            item.setFocusable(true);
            MotionKit.attachPressSpring(item, 0.95f);
            item.setOnClickListener(v -> listener.onSelect(idx));
            bar.addView(item, new LinearLayout.LayoutParams(0, dp(context, 38), 1));
        }
        return bar;
    }

    static LinearLayout ratioCard(Context context, String ratio, boolean active, Runnable onClick) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(active ? card(context, BLUE_SOFT, BLUE, 16) : liquidGlass(context, 16));
        if (active) applySoftShadow(card, context, 4, 0x332F5CE0);
        card.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 9));
        card.setFocusable(true);
        MotionKit.attachPressSpring(card, 0.93f);
        card.setOnClickListener(v -> onClick.run());

        int w = 16, h = 16;
        if ("3:4".equals(ratio)) { w = 13; h = 18; }
        else if ("4:3".equals(ratio)) { w = 18; h = 13; }
        else if ("9:16".equals(ratio)) { w = 10; h = 19; }
        else if ("16:9".equals(ratio)) { w = 19; h = 10; }

        LinearLayout iconHolder = new LinearLayout(context);
        iconHolder.setGravity(Gravity.CENTER);
        View rect = new View(context);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(active ? 0x242F5CE0 : Color.TRANSPARENT);
        outline.setStroke(dp(context, 1.6f), active ? BLUE : MUTED);
        outline.setCornerRadius(dp(context, 3));
        rect.setBackground(outline);
        iconHolder.addView(rect, new LinearLayout.LayoutParams(dp(context, w), dp(context, h)));
        card.addView(iconHolder, new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)));

        TextView label = text(context, ratio, 11.5f, active ? BLUE : MUTED, active);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(context, 5);
        card.addView(label, lp);
        return card;
    }
}
