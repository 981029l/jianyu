package studio.jianyu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int SAVE_LOCAL_IMAGE = 1;
    private static final int PICK_REFERENCE = 2;
    private static final int SAVE_REMOTE_IMAGE = 3;
    private static final int PLAZA_PAGE_SIZE = 24;
    private static final int GALLERY_PAGE_SIZE = 24;

    private FrameLayout root;
    private LinearLayout page;
    private EditText prompt;
    private String draft = "", screen = "", current = "";
    private String activePrompt = "", activeModel = "", activeRatio = "";
    private boolean activeHasRef = false;
    private int model, ratio;
    private boolean nsfw;
    private int libraryFilter; // 0: 全部, 1: GPT, 2: Grok
    private int inspireTab;    // 0: 创意广场, 1: 提示词画廊
    private String galleryMajor = "全部";
    private String galleryQuery = "";
    private int galleryVisibleCount = GALLERY_PAGE_SIZE;

    private String[] models, wireModels, ratios;
    private Uri reference;
    private ImageService service;

    private static boolean busy;
    private static boolean checkingUpdate;
    private static boolean autoCheckedUpdate;
    private static MainActivity visible;

    private static final List<InspirationRepository.Item> plazaItems = new ArrayList<>();
    private static int plazaPage = 0;
    private static boolean plazaHasMore = true;
    private static boolean plazaLoading = false;
    private static String plazaError = "";

    private static InspirationRepository.GalleryData galleryData = null;
    private static boolean galleryLoading = false;
    private static String galleryError = "";
    private static InspirationRepository.Item activeInspiration = null;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        service = new ImageService(this);
        models = getResources().getStringArray(R.array.models);
        wireModels = getResources().getStringArray(R.array.wire_models);
        ratios = getResources().getStringArray(R.array.ratios);
        if (state != null) {
            draft = state.getString("draft", "");
            model = state.getInt("model", 0);
            ratio = state.getInt("ratio", 0);
            nsfw = state.getBoolean("nsfw", false);
            current = state.getString("current", "");
            libraryFilter = state.getInt("libraryFilter", 0);
            inspireTab = state.getInt("inspireTab", 0);
            galleryMajor = state.getString("galleryMajor", "全部");
            galleryQuery = state.getString("galleryQuery", "");
            galleryVisibleCount = state.getInt("galleryVisibleCount", GALLERY_PAGE_SIZE);
            if (state.containsKey("reference")) reference = Uri.parse(state.getString("reference"));
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        String savedScreen = state != null ? state.getString("screen", "home") : "home";
        if ("detail".equals(savedScreen)) detail(current);
        else if ("library".equals(savedScreen)) library();
        else if ("inspire".equals(savedScreen)) inspire();
        else if ("inspire_detail".equals(savedScreen) && activeInspiration != null) inspireDetail(activeInspiration);
        else if ("loading".equals(savedScreen) && busy) loading();
        else home();

        if (!autoCheckedUpdate) {
            autoCheckedUpdate = true;
            checkUpdate(true);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = this;
        if ("loading".equals(screen) && !busy) home();
        else if ("library".equals(screen)) library();
    }

    @Override protected void onStop() {
        if (visible == this) visible = null;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        remember();
        state.putString("draft", draft);
        state.putInt("model", model);
        state.putInt("ratio", ratio);
        state.putBoolean("nsfw", nsfw);
        state.putInt("libraryFilter", libraryFilter);
        state.putInt("inspireTab", inspireTab);
        state.putString("galleryMajor", galleryMajor);
        state.putString("galleryQuery", galleryQuery);
        state.putInt("galleryVisibleCount", galleryVisibleCount);
        if (reference != null) state.putString("reference", reference.toString());
        state.putString("screen", screen);
        state.putString("current", current);
    }

    private void remember() {
        if (prompt != null) draft = prompt.getText().toString();
    }

    private int dp(float v) { return UiKit.dp(this, v); }
    private boolean isGrok() { return "Image 2".equals(wireModels[model]); }

    private void add(View view, int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, heightDp < 0 ? heightDp : dp(heightDp));
        p.topMargin = dp(topDp);
        page.addView(view, p);
    }

    /** 浅色晨光画布 + 全屏滚动 + 悬浮液态毛玻璃 3-Tab 药丸底栏 */
    private void layout(String name, boolean showFloatingNav) {
        remember();
        prompt = null;
        screen = name;

        root = new FrameLayout(this);
        root.setBackground(UiKit.auroraCanvasBg(this));
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets b = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return insets;
            });
        }
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(showFloatingNav ? 104 : 28));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        if (showFloatingNav) attachFloatingTabBar();
    }

    private void attachFloatingTabBar() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(UiKit.floatingPillBg(this, 34));
        UiKit.applySoftShadow(pill, this, 14, 0x3A182638);
        pill.setPadding(dp(8), dp(6), dp(8), dp(6));

        int[] iconRes = {R.drawable.ic_sparkles, R.drawable.ic_compass, R.drawable.ic_images};
        String[] labels = {"创作", "灵感", "作品库"};
        String[] keys = {"home", "inspire", "library"};

        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean active = key.equals(screen)
                    || ("home".equals(key) && "loading".equals(screen))
                    || ("inspire".equals(key) && "inspire_detail".equals(screen))
                    || ("library".equals(key) && "detail".equals(screen));

            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(7), dp(12), dp(7));
            if (active) tab.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 26));
            tab.setFocusable(true);

            int tint = active ? UiKit.BLUE : UiKit.MUTED;
            tab.addView(UiKit.icon(this, iconRes[i], 19, tint));
            TextView lbl = UiKit.text(this, labels[i], 10.5f, tint, active);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.topMargin = dp(2);
            tab.addView(lbl, llp);

            tab.setOnClickListener(v -> {
                if ("home".equals(key)) home();
                else if ("inspire".equals(key)) inspire();
                else library();
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tlp.leftMargin = dp(4);
            pill.addView(tab, tlp);
        }

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(296), -2);
        flp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        flp.bottomMargin = dp(14);
        root.addView(pill, flp);
    }

    // ==================== 01 & 02 创作页 ====================

    private void home() {
        layout("home", true);

        // 样式 1 题标：无框极光灯塔微标 + 见屿 · JIANYU + 大标题「创作」+ 右侧更新玻璃胶囊
        LinearLayout updatePill = new LinearLayout(this);
        updatePill.setOrientation(LinearLayout.HORIZONTAL);
        updatePill.setGravity(Gravity.CENTER_VERTICAL);
        updatePill.setBackground(UiKit.floatingPillBg(this, 18));
        UiKit.applySoftShadow(updatePill, this, 4, 0x22182638);
        updatePill.setPadding(dp(11), dp(7), dp(11), dp(7));
        updatePill.setFocusable(true);
        updatePill.setOnClickListener(v -> checkUpdate(false));
        updatePill.addView(UiKit.icon(this, R.drawable.ic_refresh, 14, UiKit.BLUE));
        TextView vTxt = UiKit.text(this, "v" + BuildConfig.VERSION_NAME + " · 更新", 11.5f, UiKit.INK, true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = dp(5);
        updatePill.addView(vTxt, vlp);

        add(UiKit.styleOneHeader(this, "创作", updatePill), -2, 0);

        // 灵动岛风待取回任务胶囊
        boolean pending = service.hasPending();
        if (pending && !busy) {
            LinearLayout banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setBackground(UiKit.card(this, 0xE8EAF0FF, 0xFFC8DAFF, 18));
            banner.setPadding(dp(12), dp(9), dp(10), dp(9));
            banner.addView(UiKit.icon(this, R.drawable.ic_clock, 16, UiKit.BLUE));

            TextView msg = UiKit.text(this, "上次作品仍在生成中", 12, UiKit.INK, true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1);
            mlp.leftMargin = dp(8);
            banner.addView(msg, mlp);

            TextView resume = UiKit.text(this, "继续获取", 11.5f, Color.WHITE, true);
            resume.setBackground(UiKit.round(this, UiKit.BLUE, 13));
            resume.setPadding(dp(10), dp(5), dp(10), dp(5));
            resume.setOnClickListener(v -> generate());
            banner.addView(resume);

            LinearLayout drop = new LinearLayout(this);
            drop.setGravity(Gravity.CENTER);
            drop.setPadding(dp(6), dp(5), dp(4), dp(5));
            drop.addView(UiKit.icon(this, R.drawable.ic_close, 14, UiKit.MUTED));
            drop.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("移除本机的取图记录？这不会取消服务端任务。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("移除", (d, w) -> { service.forget(); home(); })
                    .show());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = dp(4);
            banner.addView(drop, dlp);
            add(banner, -2, 12);
        }

        // Hero 液态毛玻璃画布卡
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setBackground(UiKit.liquidGlass(this, 26));
        UiKit.applySoftShadow(heroCard, this, 10, 0x26182638);
        heroCard.setPadding(dp(16), dp(14), dp(16), dp(13));

        // 若已选参考图，以 Apple Photos 贴纸风格内嵌画布顶部
        if (reference != null) {
            LinearLayout refChip = new LinearLayout(this);
            refChip.setOrientation(LinearLayout.HORIZONTAL);
            refChip.setGravity(Gravity.CENTER_VERTICAL);
            refChip.setBackground(UiKit.card(this, 0xCCF2F6FC, 0xFFFFFFFF, 16));
            refChip.setPadding(dp(8), dp(8), dp(10), dp(8));

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(UiKit.round(this, UiKit.SOFT, 11));
            thumb.setClipToOutline(true);
            loadReferenceThumb(thumb, reference);
            refChip.addView(thumb, new LinearLayout.LayoutParams(dp(50), dp(50)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(UiKit.text(this, "已附带参考图", 13, UiKit.INK, true));
            TextView sub = UiKit.text(this, "将按画面描述进行图改图", 11, UiKit.MUTED, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = dp(2);
            info.addView(sub, slp);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1);
            ilp.leftMargin = dp(10);
            refChip.addView(info, ilp);

            TextView swap = UiKit.text(this, "更换", 11.5f, UiKit.BLUE, true);
            swap.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 12));
            swap.setPadding(dp(10), dp(5), dp(10), dp(5));
            swap.setOnClickListener(v -> pickReference());
            refChip.addView(swap);

            LinearLayout remove = new LinearLayout(this);
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(UiKit.round(this, UiKit.DANGER_SOFT, 13));
            remove.addView(UiKit.icon(this, R.drawable.ic_close, 13, UiKit.DANGER));
            remove.setOnClickListener(v -> { reference = null; home(); });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(26), dp(26));
            rlp.leftMargin = dp(6);
            refChip.addView(remove, rlp);

            LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(-1, -2);
            refLp.bottomMargin = dp(10);
            heroCard.addView(refChip, refLp);
        }

        prompt = new EditText(this);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setTextSize(15.5f);
        prompt.setTextColor(UiKit.INK);
        prompt.setHintTextColor(0xFF94A3B8);
        prompt.setSingleLine(false);
        prompt.setMinLines(4);
        prompt.setMaxLines(7);
        prompt.setBackground(null);
        prompt.setHint("描述你想生成的画面，或去「灵感」挑一个同款…");
        prompt.setText(draft);
        prompt.setSelection(draft.length());
        prompt.setPadding(0, dp(2), 0, dp(10));
        heroCard.addView(prompt, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE9EFF8);
        heroCard.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-1, -2);
        footLp.topMargin = dp(10);

        TextView counter = UiKit.text(this, draft.codePointCount(0, draft.length()) + " / 4000", 11.5f, UiKit.MUTED, false);
        foot.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout clearBtn = UiKit.pillTagButton(this, R.drawable.ic_close, "清空", UiKit.SOFT, UiKit.MUTED, () -> {
            draft = "";
            if (prompt != null) prompt.setText("");
        });
        clearBtn.setVisibility(draft.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(-2, -2);
        clrLp.rightMargin = dp(6);
        foot.addView(clearBtn, clrLp);

        LinearLayout refBtn = UiKit.pillTagButton(this, R.drawable.ic_image_plus,
                reference == null ? "参考图" : "换参考图", UiKit.BLUE_SOFT, UiKit.BLUE, this::pickReference);
        foot.addView(refBtn);
        heroCard.addView(foot, footLp);

        prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String value = s.toString();
                draft = value;
                counter.setText(value.codePointCount(0, value.length()) + " / 4000");
                clearBtn.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        add(heroCard, -2, 14);

        // 轻盈模型分段控制行
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.addView(UiKit.text(this, "生成模型", 13, UiKit.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout modelSeg = UiKit.segmentedControl(this, models, model, index -> {
            if (model != index) { model = index; home(); }
        });
        modelRow.addView(modelSeg, new LinearLayout.LayoutParams(dp(184), -2));
        add(modelRow, -2, 14);

        // 5 格可视化比例胶囊卡片
        LinearLayout ratioRow = new LinearLayout(this);
        ratioRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ratios.length; i++) {
            final int idx = i;
            LinearLayout card = UiKit.ratioCard(this, ratios[i], idx == ratio, () -> {
                if (ratio != idx) { ratio = idx; home(); }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) rlp.leftMargin = dp(7);
            ratioRow.addView(card, rlp);
        }
        add(ratioRow, -2, 10);

        // Grok 专属液态玻璃 NSFW 开关行
        if (isGrok()) add(nsfwCard(), -2, 10);

        // Apple Intelligence 浅色流光主操作药丸按钮
        int ctaIcon = reference == null ? R.drawable.ic_sparkles : R.drawable.ic_wand;
        String ctaLabel = busy ? "正在处理…"
                : pending ? "继续获取上次作品"
                : reference == null ? "生成图片" : "按描述修改参考图";
        add(UiKit.prismButton(this, ctaIcon, ctaLabel, this::generate), 52, 14);

        if (!service.isConfigured()) {
            add(UiKit.text(this, "预览版 · 尚未连接生图服务", 12, UiKit.MUTED, false), -2, 8);
        }

        // Apple Photos 风格最近作品无边框画廊
        LinearLayout recentHead = new LinearLayout(this);
        recentHead.setOrientation(LinearLayout.HORIZONTAL);
        recentHead.setGravity(Gravity.CENTER_VERTICAL);
        recentHead.addView(UiKit.text(this, "最近作品", 15, UiKit.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView viewAll = UiKit.text(this, "全部 ›", 13, UiKit.BLUE, true);
        viewAll.setPadding(dp(8), dp(4), dp(2), dp(4));
        viewAll.setOnClickListener(v -> library());
        recentHead.addView(viewAll);
        add(recentHead, -2, 20);

        add(recentPhotosStrip(), -2, 10);
    }

    private LinearLayout nsfwCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.liquidGlass(this, 18));
        UiKit.applySoftShadow(row, this, 4, 0x1E182638);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true);

        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 11));
        iconBox.addView(UiKit.icon(this, R.drawable.ic_shield, 17, UiKit.BLUE));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(UiKit.text(this, "允许 NSFW", 13.5f, UiKit.INK, true));
        TextView sub = UiKit.text(this, "仅 Grok 支持 · 默认关闭", 11, UiKit.MUTED, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(2);
        texts.addView(sub, slp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(10);
        row.addView(texts, tlp);

        Switch toggle = new Switch(this);
        toggle.setChecked(nsfw);
        toggle.setOnCheckedChangeListener((b, checked) -> nsfw = checked);
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }

    private View recentPhotosStrip() {
        String[] ids = listLocalArtworkIds();
        if (ids.length == 0) {
            return fullBleedLocalTile("", null, 148, "内置示例");
        }
        File dir = new File(getFilesDir(), "images");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count = Math.min(2, ids.length);
        for (int i = 0; i < count; i++) {
            String id = ids[i];
            View tile = fullBleedLocalTile(id, new File(dir, id), 148, artworkBadge(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(148), 1);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(tile, lp);
        }
        if (count == 1) {
            View sample = fullBleedLocalTile("", null, 148, "内置示例");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(148), 1);
            lp.leftMargin = dp(10);
            row.addView(sample, lp);
        }
        return row;
    }

    private FrameLayout fullBleedLocalTile(String id, File file, int heightDp, String badgeText) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(UiKit.round(this, UiKit.SOFT, 22));
        tile.setClipToOutline(true);
        UiKit.applySoftShadow(tile, this, 6, 0x2A182638);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> detail(id));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (file == null) img.setImageResource(R.drawable.coast);
        else InspirationRepository.loadLocalThumb(img, file, 520);
        tile.addView(img, new FrameLayout.LayoutParams(-1, dp(heightDp)));

        if (badgeText != null && !badgeText.isEmpty()) {
            TextView tag = UiKit.frostedBadge(this, badgeText);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
            tlp.gravity = Gravity.BOTTOM | Gravity.START;
            tlp.leftMargin = dp(10);
            tlp.bottomMargin = dp(10);
            tile.addView(tag, tlp);
        }
        return tile;
    }

    private void loadReferenceThumb(ImageView view, Uri uri) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = service.openUri(uri)) {
                    if (in == null) return;
                    BitmapFactory.decodeStream(in, null, o);
                }
                if (o.outWidth <= 0 || o.outHeight <= 0) return;
                o.inSampleSize = 1;
                while ((long) Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 256) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                Bitmap bmp;
                try (InputStream in = service.openUri(uri)) {
                    if (in == null) return;
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                if (bmp != null) main.post(() -> view.setImageBitmap(bmp));
            } catch (Exception ignored) { }
        }, "jianyu-ref-thumb").start();
    }

    // ==================== 03 浅色虹彩动态光球 · 生成中 ====================

    private void loading() {
        layout("loading", true);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::onBackPressed),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = UiKit.text(this, service.hasPending() ? "正在获取作品" : "正在创作", 16, UiKit.INK, true);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(new View(this), new LinearLayout.LayoutParams(dp(40), dp(1)));
        add(topBar, -2, 0);

        // Apple Image Playground 浅色动态虹彩玻璃光球
        LinearLayout orbHolder = new LinearLayout(this);
        orbHolder.setOrientation(LinearLayout.VERTICAL);
        orbHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        AuroraOrbView orb = new AuroraOrbView(this);
        orbHolder.addView(orb, new LinearLayout.LayoutParams(dp(220), dp(220)));

        TextView status = UiKit.text(this, "正在绘制画面…", 20, UiKit.INK, true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        orbHolder.addView(status, slp);

        TextView sub = UiKit.text(this, "离开此页也会自动存入作品库", 13, UiKit.MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(6);
        orbHolder.addView(sub, subLp);
        add(orbHolder, -2, 12);

        // 本次创作摘要毛玻璃卡
        String summaryPrompt = !activePrompt.isEmpty() ? activePrompt : draft.trim();
        if (!summaryPrompt.isEmpty()) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiKit.liquidGlass(this, 22));
            UiKit.applySoftShadow(card, this, 6, 0x20182638);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.addView(UiKit.text(this, "本次画面描述", 12, UiKit.MUTED, true));

            TextView desc = UiKit.text(this, summaryPrompt, 13.5f, UiKit.INK, false);
            desc.setMaxLines(4);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = dp(6);
            card.addView(desc, dlp);

            LinearLayout tags = new LinearLayout(this);
            tags.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.topMargin = dp(10);
            String m = !activeModel.isEmpty() ? activeModel : models[model];
            String r = !activeRatio.isEmpty() ? activeRatio : ratios[ratio];
            tags.addView(UiKit.softBadge(this, m, UiKit.BLUE_SOFT, UiKit.BLUE));
            TextView rBadge = UiKit.softBadge(this, r, UiKit.SOFT, UiKit.MUTED);
            LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-2, -2);
            rbp.leftMargin = dp(6);
            tags.addView(rBadge, rbp);
            if (activeHasRef) {
                TextView refBadge = UiKit.softBadge(this, "图改图", UiKit.SOFT, UiKit.MUTED);
                LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(-2, -2);
                fbp.leftMargin = dp(6);
                tags.addView(refBadge, fbp);
            }
            card.addView(tags, tlp);
            add(card, -2, 18);
        }

        LinearLayout navBtns = new LinearLayout(this);
        navBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout goInspire = UiKit.glassButton(this, R.drawable.ic_compass, "逛逛灵感", UiKit.GLASS_STRONG, UiKit.BLUE, this::inspire);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp1.rightMargin = dp(6);
        navBtns.addView(goInspire, lp1);

        LinearLayout goLib = UiKit.glassButton(this, R.drawable.ic_images, "查看作品库", UiKit.GLASS_STRONG, UiKit.INK, this::library);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp2.leftMargin = dp(6);
        navBtns.addView(goLib, lp2);
        add(navBtns, -2, 14);
    }

    // ==================== 04 & 05 灵感频道（创意广场 + 提示词画廊） ====================

    private void inspire() {
        layout("inspire", true);

        LinearLayout refreshBtn = UiKit.circleIconBtn(this, R.drawable.ic_refresh, UiKit.BLUE, UiKit.GLASS_STRONG, () -> {
            if (inspireTab == 0) fetchPlaza(true);
            else fetchGallery(true);
        });
        refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        add(UiKit.styleOneHeader(this, "灵感", refreshBtn), -2, 0);

        LinearLayout switcher = UiKit.segmentedControl(this, new String[]{"创意广场", "提示词画廊"}, inspireTab, index -> {
            if (inspireTab != index) { inspireTab = index; inspire(); }
        });
        add(switcher, -2, 14);

        if (inspireTab == 0) renderPlazaSection();
        else renderGallerySection();
    }

    private void renderPlazaSection() {
        if (plazaItems.isEmpty() && !plazaLoading && plazaError.isEmpty()) {
            fetchPlaza(true);
            return;
        }
        if (plazaLoading && plazaItems.isEmpty()) {
            add(loadingHintCard("正在加载创意广场…"), -2, 18);
            return;
        }
        if (!plazaError.isEmpty() && plazaItems.isEmpty()) {
            add(emptyCard("加载失败", plazaError, "重新加载", () -> fetchPlaza(true)), -2, 18);
            return;
        }

        add(buildStaggeredPlazaGrid(plazaItems), -2, 14);

        if (plazaLoading) {
            TextView loading = UiKit.text(this, "正在加载更多灵感…", 12.5f, UiKit.MUTED, false);
            loading.setGravity(Gravity.CENTER);
            add(loading, -2, 14);
        } else if (plazaHasMore) {
            add(UiKit.glassButton(this, R.drawable.ic_refresh, "加载更多灵感", UiKit.GLASS_STRONG, UiKit.BLUE, () -> fetchPlaza(false)), 46, 16);
        } else if (!plazaItems.isEmpty()) {
            TextView end = UiKit.text(this, "已经到底啦", 12, UiKit.MUTED, false);
            end.setGravity(Gravity.CENTER);
            add(end, -2, 14);
        }
    }

    private void renderGallerySection() {
        if (galleryData == null && !galleryLoading && galleryError.isEmpty()) {
            fetchGallery(false);
            return;
        }
        if (galleryLoading && galleryData == null) {
            add(loadingHintCard("首次拉取 5,400+ 提示词画廊并缓存离线可用…"), -2, 18);
            return;
        }
        if (!galleryError.isEmpty() && galleryData == null) {
            add(emptyCard("加载失败", galleryError, "重新加载", () -> fetchGallery(true)), -2, 18);
            return;
        }

        // iOS Spotlight 风格毛玻璃药丸搜索栏
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(UiKit.liquidGlass(this, 24));
        UiKit.applySoftShadow(searchBar, this, 4, 0x1A182638);
        searchBar.setPadding(dp(14), dp(4), dp(8), dp(4));
        searchBar.addView(UiKit.icon(this, R.drawable.ic_search, 16, UiKit.MUTED));

        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setBackground(null);
        searchInput.setTextSize(13.5f);
        searchInput.setTextColor(UiKit.INK);
        searchInput.setHintTextColor(0xFF94A3B8);
        searchInput.setHint("搜索 5,443 条提示词、风格、分类…");
        searchInput.setText(galleryQuery);
        searchInput.setSelection(galleryQuery.length());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            galleryQuery = searchInput.getText().toString().trim();
            galleryVisibleCount = GALLERY_PAGE_SIZE;
            inspire();
            return true;
        });
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(0, dp(38), 1);
        sip.leftMargin = dp(8);
        searchBar.addView(searchInput, sip);

        if (!galleryQuery.isEmpty()) {
            LinearLayout clr = new LinearLayout(this);
            clr.setGravity(Gravity.CENTER);
            clr.setPadding(dp(6), dp(6), dp(6), dp(6));
            clr.addView(UiKit.icon(this, R.drawable.ic_close, 14, UiKit.MUTED));
            clr.setOnClickListener(v -> {
                galleryQuery = "";
                galleryVisibleCount = GALLERY_PAGE_SIZE;
                inspire();
            });
            searchBar.addView(clr);
        }
        TextView go = UiKit.text(this, "搜索", 12, Color.WHITE, true);
        go.setBackground(UiKit.round(this, UiKit.BLUE, 14));
        go.setPadding(dp(12), dp(6), dp(12), dp(6));
        go.setOnClickListener(v -> {
            galleryQuery = searchInput.getText().toString().trim();
            galleryVisibleCount = GALLERY_PAGE_SIZE;
            inspire();
        });
        searchBar.addView(go);
        add(searchBar, -2, 12);

        // 横向大类胶囊
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chips);
        for (int i = 0; i < galleryData.majors.size(); i++) {
            String major = galleryData.majors.get(i);
            TextView chip = UiKit.chip(this, major, major.equals(galleryMajor), () -> {
                if (!major.equals(galleryMajor)) {
                    galleryMajor = major;
                    galleryVisibleCount = GALLERY_PAGE_SIZE;
                    inspire();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) clp.leftMargin = dp(7);
            chips.addView(chip, clp);
        }
        add(chipsScroll, -2, 12);

        List<InspirationRepository.Item> filtered = InspirationRepository.filterGallery(galleryData, galleryMajor, galleryQuery);
        add(UiKit.text(this, "共 " + filtered.size() + " 条灵感 · 已缓存离线秒开", 11.5f, UiKit.MUTED, false), -2, 10);

        if (filtered.isEmpty()) {
            add(emptyCard("没有找到匹配的提示词", "试试切换分类或换个关键词搜索。", "重置筛选", () -> {
                galleryMajor = "全部";
                galleryQuery = "";
                galleryVisibleCount = GALLERY_PAGE_SIZE;
                inspire();
            }), -2, 14);
            return;
        }

        int showCount = Math.min(galleryVisibleCount, filtered.size());
        add(buildStaggeredGalleryGrid(filtered.subList(0, showCount)), -2, 10);

        if (showCount < filtered.size()) {
            int remaining = filtered.size() - showCount;
            add(UiKit.glassButton(this, R.drawable.ic_refresh, "加载更多（剩余 " + remaining + " 条）", UiKit.GLASS_STRONG, UiKit.BLUE, () -> {
                galleryVisibleCount += GALLERY_PAGE_SIZE;
                inspire();
            }), 46, 16);
        }
    }

    /** 无边框全出血双列错落瀑布流（创意广场） */
    private LinearLayout buildStaggeredPlazaGrid(List<InspirationRepository.Item> items) {
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < items.size(); i++) {
            InspirationRepository.Item item = items.get(i);
            boolean isLeft = (i % 2 == 0);
            int h = ((i % 4 == 0 || i % 4 == 3) ? 206 : 168);
            FrameLayout tile = fullBleedPlazaTile(item, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(h));
            if (i >= 2) lp.topMargin = dp(10);
            if (isLeft) leftCol.addView(tile, lp);
            else rightCol.addView(tile, lp);
        }

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1);
        llp.rightMargin = dp(5);
        columns.addView(leftCol, llp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1);
        rlp.leftMargin = dp(5);
        columns.addView(rightCol, rlp);
        return columns;
    }

    private FrameLayout fullBleedPlazaTile(InspirationRepository.Item item, int heightDp) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(UiKit.round(this, UiKit.SOFT, 22));
        tile.setClipToOutline(true);
        UiKit.applySoftShadow(tile, this, 6, 0x26182638);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> inspireDetail(item));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        InspirationRepository.loadImage(this, img, item.thumbUrl, 520);
        tile.addView(img, new FrameLayout.LayoutParams(-1, dp(heightDp)));

        // 左上角磨砂参数微胶囊
        if (!item.model.isEmpty()) {
            TextView tag = UiKit.frostedBadge(this, item.model + " · " + item.ratio);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
            tlp.gravity = Gravity.TOP | Gravity.START;
            tlp.leftMargin = dp(9);
            tlp.topMargin = dp(9);
            tile.addView(tag, tlp);
        }

        // 底部极薄暗色磨砂浮条 + 一步「同款」微胶囊
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UiKit.darkFrosted(this, 16));
        bar.setPadding(dp(10), dp(6), dp(6), dp(6));

        TextView author = UiKit.text(this, item.author.isEmpty() ? "广场作品" : item.author, 11, Color.WHITE, false);
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(author, new LinearLayout.LayoutParams(0, -2, 1));

        TextView quickSame = UiKit.text(this, "同款", 10.5f, UiKit.BLUE, true);
        quickSame.setBackground(UiKit.round(this, 0xEEFFFFFF, 11));
        quickSame.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));
        quickSame.setOnClickListener(v -> applyInspirationToHome(item));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
        qlp.leftMargin = dp(6);
        bar.addView(quickSame, qlp);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        blp.gravity = Gravity.BOTTOM;
        blp.leftMargin = dp(7);
        blp.rightMargin = dp(7);
        blp.bottomMargin = dp(7);
        tile.addView(bar, blp);
        return tile;
    }

    /** 无边框全出血双列错落瀑布流（提示词画廊） */
    private LinearLayout buildStaggeredGalleryGrid(List<InspirationRepository.Item> items) {
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < items.size(); i++) {
            InspirationRepository.Item item = items.get(i);
            boolean isLeft = (i % 2 == 0);
            int h = ((i % 4 == 0 || i % 4 == 3) ? 204 : 172);
            FrameLayout tile = fullBleedGalleryTile(item, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(h));
            if (i >= 2) lp.topMargin = dp(10);
            if (isLeft) leftCol.addView(tile, lp);
            else rightCol.addView(tile, lp);
        }

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1);
        llp.rightMargin = dp(5);
        columns.addView(leftCol, llp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1);
        rlp.leftMargin = dp(5);
        columns.addView(rightCol, rlp);
        return columns;
    }

    private FrameLayout fullBleedGalleryTile(InspirationRepository.Item item, int heightDp) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(UiKit.round(this, UiKit.SOFT, 22));
        tile.setClipToOutline(true);
        UiKit.applySoftShadow(tile, this, 6, 0x26182638);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> inspireDetail(item));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        InspirationRepository.loadImage(this, img, item.thumbUrl, 520);
        tile.addView(img, new FrameLayout.LayoutParams(-1, dp(heightDp)));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackground(UiKit.scrimBottom(22, this));
        overlay.setPadding(dp(11), dp(26), dp(11), dp(11));

        TextView title = UiKit.text(this, item.title.isEmpty() ? item.prompt : item.title, 12.5f, Color.WHITE, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        overlay.addView(title);

        String cat = !item.category.isEmpty() ? item.category : item.major;
        if (!cat.isEmpty()) {
            TextView sub = UiKit.text(this, cat, 10.5f, 0xD9FFFFFF, false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dp(2);
            overlay.addView(sub, slp);
        }
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(-1, -2);
        olp.gravity = Gravity.BOTTOM;
        tile.addView(overlay, olp);
        return tile;
    }

    // ==================== 06 灵感详情 · 沉浸环境光 ====================

    private void inspireDetail(InspirationRepository.Item item) {
        activeInspiration = item;
        layout("inspire_detail", true);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(UiKit.round(this, UiKit.SOFT, 28));
        frame.setClipToOutline(true);
        UiKit.applySoftShadow(frame, this, 16, 0x553B74E6);

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setMinimumHeight(dp(260));
        InspirationRepository.loadImage(this, image, item.imageUrl, 1600);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2));

        // 顶部左右悬浮圆形毛玻璃图标按钮
        LinearLayout backCircle = UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::onBackPressed);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(40), dp(40));
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.leftMargin = dp(12);
        blp.topMargin = dp(12);
        frame.addView(backCircle, blp);

        LinearLayout saveCircle = UiKit.circleIconBtn(this, R.drawable.ic_download, UiKit.INK, UiKit.GLASS_STRONG, () -> exportRemoteImage(item));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(40), dp(40));
        slp.gravity = Gravity.TOP | Gravity.END;
        slp.rightMargin = dp(12);
        slp.topMargin = dp(12);
        frame.addView(saveCircle, slp);

        String corner = !item.model.isEmpty() ? item.model + " · " + item.ratio
                : (!item.major.isEmpty() ? item.major + " · " + item.ratio : item.ratio);
        TextView cornerTag = UiKit.frostedBadge(this, corner);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.BOTTOM | Gravity.START;
        clp.leftMargin = dp(12);
        clp.bottomMargin = dp(12);
        frame.addView(cornerTag, clp);
        add(frame, -2, 0);

        if (!item.title.isEmpty() || !item.author.isEmpty()) {
            LinearLayout metaRow = new LinearLayout(this);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            String heading = !item.title.isEmpty() ? item.title : "广场灵感作品";
            metaRow.addView(UiKit.text(this, heading, 18, UiKit.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            String rightMeta = !item.author.isEmpty() ? "by " + item.author : item.category;
            if (!rightMeta.isEmpty()) metaRow.addView(UiKit.text(this, rightMeta, 12, UiKit.MUTED, false));
            add(metaRow, -2, 14);
        }

        add(promptDetailCard(item.prompt), -2, 12);
        add(UiKit.prismButton(this, R.drawable.ic_sparkles, "一键做同款", () -> applyInspirationToHome(item)), 52, 16);

        LinearLayout subActions = new LinearLayout(this);
        subActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout asRef = UiKit.glassButton(this, R.drawable.ic_image_plus, "设为参考图", UiKit.GLASS_STRONG, UiKit.BLUE, () -> useRemoteAsReference(item));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp1.rightMargin = dp(6);
        subActions.addView(asRef, lp1);

        LinearLayout saveBtn = UiKit.glassButton(this, R.drawable.ic_download, "保存原图", UiKit.GLASS_STRONG, UiKit.INK, () -> exportRemoteImage(item));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp2.leftMargin = dp(6);
        subActions.addView(saveBtn, lp2);
        add(subActions, -2, 10);
    }

    private void applyInspirationToHome(InspirationRepository.Item item) {
        draft = item.prompt;
        if ("Grok".equals(item.model)) {
            setModelByName("Grok");
            nsfw = item.nsfw;
        } else if ("GPT".equals(item.model)) {
            setModelByName("GPT");
        }
        setRatioByName(item.ratio);
        reference = null;
        Toast.makeText(this, "已填入同款提示词与参数", Toast.LENGTH_SHORT).show();
        home();
    }

    private void useRemoteAsReference(InspirationRepository.Item item) {
        Toast.makeText(this, "正在准备参考图…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Uri cached = service.cacheRemoteReference(item.imageUrl);
                main.post(() -> {
                    MainActivity act = visible;
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    act.reference = cached;
                    act.setRatioByName(item.ratio);
                    Toast.makeText(act, "已设为参考图，可输入修改描述", Toast.LENGTH_SHORT).show();
                    act.home();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "无法加载参考图。" : e.getMessage();
                main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            }
        }, "jianyu-remote-ref").start();
    }

    private void exportRemoteImage(InspirationRepository.Item item) {
        String url = item.imageUrl.toLowerCase(Locale.ROOT);
        String ext = url.endsWith(".png") ? "png" : url.endsWith(".webp") ? "webp" : "jpg";
        String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "见屿-灵感-" + item.id + "." + ext);
        startActivityForResult(intent, SAVE_REMOTE_IMAGE);
    }

    private void fetchPlaza(boolean reset) {
        if (plazaLoading) return;
        plazaLoading = true;
        plazaError = "";
        int nextPage = reset ? 1 : plazaPage + 1;
        if (reset) {
            plazaItems.clear();
            plazaPage = 0;
            plazaHasMore = true;
        }
        if ("inspire".equals(screen) && inspireTab == 0) inspire();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                InspirationRepository.PageResult result = InspirationRepository.fetchPlazaPage(nextPage, PLAZA_PAGE_SIZE);
                main.post(() -> {
                    plazaLoading = false;
                    plazaPage = nextPage;
                    plazaHasMore = result.hasMore;
                    plazaItems.addAll(result.items);
                    MainActivity act = visible;
                    if (act != null && "inspire".equals(act.screen) && act.inspireTab == 0) act.inspire();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "创意广场加载失败，请检查网络。" : e.getMessage();
                main.post(() -> {
                    plazaLoading = false;
                    plazaError = msg;
                    MainActivity act = visible;
                    if (act != null && "inspire".equals(act.screen) && act.inspireTab == 0) act.inspire();
                });
            }
        }, "jianyu-plaza").start();
    }

    private void fetchGallery(boolean forceRefresh) {
        if (galleryLoading) return;
        galleryLoading = true;
        galleryError = "";
        if (forceRefresh) galleryData = null;
        if ("inspire".equals(screen) && inspireTab == 1) inspire();
        Context appContext = getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                InspirationRepository.GalleryData data = InspirationRepository.loadGallery(appContext, forceRefresh);
                main.post(() -> {
                    galleryLoading = false;
                    galleryData = data;
                    MainActivity act = visible;
                    if (act != null && "inspire".equals(act.screen) && act.inspireTab == 1) act.inspire();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "提示词画廊加载失败，请检查网络。" : e.getMessage();
                main.post(() -> {
                    galleryLoading = false;
                    galleryError = msg;
                    MainActivity act = visible;
                    if (act != null && "inspire".equals(act.screen) && act.inspireTab == 1) act.inspire();
                });
            }
        }, "jianyu-gallery").start();
    }

    // ==================== 07 作品库 · Apple Photos 无边框相册 ====================

    private void library() {
        layout("library", true);
        String[] allIds = listLocalArtworkIds();

        TextView count = UiKit.softBadge(this, allIds.length + " 幅", UiKit.GLASS_STRONG, UiKit.MUTED);
        add(UiKit.styleOneHeader(this, "作品库", count), -2, 0);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] filters = {"全部", "GPT", "Grok"};
        for (int i = 0; i < filters.length; i++) {
            final int idx = i;
            TextView chip = UiKit.chip(this, filters[i], idx == libraryFilter, () -> {
                if (libraryFilter != idx) { libraryFilter = idx; library(); }
            });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) flp.leftMargin = dp(8);
            filterRow.addView(chip, flp);
        }
        add(filterRow, -2, 14);

        List<String> filtered = new ArrayList<>();
        for (String id : allIds) {
            if (libraryFilter == 0) filtered.add(id);
            else {
                String m = service.modelLabel(id);
                if (libraryFilter == 1 && "GPT".equals(m)) filtered.add(id);
                else if (libraryFilter == 2 && "Grok".equals(m)) filtered.add(id);
            }
        }

        if (filtered.isEmpty()) {
            String title = allIds.length == 0 ? "作品库还是空的" : "该筛选下暂无作品";
            String desc = allIds.length == 0
                    ? "生成的图片会自动保存在本机，随时可欣赏、导出或再创作。"
                    : "切换到「全部」查看已有作品，或去创作一幅新图。";
            add(emptyCard(title, desc, "开始创作", this::home), -2, 18);
            return;
        }

        long startOfToday = startOfDayMillis(System.currentTimeMillis());
        long startOfYesterday = startOfToday - 24L * 60 * 60 * 1000;
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : filtered) {
            long ts = extractTimestamp(id);
            String label = ts >= startOfToday ? "今天" : ts >= startOfYesterday ? "昨天" : "更早";
            List<String> bucket = groups.get(label);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(label, bucket);
            }
            bucket.add(id);
        }

        File dir = new File(getFilesDir(), "images");
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            add(UiKit.text(this, entry.getKey(), 13, UiKit.MUTED, true), -2, 16);
            add(buildPhotosGrid(dir, entry.getValue()), -2, 10);
        }
    }

    private LinearLayout buildPhotosGrid(File dir, List<String> ids) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ids.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = dp(10);

            String leftId = ids.get(i);
            FrameLayout left = fullBleedLocalTile(leftId, new File(dir, leftId), 178, artworkBadge(leftId));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, dp(178), 1);
            llp.rightMargin = dp(5);
            row.addView(left, llp);

            if (i + 1 < ids.size()) {
                String rightId = ids.get(i + 1);
                FrameLayout right = fullBleedLocalTile(rightId, new File(dir, rightId), 178, artworkBadge(rightId));
                LinearLayout.LayoutParams rrp = new LinearLayout.LayoutParams(0, dp(178), 1);
                rrp.leftMargin = dp(5);
                row.addView(right, rrp);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(1), 1);
                sp.leftMargin = dp(5);
                row.addView(spacer, sp);
            }
            grid.addView(row, rlp);
        }
        return grid;
    }

    // ==================== 08 作品详情 · 环境光沉浸 ====================

    private void detail(String id) {
        current = id;
        layout("detail", true);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(UiKit.round(this, UiKit.SOFT, 28));
        frame.setClipToOutline(true);
        UiKit.applySoftShadow(frame, this, 18, 0x5A3B74E6);

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        if (id.isEmpty()) {
            image.setImageResource(R.drawable.coast);
        } else {
            File f = new File(new File(getFilesDir(), "images"), id);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while ((long) Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 1800) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(f.getPath(), o);
            if (bitmap == null) { library(); return; }
            image.setImageBitmap(bitmap);
        }
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2));

        // 顶部左右悬浮圆形毛玻璃图标按钮
        LinearLayout backCircle = UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::onBackPressed);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(40), dp(40));
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.leftMargin = dp(12);
        blp.topMargin = dp(12);
        frame.addView(backCircle, blp);

        if (!id.isEmpty()) {
            LinearLayout delCircle = UiKit.circleIconBtn(this, R.drawable.ic_trash, UiKit.DANGER, 0xE6FFF0F0, () ->
                    new AlertDialog.Builder(this)
                            .setTitle("删除作品")
                            .setMessage("确定从本机作品库删除这幅作品吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (d, w) -> {
                                service.deleteArtwork(id);
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                                library();
                            }).show());
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(40), dp(40));
            dlp.gravity = Gravity.TOP | Gravity.END;
            dlp.rightMargin = dp(12);
            dlp.topMargin = dp(12);
            frame.addView(delCircle, dlp);
        }

        String badgeText = id.isEmpty() ? "内置示例" : artworkBadge(id);
        if (!badgeText.isEmpty()) {
            TextView tag = UiKit.frostedBadge(this, badgeText);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
            tlp.gravity = Gravity.BOTTOM | Gravity.START;
            tlp.leftMargin = dp(12);
            tlp.bottomMargin = dp(12);
            frame.addView(tag, tlp);
        }
        add(frame, -2, 0);

        String promptText = description(id);
        add(promptDetailCard(promptText), -2, 16);

        add(UiKit.prismButton(this, R.drawable.ic_download, "保存至相册", () -> {
            String ext = id.isEmpty() ? "jpg" : id.substring(id.lastIndexOf('.') + 1);
            String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType(mime)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "见屿-" + (id.isEmpty() ? "海岸晨光.jpg" : id));
            startActivityForResult(intent, SAVE_LOCAL_IMAGE);
        }), 52, 16);

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout remix = UiKit.glassButton(this, R.drawable.ic_sparkles, "同款再创作", UiKit.GLASS_STRONG, UiKit.BLUE, () -> {
            draft = promptText;
            String m = service.modelLabel(id);
            String r = service.ratioLabel(id);
            if (!m.isEmpty()) setModelByName(m);
            if (!r.isEmpty()) setRatioByName(r);
            reference = null;
            Toast.makeText(this, "已回填画面描述与参数", Toast.LENGTH_SHORT).show();
            home();
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp1.rightMargin = dp(6);
        secondaryRow.addView(remix, lp1);

        LinearLayout asRef = UiKit.glassButton(this, R.drawable.ic_image_plus, "设为参考图", UiKit.GLASS_STRONG, UiKit.INK, () -> {
            if (id.isEmpty()) {
                Toast.makeText(this, "内置示例仅支持同款再创作", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = new File(new File(getFilesDir(), "images"), id);
            if (!file.isFile()) {
                Toast.makeText(this, "作品文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            reference = Uri.fromFile(file);
            Toast.makeText(this, "已设为参考图，可输入修改描述", Toast.LENGTH_SHORT).show();
            home();
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp2.leftMargin = dp(6);
        secondaryRow.addView(asRef, lp2);
        add(secondaryRow, -2, 10);
    }

    private LinearLayout promptDetailCard(String promptText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.liquidGlass(this, 22));
        UiKit.applySoftShadow(card, this, 6, 0x1E182638);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiKit.text(this, "画面描述", 12.5f, UiKit.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiKit.pillTagButton(this, R.drawable.ic_copy, "复制", UiKit.BLUE_SOFT, UiKit.BLUE, () -> copyText(promptText)));
        card.addView(head);

        TextView body = UiKit.text(this, promptText, 13.5f, UiKit.INK, false);
        body.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(8);
        card.addView(body, blp);
        return card;
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("prompt", text));
            Toast.makeText(this, "已复制提示词", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 09 AirPods 风格四边留白悬浮玻璃更新卡 ====================

    private void checkUpdate(boolean silent) {
        if (checkingUpdate) {
            if (!silent) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
            return;
        }
        checkingUpdate = true;
        if (!silent) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                UpdateManager.UpdateInfo info = UpdateManager.checkForUpdate();
                main.post(() -> {
                    checkingUpdate = false;
                    MainActivity activity = visible;
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    if (info.hasUpdate) activity.showUpdateDialog(info);
                    else if (!silent) {
                        new AlertDialog.Builder(activity)
                                .setTitle("已是最新版本")
                                .setMessage("当前版本 v" + info.currentVersion + " 已经是最新版本。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "检查更新失败，请稍后重试。" : e.getMessage();
                main.post(() -> {
                    checkingUpdate = false;
                    MainActivity activity = visible;
                    if (silent || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("检查更新失败")
                            .setMessage(message)
                            .setPositiveButton("重试", (d, w) -> activity.checkUpdate(false))
                            .setNegativeButton("关闭", null)
                            .show();
                });
            }
        }, "jianyu-update-check").start();
    }

    private void showUpdateDialog(UpdateManager.UpdateInfo info) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setBackground(UiKit.floatingPillBg(this, 32));
        UiKit.applySoftShadow(sheet, this, 18, 0x40182638);
        sheet.setPadding(dp(22), dp(14), dp(22), dp(18));

        View handle = new View(this);
        handle.setBackground(UiKit.round(this, 0xFFCBD5E1, 3));
        sheet.addView(handle, new LinearLayout.LayoutParams(dp(38), dp(4)));

        // 全新极光灯塔 Icon + 柔和虹彩光环
        FrameLayout iconHalo = new FrameLayout(this);
        GradientDrawable halo = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x446AA8FF, 0x44C6A4FF, 0x44FFD0B4});
        halo.setCornerRadius(dp(24));
        iconHalo.setBackground(halo);
        iconHalo.setPadding(dp(7), dp(7), dp(7), dp(7));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        iconHalo.addView(icon, new FrameLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = dp(16);
        sheet.addView(iconHalo, ilp);

        TextView title = UiKit.text(this, "发现新版本", 19, UiKit.INK, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(12);
        sheet.addView(title, tlp);

        TextView verPill = UiKit.softBadge(this, "v" + info.currentVersion + "  →  v" + info.latestVersion, UiKit.BLUE_SOFT, UiKit.BLUE);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.topMargin = dp(8);
        sheet.addView(verPill, vlp);

        LinearLayout notesBox = new LinearLayout(this);
        notesBox.setOrientation(LinearLayout.VERTICAL);
        notesBox.setBackground(UiKit.card(this, 0xEEF4F7FC, 0xFFFFFFFF, 16));
        notesBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView notes = UiKit.text(this, info.releaseNotes, 12.5f, UiKit.MUTED, false);
        notes.setLineSpacing(dp(4), 1f);
        notesBox.addView(notes);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(14);
        sheet.addView(notesBox, nlp);

        LinearLayout progressWrap = new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        progressWrap.setVisibility(View.GONE);
        LinearLayout progHead = new LinearLayout(this);
        progHead.setOrientation(LinearLayout.HORIZONTAL);
        TextView progLabel = UiKit.text(this, "正在下载更新…", 12, UiKit.INK, true);
        TextView progPct = UiKit.text(this, "0%", 12, UiKit.BLUE, true);
        progHead.addView(progLabel, new LinearLayout.LayoutParams(0, -2, 1));
        progHead.addView(progPct);
        progressWrap.addView(progHead);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(8);
        progressWrap.addView(bar, blp);
        LinearLayout.LayoutParams pwlp = new LinearLayout.LayoutParams(-1, -2);
        pwlp.topMargin = dp(14);
        sheet.addView(progressWrap, pwlp);

        wrapper.addView(sheet, new FrameLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(wrapper).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextView updateBtnText = UiKit.text(this, "立即更新", 15, Color.WHITE, true);
        LinearLayout updateNow = new LinearLayout(this);
        updateNow.setGravity(Gravity.CENTER);
        updateNow.setBackground(UiKit.prismBg(this, 26));
        UiKit.applySoftShadow(updateNow, this, 8, 0x552F5CE0);
        updateNow.addView(updateBtnText);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, dp(48));
        ulp.topMargin = dp(18);
        sheet.addView(updateNow, ulp);

        TextView later = UiKit.text(this, "稍后再说", 13, UiKit.MUTED, true);
        later.setGravity(Gravity.CENTER);
        later.setPadding(dp(14), dp(10), dp(14), dp(4));
        later.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(6);
        sheet.addView(later, llp);

        updateNow.setOnClickListener(v -> {
            updateNow.setEnabled(false);
            updateBtnText.setText("下载中…");
            later.setVisibility(View.GONE);
            progressWrap.setVisibility(View.VISIBLE);
            dialog.setCancelable(false);
            downloadAndInstall(dialog, info, bar, progLabel, progPct, updateNow, updateBtnText, later);
        });

        dialog.show();
    }

    private void downloadAndInstall(AlertDialog dialog, UpdateManager.UpdateInfo info,
                                    ProgressBar bar, TextView progLabel, TextView progPct,
                                    LinearLayout updateNow, TextView updateBtnText, TextView later) {
        Handler main = new Handler(Looper.getMainLooper());
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                File apk = UpdateManager.downloadApk(appContext, info.downloadUrl, percent ->
                        main.post(() -> {
                            bar.setProgress(percent);
                            progPct.setText(percent + "%");
                        }));
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    MainActivity activity = visible;
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    if (!UpdateManager.installApk(activity, apk)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("新版本已下载")
                                .setMessage("授权“允许安装未知应用”后，点击下方按钮即可覆盖安装 v" + info.latestVersion + "。")
                                .setPositiveButton("立即安装", (d, w) -> UpdateManager.installApk(activity, apk))
                                .setNegativeButton("稍后", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "安装包下载失败，请稍后重试。" : e.getMessage();
                main.post(() -> {
                    dialog.setCancelable(true);
                    updateNow.setEnabled(true);
                    updateBtnText.setText("重试下载");
                    later.setVisibility(View.VISIBLE);
                    progLabel.setText("下载失败：" + message);
                });
            }
        }, "jianyu-update-download").start();
    }

    // ==================== 通用状态卡片 & 生图提交 ====================

    private LinearLayout loadingHintCard(String message) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(UiKit.liquidGlass(this, 22));
        card.setPadding(dp(20), dp(36), dp(20), dp(36));
        card.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView txt = UiKit.text(this, message, 13.5f, UiKit.MUTED, false);
        txt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        card.addView(txt, lp);
        return card;
    }

    private LinearLayout emptyCard(String title, String message, String btnLabel, Runnable onAction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(UiKit.liquidGlass(this, 24));
        card.setPadding(dp(22), dp(32), dp(22), dp(26));
        card.addView(UiKit.text(this, title, 16, UiKit.INK, true));
        TextView sub = UiKit.text(this, message, 13, UiKit.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(8);
        card.addView(sub, slp);
        LinearLayout btn = UiKit.prismButton(this, R.drawable.ic_sparkles, btnLabel, onAction);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(156), dp(44));
        blp.topMargin = dp(16);
        card.addView(btn, blp);
        return card;
    }

    private void pickReference() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
        startActivityForResult(picker, PICK_REFERENCE);
    }

    private void generate() {
        if (busy) return;
        if (!service.isConfigured()) {
            new AlertDialog.Builder(this)
                    .setTitle("生图服务尚未连接")
                    .setMessage("当前可预览示例作品。应用接入服务后即可用自己的提示词生成图片。")
                    .setPositiveButton("查看示例", (d, w) -> detail(""))
                    .setNegativeButton("返回", null)
                    .show();
            return;
        }
        remember();
        String input = draft.trim();
        if (!service.hasPending() && (input.isEmpty() || input.codePointCount(0, input.length()) > 4000)) {
            if (prompt != null) prompt.setError("请输入 1 至 4000 个字符的画面描述");
            return;
        }
        activePrompt = input;
        activeModel = models[model];
        activeRatio = ratios[ratio];
        activeHasRef = reference != null;
        String wireModel = wireModels[model];
        busy = true;
        loading();
        submit(service, input, wireModel, ratios[ratio], reference, isGrok() && nsfw);
    }

    private static void submit(ImageService service, String input, String model, String ratio, Uri reference, boolean nsfw) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String id = service.run(input, model, ratio, reference, nsfw);
                main.post(() -> finished(id, null));
            } catch (Exception e) {
                String message = e instanceof java.net.SocketTimeoutException
                        || e instanceof java.net.SocketException
                        || e instanceof java.net.UnknownHostException
                        ? "网络连接中断，请先核对原任务记录。" : e.getMessage();
                main.post(() -> finished(null, message == null ? "无法获取作品，请检查网络或服务连接。" : message));
            }
        }, "jianyu-image").start();
    }

    private static void finished(String id, String error) {
        busy = false;
        MainActivity activity = visible;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (error != null) {
            activity.home();
            new AlertDialog.Builder(activity).setMessage(error).setPositiveButton("知道了", null).show();
        } else if ("loading".equals(activity.screen)) activity.detail(id);
        else if ("library".equals(activity.screen)) activity.library();
        else if ("home".equals(activity.screen)) activity.home();
    }

    // ==================== 数据与工具方法 ====================

    private String[] listLocalArtworkIds() {
        String[] ids = new File(getFilesDir(), "images")
                .list((dir, n) -> n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp"));
        if (ids == null || ids.length == 0) return new String[0];
        Arrays.sort(ids, Collections.reverseOrder());
        return ids;
    }

    private String description(String id) {
        return id.isEmpty()
                ? "清晨的海岸灯塔，远山隐入薄雾，日光在蓝绿色的海面上铺开。"
                : getSharedPreferences("art", MODE_PRIVATE).getString(id, "生成作品");
    }

    private String artworkBadge(String id) {
        String m = service.modelLabel(id);
        String r = service.ratioLabel(id);
        if (!m.isEmpty() && !r.isEmpty()) return m + " · " + r;
        if (!m.isEmpty()) return m;
        return r;
    }

    private void setModelByName(String name) {
        for (int i = 0; i < models.length; i++) {
            if (models[i].equalsIgnoreCase(name)) { model = i; return; }
        }
    }

    private void setRatioByName(String name) {
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(name)) { ratio = i; return; }
        }
    }

    private static long extractTimestamp(String id) {
        int dash = id.indexOf('-');
        if (dash <= 0) return 0L;
        try { return Long.parseLong(id.substring(0, dash)); } catch (NumberFormatException e) { return 0L; }
    }

    private static long startOfDayMillis(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        if (code == PICK_REFERENCE) {
            reference = intent.getData();
            home();
            return;
        }
        if (code == SAVE_LOCAL_IMAGE) {
            try (InputStream in = current.isEmpty()
                    ? getResources().openRawResource(R.drawable.coast)
                    : new FileInputStream(new File(new File(getFilesDir(), "images"), current));
                 OutputStream out = getContentResolver().openOutputStream(intent.getData())) {
                if (out == null) throw new IOException();
                ImageService.copy(in, out, ImageService.IMAGE_LIMIT, "作品文件过大。");
                Toast.makeText(this, "已保存至相册", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "保存失败，请重新选择位置。", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (code == SAVE_REMOTE_IMAGE && activeInspiration != null) {
            Uri dest = intent.getData();
            String url = activeInspiration.imageUrl;
            Toast.makeText(this, "正在保存原图…", Toast.LENGTH_SHORT).show();
            Handler main = new Handler(Looper.getMainLooper());
            Context appContext = getApplicationContext();
            new Thread(() -> {
                try {
                    File cached = InspirationRepository.ensureCachedImage(appContext, url);
                    try (InputStream in = new FileInputStream(cached);
                         OutputStream out = getContentResolver().openOutputStream(dest)) {
                        if (out == null) throw new IOException();
                        ImageService.copy(in, out, ImageService.IMAGE_LIMIT, "作品文件过大。");
                    }
                    main.post(() -> Toast.makeText(this, "原图已保存", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    main.post(() -> Toast.makeText(this, "保存失败，请稍后重试。", Toast.LENGTH_LONG).show());
                }
            }, "jianyu-save-remote").start();
        }
    }

    @Override public void onBackPressed() {
        if ("home".equals(screen)) super.onBackPressed();
        else if ("inspire_detail".equals(screen)) inspire();
        else if ("detail".equals(screen) && !current.isEmpty()) library();
        else home();
    }
}
