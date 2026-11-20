package studio.jianyu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
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
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.provider.MediaStore;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int SAVE_LOCAL_IMAGE = 1;
    private static final int PICK_REFERENCE = 2;
    private static final int SAVE_REMOTE_IMAGE = 3;
    private static final int REQUEST_NOTIFICATIONS = 4;
    private static final String KEY_NOTIFICATION_REQUESTED = "notification-requested";
    private static final int PLAZA_PAGE_SIZE = 24;
    private static final int GALLERY_PAGE_SIZE = 24;

    private static final String PREFS_NAME = "jianyu-prefs";
    private static final String KEY_SAFE_BROWSE = "safe_browse";

    private static final class OverlayEntry {
        final String tag;
        final View view;
        OverlayEntry(String tag, View view) {
            this.tag = tag;
            this.view = view;
        }
    }

    // 根视图与持久化 3-Tab 容器（切 Tab / 返回详情永不丢滚动位置）
    private FrameLayout root;
    private FrameLayout tabHost;
    private FrameLayout overlayHost;
    private LinearLayout floatingNav;
    private LinearLayout miniGenBar;
    private TextView miniGenText;
    private LinearLayout libraryBatchBar;
    private TextView libraryBatchCount;
    private View[] tabContainers = new View[4];
    private LinearLayout[] navItems = new LinearLayout[4];
    private ImageView[] navIcons = new ImageView[4];
    private TextView[] navLabels = new TextView[4];
    private final List<OverlayEntry> overlayStack = new ArrayList<>();

    // 首页持久化控件
    private LinearLayout heroCardView;
    private View homeUpdateDot;
    private LinearLayout homePendingSlot;
    private LinearLayout homeRefSlot;
    private EditText promptInput;
    private TextView promptCounter;
    private LinearLayout promptClearBtn;
    private TextView promptRefBtnLabel;
    private TextView paramSummaryText;
    private TextView ctaLabelView;
    private ImageView ctaIconView;
    private LinearLayout homeRecentSlot;

    // 提示词工坊持久化控件 (Tab 1)
    private LinearLayout promptSourceSegHolder;
    private int promptSourceIndex = 0; // 0: 全部, 1: 热榜图鉴, 2: 设计精选, 3: 经典画廊, 4: 发现灵感
    private EditText promptSearchInput;
    private View promptSearchClear;
    private LinearLayout promptMajorsRow;
    private TextView promptCountLabel;
    private SwipeRefreshLayout promptSwipe;
    private RecyclerView promptRecycler;
    private FrameLayout promptStateBox;
    private GalleryAdapters.InspirationAdapter promptAdapter;
    private String promptMajor = "全部";
    private String promptQuery = "";
    private int promptVisibleCount = GALLERY_PAGE_SIZE;
    private final PromptOrder<InspirationRepository.Item> promptOrder = new PromptOrder<>();
    private String builtPromptMajorsSignature = "";

    // 创意广场持久化控件 (Tab 2)
    private SwipeRefreshLayout plazaSwipe;
    private RecyclerView plazaRecycler;
    private FrameLayout plazaStateBox;
    private GalleryAdapters.InspirationAdapter plazaAdapter;

    // 作品库持久化控件 (Tab 3)
    private TextView librarySubtitleView;
    private TextView librarySelectBtn;
    private TextView libraryClearRecordsBtn;
    private LinearLayout librarySegHolder;
    private int librarySubTab = 0; // 0: 成品作品, 1: 生成记录, 2: 灵感收藏
    private LinearLayout libraryFilterRow;
    private RecyclerView libraryRecycler;
    private RecyclerView recordsRecycler;
    private RecyclerView favsRecycler;
    private FrameLayout libraryEmptyBox;
    private GalleryAdapters.LocalLibraryAdapter libraryAdapter;
    private GalleryAdapters.RecordAdapter recordAdapter;
    private GalleryAdapters.InspirationAdapter favsAdapter;

    // 后台生成计时器
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView loadingTimerView;
    private TextView loadingStatusView;
    private final Runnable genTimerTick = new Runnable() {
        @Override public void run() {
            int count = GenerationTasks.count();
            if (count == 0) return;
            if (miniGenText != null) miniGenText.setText(count + " 条任务进行中 · 查看进度");
            if (recordAdapter != null && activeTab == 3 && librarySubTab == 1) recordAdapter.refreshActiveStatuses();
            GenerationTasks.Task task = GenerationTasks.get(loadingRecordId);
            if (loadingStatusView != null && task != null) loadingStatusView.setText(GenerationRecord.statusLabel(task.record.status));
            if (loadingTimerView != null && task != null) {
                long sec = Math.max(1L, (android.os.SystemClock.elapsedRealtime() - task.startedAt) / 1000L);
                loadingTimerView.setText(task.record.status == GenerationRecord.STATUS_DOWNLOADING
                        ? task.service.downloadProgress() : "已等待 " + sec + " 秒 · 完成后自动存入作品库");
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    // 状态字段
    private int activeTab = 0; // 0: 创作, 1: 提示词, 2: 广场, 3: 作品库
    private boolean imeVisible = false;
    private String draft = "", current = "";
    private int selectedRoute = 0; // 0: 线路 1, 1: 线路 2
    private int modelLine1 = 0; // 0: GPT, 1: Grok
    private int modelLine2 = 0; // 0: GPT-2.5, 1: GPT-2.5 Flare, 2: GPT-2.5 Sunburst, 3: GPT-2
    private int ratio;
    private boolean nsfw;
    private boolean safeBrowse = true;
    private int libraryFilter = 0; // 0: 全部, 1: GPT, 2: Grok
    private boolean librarySelectMode = false;
    private final Set<String> librarySelected = new LinkedHashSet<>();

    private String[] routes, modelsLine1, wireModelsLine1, modelsLine2, wireModelsLine2, ratios;
    private Uri reference;
    private ImageService service;
    private SharedPreferences prefs;

    private String loadingRecordId = "";
    private static boolean checkingUpdate = false;
    private static boolean autoCheckedUpdate = false;
    private static UpdateManager.UpdateInfo availableUpdate = null;
    private static MainActivity visible = null;

    private static final List<InspirationRepository.Item> plazaItems = new ArrayList<>();
    private static int plazaPage = 0;
    private static boolean plazaHasMore = true;
    private static boolean plazaLoading = false;
    private static String plazaError = "";

    private static final List<InspirationRepository.Item> discoverItems = new ArrayList<>();
    private static int discoverPage = 0;
    private static boolean discoverHasMore = true;
    private static boolean discoverLoading = false;
    private static String discoverError = "";
    private static String discoverFetchedKey = null;

    private static InspirationRepository.GalleryData galleryData = null;
    private static boolean galleryLoading = false;
    private static String galleryError = "";
    private static InspirationRepository.Item activeInspiration = null;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        service = new ImageService(this);
        GenerationTasks.restore(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        safeBrowse = prefs.getBoolean(KEY_SAFE_BROWSE, true);

        routes = getResources().getStringArray(R.array.routes);
        modelsLine1 = getResources().getStringArray(R.array.models_line1);
        wireModelsLine1 = getResources().getStringArray(R.array.wire_models_line1);
        modelsLine2 = getResources().getStringArray(R.array.models_line2);
        wireModelsLine2 = getResources().getStringArray(R.array.wire_models_line2);
        ratios = getResources().getStringArray(R.array.ratios);

        selectedRoute = prefs.getInt("selected_route", 0);
        modelLine1 = prefs.getInt("model_line1", 0);
        modelLine2 = prefs.getInt("model_line2", 0);

        if (state != null) {
            draft = state.getString("draft", "");
            selectedRoute = state.getInt("selectedRoute", selectedRoute);
            modelLine1 = state.getInt("modelLine1", modelLine1);
            modelLine2 = state.getInt("modelLine2", modelLine2);
            ratio = state.getInt("ratio", 0);
            nsfw = state.getBoolean("nsfw", false);
            current = state.getString("current", "");
            activeTab = state.getInt("activeTab", 0);
            libraryFilter = state.getInt("libraryFilter", 0);
            librarySubTab = state.getInt("librarySubTab", 0);
            promptSourceIndex = state.getInt("promptSourceIndex", 0);
            promptMajor = state.getString("promptMajor", "全部");
            promptQuery = state.getString("promptQuery", "");
            promptVisibleCount = state.getInt("promptVisibleCount", GALLERY_PAGE_SIZE);
            if (state.containsKey("reference")) reference = Uri.parse(state.getString("reference"));
        }

        setupEdgeToEdge();
        buildRootShell();
        switchTab(activeTab);
        refreshHomeDynamic();
        refreshLibraryContent();

        if (GenerationTasks.count() > 0) startGenTimer();

        if (state == null) {
            playLaunchAnimation();
        }

        if (!autoCheckedUpdate) {
            autoCheckedUpdate = true;
            checkUpdate(true);
        }
        handleGenerationIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleGenerationIntent(intent);
    }

    private void handleGenerationIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(GenerationService.SHOW_RECORDS, false)) {
            intent.removeExtra(GenerationService.SHOW_RECORDS);
            showGenerationRecords();
        }
    }

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = this;
        if (isOverlayOpen("loading") && GenerationTasks.get(loadingRecordId) == null) popOverlaysByTag("loading");
        refreshHomeDynamic();
        refreshLibraryContent();
        updateFloatingBarsVisibility();
        if (GenerationTasks.count() > 0) startGenTimer();
    }

    @Override protected void onStop() {
        uiHandler.removeCallbacks(genTimerTick);
        if (visible == this) visible = null;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (promptInput != null) draft = promptInput.getText().toString();
        state.putString("draft", draft);
        state.putInt("selectedRoute", selectedRoute);
        state.putInt("modelLine1", modelLine1);
        state.putInt("modelLine2", modelLine2);
        state.putInt("ratio", ratio);
        state.putBoolean("nsfw", nsfw);
        state.putInt("activeTab", activeTab);
        state.putInt("libraryFilter", libraryFilter);
        state.putInt("librarySubTab", librarySubTab);
        state.putInt("promptSourceIndex", promptSourceIndex);
        state.putString("promptMajor", promptMajor);
        state.putString("promptQuery", promptQuery);
        state.putInt("promptVisibleCount", promptVisibleCount);
        if (reference != null) state.putString("reference", reference.toString());
        state.putString("current", current);
    }

    private String[] activeModelDisplayList() {
        return selectedRoute == 0 ? modelsLine1 : modelsLine2;
    }

    private String[] activeWireModelList() {
        return selectedRoute == 0 ? wireModelsLine1 : wireModelsLine2;
    }

    private int activeModelIndex() {
        return selectedRoute == 0 ? modelLine1 : modelLine2;
    }

    private void setActiveModelIndex(int idx) {
        if (selectedRoute == 0) {
            modelLine1 = Math.max(0, Math.min(idx, modelsLine1.length - 1));
            prefs.edit().putInt("model_line1", modelLine1).apply();
        } else {
            modelLine2 = Math.max(0, Math.min(idx, modelsLine2.length - 1));
            prefs.edit().putInt("model_line2", modelLine2).apply();
        }
    }

    private String activeModelDisplayName() {
        String[] list = activeModelDisplayList();
        int idx = activeModelIndex();
        return idx >= 0 && idx < list.length ? list[idx] : "";
    }

    private String activeWireModelName() {
        String[] list = activeWireModelList();
        int idx = activeModelIndex();
        return idx >= 0 && idx < list.length ? list[idx] : "";
    }

    private int dp(float v) { return UiKit.dp(this, v); }
    private boolean isGrok() { return selectedRoute == 0 && modelLine1 == 1; }
    private String activeRatioName() { return ImageService.generationRatio(activeWireModelName(), ratios[ratio], isGrok() && nsfw); }

    /** 专属极简 Apple 风格开屏启动动画（冷启动时 0.45 秒内平滑展开，无黑屏无广告） */
    private void playLaunchAnimation() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackground(UiKit.auroraCanvasBg(this));
        splash.setClickable(true);
        splash.setFocusable(true);

        LinearLayout brandCenter = new LinearLayout(this);
        brandCenter.setOrientation(LinearLayout.VERTICAL);
        brandCenter.setGravity(Gravity.CENTER);

        // 极光晨光光环 + 灯塔 Logo
        FrameLayout halo = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.5f);
        glow.setGradientRadius(dp(70));
        glow.setColors(new int[]{0x456AA8FF, 0x00FFFFFF});
        halo.setBackground(glow);
        halo.setPadding(dp(10), dp(10), dp(10), dp(10));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        halo.addView(logo, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        brandCenter.addView(halo, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView title = UiKit.text(this, "见屿", 25, UiKit.INK, true);
        title.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        brandCenter.addView(title, tlp);

        TextView subtitle = UiKit.text(this, "J I A N Y U", 10.5f, UiKit.BLUE, true);
        subtitle.setLetterSpacing(0.26f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        brandCenter.addView(subtitle, slp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.CENTER;
        splash.addView(brandCenter, clp);

        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        // 初始状态配置
        brandCenter.setScaleX(0.80f);
        brandCenter.setScaleY(0.80f);
        brandCenter.setAlpha(0f);

        tabHost.setAlpha(0f);
        tabHost.setScaleX(0.965f);
        tabHost.setScaleY(0.965f);
        tabHost.setTranslationY(dp(18));

        floatingNav.setAlpha(0f);
        floatingNav.setTranslationY(dp(70));

        // Phase 1 (0 ~ 240ms): 极光灯塔微标伴随晨光微亮呼吸展开
        brandCenter.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(MotionKit.APPLE_SPRING)
                .withEndAction(() -> {
                    // Phase 2 (240 ~ 480ms): 启动蒙层轻盈溶解，主画布展开并浮现底栏
                    splash.animate()
                            .alpha(0f)
                            .scaleX(1.05f)
                            .scaleY(1.05f)
                            .setDuration(240)
                            .setInterpolator(MotionKit.APPLE_EASE_OUT)
                            .withEndAction(() -> root.removeView(splash))
                            .start();

                    tabHost.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(280)
                            .setInterpolator(MotionKit.APPLE_EASE_OUT)
                            .start();

                    floatingNav.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(300)
                            .setInterpolator(MotionKit.APPLE_SPRING)
                            .start();
                })
                .start();
    }

    // ==================== 根壳与持久化 3-Tab ====================

    private void buildRootShell() {
        root = new FrameLayout(this);
        root.setBackground(UiKit.auroraCanvasBg(this));
        root.setFocusableInTouchMode(true);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            updateFloatingBarsVisibility();
            return windowInsets;
        });

        tabHost = new FrameLayout(this);
        root.addView(tabHost, new FrameLayout.LayoutParams(-1, -1));

        tabContainers[0] = buildHomeTab();
        tabContainers[1] = buildPromptTab();
        tabContainers[2] = buildPlazaTab();
        tabContainers[3] = buildLibraryTab();
        for (View tab : tabContainers) {
            tab.setVisibility(View.GONE);
            tabHost.addView(tab, new FrameLayout.LayoutParams(-1, -1));
        }

        // 悬浮 4-Tab 药丸底栏
        floatingNav = buildFloatingTabBar();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(dp(336), -2);
        navLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        navLp.bottomMargin = dp(14);
        root.addView(floatingNav, navLp);

        // 作品库批量选择底栏
        libraryBatchBar = buildLibraryBatchBar();
        FrameLayout.LayoutParams batchLp = new FrameLayout.LayoutParams(-1, -2);
        batchLp.gravity = Gravity.BOTTOM;
        batchLp.leftMargin = dp(18);
        batchLp.rightMargin = dp(18);
        batchLp.bottomMargin = dp(14);
        libraryBatchBar.setVisibility(View.GONE);
        root.addView(libraryBatchBar, batchLp);

        // 后台生图悬浮微胶囊（最小化光球时浮在底栏上方）
        miniGenBar = buildMiniGenBar();
        FrameLayout.LayoutParams miniLp = new FrameLayout.LayoutParams(-2, -2);
        miniLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        miniLp.bottomMargin = dp(86);
        miniGenBar.setVisibility(View.GONE);
        root.addView(miniGenBar, miniLp);

        // 二级页面叠加层（详情 / 灵感详情 / 生成中光球 / 全屏缩放）
        overlayHost = new FrameLayout(this);
        overlayHost.setVisibility(View.GONE);
        root.addView(overlayHost, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private LinearLayout buildFloatingTabBar() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(UiKit.floatingPillBg(this, 34));
        UiKit.applySoftShadow(pill, this, 14, 0x36182638);
        pill.setPadding(dp(6), dp(5), dp(6), dp(5));

        int[] iconRes = {R.drawable.ic_sparkles, R.drawable.ic_wand, R.drawable.ic_compass, R.drawable.ic_images};
        String[] labels = {"创作", "提示词", "广场", "作品"};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(8), dp(6), dp(8), dp(6));
            item.setMinimumHeight(dp(48));
            item.setFocusable(true);
            MotionKit.attachPressSpring(item, 0.94f);
            item.setOnClickListener(v -> switchTab(idx));

            ImageView iv = UiKit.icon(this, iconRes[i], 19, UiKit.MUTED);
            item.addView(iv);
            TextView lbl = UiKit.text(this, labels[i], 10.5f, UiKit.MUTED, false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.topMargin = dp(2);
            item.addView(lbl, llp);

            navItems[i] = item;
            navIcons[i] = iv;
            navLabels[i] = lbl;

            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tlp.leftMargin = dp(3);
            pill.addView(item, tlp);
        }
        return pill;
    }

    private LinearLayout buildMiniGenBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UiKit.floatingPillBg(this, 24));
        UiKit.applySoftShadow(bar, this, 10, 0x442F5CE0);
        bar.setPadding(dp(14), dp(8), dp(10), dp(8));
        bar.setFocusable(true);
        bar.setOnClickListener(v -> openLoadingOverlay());

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bar.addView(pb, new LinearLayout.LayoutParams(dp(16), dp(16)));

        miniGenText = UiKit.text(this, "正在绘制…", 12.5f, UiKit.INK, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(10);
        bar.addView(miniGenText, tlp);

        TextView openBtn = UiKit.text(this, "查看", 11.5f, Color.WHITE, true);
        openBtn.setBackground(UiKit.round(this, UiKit.BLUE, 12));
        openBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        bar.addView(openBtn);
        return bar;
    }

    private LinearLayout buildLibraryBatchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UiKit.floatingPillBg(this, 26));
        UiKit.applySoftShadow(bar, this, 14, 0x3A182638);
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));

        libraryBatchCount = UiKit.text(this, "已选 0 项", 13.5f, UiKit.INK, true);
        bar.addView(libraryBatchCount, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout selectAll = UiKit.pillTagButton(this, 0, "全选", UiKit.SOFT, UiKit.INK, this::toggleSelectAllLibrary);
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-2, -2);
        salp.rightMargin = dp(8);
        bar.addView(selectAll, salp);

        LinearLayout delBtn = UiKit.pillTagButton(this, R.drawable.ic_trash, "删除", UiKit.DANGER_SOFT, UiKit.DANGER, this::confirmBatchDelete);
        bar.addView(delBtn);
        return bar;
    }

    private void switchTab(int index) {
        hideKeyboard();
        boolean enteringPrompts = index == 1 && activeTab != 1;
        if (enteringPrompts) {
            promptOrder.reset();
            promptVisibleCount = GALLERY_PAGE_SIZE;
            Collections.shuffle(discoverItems);
        }
        if (activeTab == 3 && index != 3 && librarySelectMode) {
            exitLibrarySelectMode();
        }
        activeTab = index;
        for (int i = 0; i < 4; i++) {
            boolean active = (i == index);
            tabContainers[i].setVisibility(active ? View.VISIBLE : View.GONE);
            navItems[i].setBackground(active ? UiKit.round(this, UiKit.BLUE_SOFT, 26) : null);
            int tint = active ? UiKit.BLUE : UiKit.MUTED;
            navIcons[i].setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            navLabels[i].setTextColor(tint);
            navLabels[i].setTypeface(android.graphics.Typeface.DEFAULT, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            if (active) {
                MotionKit.popIcon(navIcons[i]);
                MotionKit.animateTabEnter(tabContainers[i]);
            }
        }
        if (index == 0) refreshHomeDynamic();
        else if (index == 1) refreshPromptTab(enteringPrompts);
        else if (index == 2) refreshPlazaTab(false);
        else if (index == 3) refreshLibraryContent();
        updateFloatingBarsVisibility();
    }

    private void updateFloatingBarsVisibility() {
        boolean hasOverlay = !overlayStack.isEmpty();
        boolean showBatch = !hasOverlay && !imeVisible && activeTab == 3 && librarySelectMode && librarySubTab == 0;
        boolean showNav = !hasOverlay && !imeVisible && !showBatch;
        MotionKit.animateFloatingBar(floatingNav, showNav, 80);
        MotionKit.animateFloatingBar(libraryBatchBar, showBatch, 80);

        boolean loadingOnTop = isOverlayOpen("loading");
        boolean zoomOnTop = isOverlayOpen("zoom");
        boolean showMini = GenerationTasks.count() > 0 && !imeVisible && !loadingOnTop && !zoomOnTop;
        MotionKit.animateFloatingBar(miniGenBar, showMini, 60);
        if (showMini) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) miniGenBar.getLayoutParams();
            lp.bottomMargin = dp((showNav || showBatch) ? 86 : 18);
            miniGenBar.setLayoutParams(lp);
        }
    }

    private boolean isOverlayOpen(String tag) {
        for (OverlayEntry e : overlayStack) {
            if (tag.equals(e.tag)) return true;
        }
        return false;
    }

    private void pushOverlay(String tag, View view) {
        hideKeyboard();
        view.setClickable(true);
        view.setFocusable(true);
        overlayStack.add(new OverlayEntry(tag, view));
        overlayHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
        overlayHost.setVisibility(View.VISIBLE);
        MotionKit.animateOverlayPush(tag, view);
        updateFloatingBarsVisibility();
    }

    private void popOverlay() {
        if (overlayStack.isEmpty()) return;
        OverlayEntry top = overlayStack.remove(overlayStack.size() - 1);
        if ("loading".equals(top.tag)) { loadingTimerView = null; loadingStatusView = null; }
        updateFloatingBarsVisibility();
        MotionKit.animateOverlayPop(top.tag, top.view, () -> {
            overlayHost.removeView(top.view);
            if (overlayStack.isEmpty()) overlayHost.setVisibility(View.GONE);
        });
    }

    private void popOverlaysByTag(String tag) {
        for (int i = overlayStack.size() - 1; i >= 0; i--) {
            OverlayEntry entry = overlayStack.get(i);
            if (tag.equals(entry.tag)) {
                overlayStack.remove(i);
                overlayHost.removeView(entry.view);
                if ("loading".equals(tag)) { loadingTimerView = null; loadingStatusView = null; }
            }
        }
        if (overlayStack.isEmpty()) overlayHost.setVisibility(View.GONE);
        updateFloatingBarsVisibility();
    }

    private void clearOverlays() {
        overlayStack.clear();
        overlayHost.removeAllViews();
        overlayHost.setVisibility(View.GONE);
        loadingTimerView = null;
        loadingStatusView = null;
        updateFloatingBarsVisibility();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    // ==================== Tab 0: 创作页（精简一屏布局 + 设置齿轮 + 1行参数胶囊） ====================

    private View buildHomeTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(112));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        // 顶部栏：仅首页保留品牌微标 + 右上角 44dp 设置齿轮（仅发现新版本时亮极小蓝点）
        FrameLayout settingsWrap = new FrameLayout(this);
        LinearLayout gearBtn = UiKit.circleIconBtn(this, R.drawable.ic_settings, UiKit.INK, UiKit.GLASS_STRONG, this::showSettingsSheet);
        settingsWrap.addView(gearBtn, new FrameLayout.LayoutParams(dp(44), dp(44)));
        homeUpdateDot = new View(this);
        homeUpdateDot.setBackground(UiKit.round(this, UiKit.BLUE, 5));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(9), dp(9));
        dotLp.gravity = Gravity.TOP | Gravity.END;
        dotLp.topMargin = dp(4);
        dotLp.rightMargin = dp(4);
        homeUpdateDot.setVisibility(View.GONE);
        settingsWrap.addView(homeUpdateDot, dotLp);

        page.addView(UiKit.styleOneHeader(this, "创作", settingsWrap), new LinearLayout.LayoutParams(-1, -2));

        // 待取回任务提示位（动态）
        homePendingSlot = new LinearLayout(this);
        homePendingSlot.setOrientation(LinearLayout.VERTICAL);
        page.addView(homePendingSlot, new LinearLayout.LayoutParams(-1, -2));

        // Hero 液态毛玻璃画布卡
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setBackground(UiKit.liquidGlass(this, 26));
        UiKit.applySoftShadow(heroCard, this, 10, 0x24182638);
        heroCard.setPadding(dp(16), dp(14), dp(16), dp(12));
        heroCardView = heroCard;

        homeRefSlot = new LinearLayout(this);
        homeRefSlot.setOrientation(LinearLayout.VERTICAL);
        heroCard.addView(homeRefSlot, new LinearLayout.LayoutParams(-1, -2));

        promptInput = (EditText) getLayoutInflater().inflate(R.layout.prompt_edit_text, heroCard, false);
        promptInput.setTextSize(15.5f);
        promptInput.setTextColor(UiKit.INK);
        promptInput.setHintTextColor(0xFF94A3B8);
        promptInput.setHint("描述你想生成的画面，或去「灵感」挑一个同款…");
        promptInput.setText(draft);
        promptInput.setSelection(draft.length());
        promptInput.setPadding(0, dp(2), dp(6), dp(10));
        promptInput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        promptInput.setOnTouchListener((v, event) -> {
            if (promptInput.getLineCount() > promptInput.getMaxLines()) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if ((event.getAction() & android.view.MotionEvent.ACTION_MASK) == android.view.MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        heroCard.addView(promptInput, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE9EFF8);
        heroCard.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-1, -2);
        footLp.topMargin = dp(10);

        promptCounter = UiKit.text(this, draft.codePointCount(0, draft.length()) + " 字符", 11.5f, UiKit.MUTED, false);
        foot.addView(promptCounter, new LinearLayout.LayoutParams(0, -2, 1));

        promptClearBtn = UiKit.pillTagButton(this, R.drawable.ic_close, "清空", UiKit.SOFT, UiKit.MUTED, () -> {
            draft = "";
            if (promptInput != null) promptInput.setText("");
        });
        promptClearBtn.setVisibility(draft.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(-2, -2);
        clrLp.rightMargin = dp(6);
        foot.addView(promptClearBtn, clrLp);

        LinearLayout refBtn = new LinearLayout(this);
        refBtn.setOrientation(LinearLayout.HORIZONTAL);
        refBtn.setGravity(Gravity.CENTER);
        refBtn.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 18));
        refBtn.setPadding(dp(13), dp(8), dp(13), dp(8));
        refBtn.setMinimumHeight(dp(36));
        refBtn.setFocusable(true);
        refBtn.setOnClickListener(v -> pickReference());
        ImageView refIv = UiKit.icon(this, R.drawable.ic_image_plus, 14, UiKit.BLUE);
        LinearLayout.LayoutParams rilp = new LinearLayout.LayoutParams(dp(14), dp(14));
        rilp.rightMargin = dp(5);
        refBtn.addView(refIv, rilp);
        promptRefBtnLabel = UiKit.text(this, "参考图", 12.5f, UiKit.BLUE, true);
        refBtn.addView(promptRefBtnLabel);
        foot.addView(refBtn);
        heroCard.addView(foot, footLp);

        promptInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String value = s.toString();
                draft = value;
                promptCounter.setText(value.codePointCount(0, value.length()) + " 字符");
                promptClearBtn.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });

        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = dp(14);
        page.addView(heroCard, heroLp);

        // 1 行收拢参数摘要胶囊（点击弹出 iOS 风格参数 Bottom Sheet，首屏轻盈不拥挤）
        LinearLayout paramPill = new LinearLayout(this);
        paramPill.setOrientation(LinearLayout.HORIZONTAL);
        paramPill.setGravity(Gravity.CENTER_VERTICAL);
        paramPill.setBackground(UiKit.liquidGlass(this, 20));
        UiKit.applySoftShadow(paramPill, this, 4, 0x1E182638);
        paramPill.setPadding(dp(14), dp(11), dp(14), dp(11));
        paramPill.setMinimumHeight(dp(50));
        paramPill.setFocusable(true);
        paramPill.setOnClickListener(v -> showParameterSheet());

        LinearLayout sliderIcon = new LinearLayout(this);
        sliderIcon.setGravity(Gravity.CENTER);
        sliderIcon.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 11));
        sliderIcon.addView(UiKit.icon(this, R.drawable.ic_sliders, 16, UiKit.BLUE));
        paramPill.addView(sliderIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        paramSummaryText = UiKit.text(this, "", 13.5f, UiKit.INK, true);
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(0, -2, 1);
        pslp.leftMargin = dp(10);
        paramPill.addView(paramSummaryText, pslp);

        TextView adjustHint = UiKit.text(this, "参数设置 ›", 12.5f, UiKit.BLUE, true);
        paramPill.addView(adjustHint);

        LinearLayout.LayoutParams paramLp = new LinearLayout.LayoutParams(-1, -2);
        paramLp.topMargin = dp(12);
        page.addView(paramPill, paramLp);

        // 主操作虹彩流光按钮
        LinearLayout ctaBtn = new LinearLayout(this);
        ctaBtn.setOrientation(LinearLayout.HORIZONTAL);
        ctaBtn.setGravity(Gravity.CENTER);
        ctaBtn.setBackground(UiKit.prismBg(this, 28));
        UiKit.applySoftShadow(ctaBtn, this, 10, 0x662F5CE0);
        ctaBtn.setFocusable(true);
        ctaBtn.setOnClickListener(v -> {
            generate();
        });
        ctaIconView = UiKit.icon(this, R.drawable.ic_sparkles, 17, Color.WHITE);
        LinearLayout.LayoutParams cilp = new LinearLayout.LayoutParams(dp(17), dp(17));
        cilp.rightMargin = dp(7);
        ctaBtn.addView(ctaIconView, cilp);
        ctaLabelView = UiKit.text(this, "生成图片", 15, Color.WHITE, true);
        ctaBtn.addView(ctaLabelView);

        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(-1, dp(52));
        ctaLp.topMargin = dp(14);
        page.addView(ctaBtn, ctaLp);

        if (!service.isConfigured()) {
            TextView previewNote = UiKit.text(this, "预览版 · 尚未连接生图服务", 12, UiKit.MUTED, false);
            LinearLayout.LayoutParams pnl = new LinearLayout.LayoutParams(-2, -2);
            pnl.topMargin = dp(8);
            page.addView(previewNote, pnl);
        }

        // 最近作品区
        LinearLayout recentHead = new LinearLayout(this);
        recentHead.setOrientation(LinearLayout.HORIZONTAL);
        recentHead.setGravity(Gravity.CENTER_VERTICAL);
        recentHead.addView(UiKit.text(this, "最近作品", 15.5f, UiKit.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView viewAll = UiKit.text(this, "全部 ›", 13, UiKit.BLUE, true);
        viewAll.setPadding(dp(10), dp(6), dp(2), dp(6));
        viewAll.setOnClickListener(v -> switchTab(3));
        recentHead.addView(viewAll);
        LinearLayout.LayoutParams rhlp = new LinearLayout.LayoutParams(-1, -2);
        rhlp.topMargin = dp(20);
        page.addView(recentHead, rhlp);

        homeRecentSlot = new LinearLayout(this);
        homeRecentSlot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, -2);
        rslp.topMargin = dp(10);
        page.addView(homeRecentSlot, rslp);

        return scroll;
    }

    private void refreshHomeDynamic() {
        if (homeUpdateDot != null) {
            homeUpdateDot.setVisibility(availableUpdate != null && availableUpdate.hasUpdate ? View.VISIBLE : View.GONE);
        }
        // 更新参数胶囊文案
        if (paramSummaryText != null) {
            String routeName = selectedRoute == 0 ? "线路 1" : "线路 2";
            String summary = routeName + " · " + activeModelDisplayName() + " · " + activeRatioName();
            if (isGrok() && nsfw) summary += " · NSFW";
            paramSummaryText.setText(summary);
        }
        // 同步草稿文本框（如从灵感/作品详情回填时）
        if (promptInput != null && !promptInput.getText().toString().equals(draft)) {
            promptInput.setText(draft);
            promptInput.setSelection(draft.length());
        }
        if (promptRefBtnLabel != null) {
            promptRefBtnLabel.setText(reference == null ? "参考图" : "换参考图");
        }
        if (homePendingSlot != null) {
            homePendingSlot.removeAllViews();
            int pendingCount = GenerationTasks.pendingCount(this);
            if (pendingCount > 0) {
                LinearLayout banner = UiKit.glassButton(this, R.drawable.ic_clock,
                        pendingCount + " 条任务可继续获取 · 查看记录", UiKit.BLUE_SOFT, UiKit.BLUE, this::showGenerationRecords);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
                lp.topMargin = dp(12);
                homePendingSlot.addView(banner, lp);
            }
        }
        // 参考图贴纸条
        if (homeRefSlot != null) {
            homeRefSlot.removeAllViews();
            if (reference != null) {
                LinearLayout refChip = new LinearLayout(this);
                refChip.setOrientation(LinearLayout.HORIZONTAL);
                refChip.setGravity(Gravity.CENTER_VERTICAL);
                refChip.setBackground(UiKit.card(this, 0xEEF2F6FC, 0xFFE2EBF7, 16));
                refChip.setPadding(dp(8), dp(8), dp(10), dp(8));

                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(UiKit.round(this, UiKit.SOFT, 11));
                thumb.setClipToOutline(true);
                loadReferenceThumb(thumb, reference);
                refChip.addView(thumb, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.addView(UiKit.text(this, "已附带参考图", 13, UiKit.INK, true));
                TextView sub = UiKit.text(this, "大图将自动压缩，按描述进行图改图", 11, UiKit.MUTED, false);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
                slp.topMargin = dp(2);
                info.addView(sub, slp);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1);
                ilp.leftMargin = dp(10);
                refChip.addView(info, ilp);

                TextView swap = UiKit.text(this, "更换", 12, UiKit.BLUE, true);
                swap.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 13));
                swap.setPadding(dp(11), dp(6), dp(11), dp(6));
                swap.setOnClickListener(v -> pickReference());
                refChip.addView(swap);

                LinearLayout remove = new LinearLayout(this);
                remove.setGravity(Gravity.CENTER);
                remove.setBackground(UiKit.round(this, UiKit.DANGER_SOFT, 15));
                remove.addView(UiKit.icon(this, R.drawable.ic_close, 14, UiKit.DANGER));
                remove.setOnClickListener(v -> { reference = null; refreshHomeDynamic(); });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(30), dp(30));
                rlp.leftMargin = dp(6);
                refChip.addView(remove, rlp);

                LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(-1, -2);
                refLp.bottomMargin = dp(10);
                homeRefSlot.addView(refChip, refLp);
            }
        }
        // 主按钮状态
        if (ctaLabelView != null && ctaIconView != null) {
            ctaIconView.setImageResource(reference == null ? R.drawable.ic_sparkles : R.drawable.ic_wand);
            ctaLabelView.setText(GenerationTasks.count() > 0 ? "生成新的独立任务"
                    : reference == null ? "生成图片" : "按描述修改参考图");
        }
        // 最近作品条
        if (homeRecentSlot != null) {
            homeRecentSlot.removeAllViews();
            homeRecentSlot.addView(recentPhotosStrip());
        }
    }

    private View recentPhotosStrip() {
        String[] ids = listLocalArtworkIds();
        if (ids.length == 0) {
            return fullBleedLocalTile("", null, 150, "内置示例");
        }
        File dir = new File(getFilesDir(), "images");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count = Math.min(2, ids.length);
        for (int i = 0; i < count; i++) {
            String id = ids[i];
            View tile = fullBleedLocalTile(id, new File(dir, id), 150, artworkBadge(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(150), 1);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(tile, lp);
        }
        if (count == 1) {
            View sample = fullBleedLocalTile("", null, 150, "内置示例");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(150), 1);
            lp.leftMargin = dp(10);
            row.addView(sample, lp);
        }
        return row;
    }

    private FrameLayout fullBleedLocalTile(String id, File file, int heightDp, String badgeText) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(UiKit.round(this, UiKit.SOFT, 20));
        tile.setClipToOutline(true);
        UiKit.applySoftShadow(tile, this, 5, 0x26182638);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> openDetailOverlay(id));

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
                if (bmp != null) uiHandler.post(() -> view.setImageBitmap(bmp));
            } catch (Exception ignored) { }
        }, "jianyu-ref-thumb").start();
    }

    // ==================== 创作参数底部面板（iOS Bottom Sheet） ====================

    private void showParameterSheet() {
        hideKeyboard();
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(UiKit.floatingPillBg(this, 30));
        UiKit.applySoftShadow(sheet, this, 18, 0x40182638);
        sheet.setPadding(dp(20), dp(12), dp(20), dp(18));

        View handle = new View(this);
        handle.setBackground(UiKit.round(this, 0xFFCBD5E1, 3));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hlp);

        TextView heading = UiKit.text(this, "生成参数", 18, UiKit.INK, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        sheet.addView(heading, tlp);

        LinearLayout contentSlot = new LinearLayout(this);
        contentSlot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(14);
        sheet.addView(contentSlot, clp);

        wrapper.addView(sheet, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = createBottomSheetDialog(wrapper);

        Runnable rebuild = new Runnable() {
            @Override public void run() {
                contentSlot.removeAllViews();

                // 1. 服务线路选择
                contentSlot.addView(UiKit.text(MainActivity.this, "服务线路", 12.5f, UiKit.MUTED, true));
                LinearLayout routeSeg = UiKit.segmentedControl(MainActivity.this, routes, selectedRoute, idx -> {
                    if (selectedRoute != idx) {
                        selectedRoute = idx;
                        prefs.edit().putInt("selected_route", selectedRoute).apply();
                        refreshHomeDynamic();
                        run();
                    }
                });
                LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, -2);
                rslp.topMargin = dp(8);
                contentSlot.addView(routeSeg, rslp);

                TextView routeHint = UiKit.text(MainActivity.this,
                        selectedRoute == 0 ? "经典线路 · 支持 GPT / Grok 稳定生成" : "新版线路 · 支持 GPT-2.5 多模型与图生图",
                        11f, UiKit.MUTED, false);
                LinearLayout.LayoutParams rhlp = new LinearLayout.LayoutParams(-2, -2);
                rhlp.topMargin = dp(4);
                rhlp.bottomMargin = dp(12);
                contentSlot.addView(routeHint, rhlp);

                // 2. 模型引擎选择
                contentSlot.addView(UiKit.text(MainActivity.this, "模型引擎", 12.5f, UiKit.MUTED, true));
                if (selectedRoute == 0) {
                    LinearLayout seg1 = UiKit.segmentedControl(MainActivity.this, modelsLine1, modelLine1, idx -> {
                        if (modelLine1 != idx) {
                            modelLine1 = idx;
                            prefs.edit().putInt("model_line1", modelLine1).apply();
                            refreshHomeDynamic();
                            run();
                        }
                    });
                    LinearLayout.LayoutParams slp1 = new LinearLayout.LayoutParams(-1, -2);
                    slp1.topMargin = dp(8);
                    contentSlot.addView(seg1, slp1);
                } else {
                    String[] row1 = new String[]{modelsLine2[0], modelsLine2[1]};
                    String[] row2 = new String[]{modelsLine2[2], modelsLine2[3]};
                    LinearLayout seg1 = UiKit.segmentedControl(MainActivity.this, row1, modelLine2 <= 1 ? modelLine2 : -1, idx -> {
                        if (modelLine2 != idx) {
                            modelLine2 = idx;
                            prefs.edit().putInt("model_line2", modelLine2).apply();
                            refreshHomeDynamic();
                            run();
                        }
                    });
                    LinearLayout.LayoutParams slp1 = new LinearLayout.LayoutParams(-1, -2);
                    slp1.topMargin = dp(8);
                    contentSlot.addView(seg1, slp1);

                    LinearLayout seg2 = UiKit.segmentedControl(MainActivity.this, row2, modelLine2 >= 2 ? (modelLine2 - 2) : -1, idx -> {
                        if (modelLine2 != (idx + 2)) {
                            modelLine2 = idx + 2;
                            prefs.edit().putInt("model_line2", modelLine2).apply();
                            refreshHomeDynamic();
                            run();
                        }
                    });
                    LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-1, -2);
                    slp2.topMargin = dp(6);
                    contentSlot.addView(seg2, slp2);
                }

                TextView rTitle = UiKit.text(MainActivity.this, "画面比例", 12.5f, UiKit.MUTED, true);
                LinearLayout.LayoutParams rtlp = new LinearLayout.LayoutParams(-2, -2);
                rtlp.topMargin = dp(16);
                contentSlot.addView(rTitle, rtlp);

                LinearLayout ratioRow = new LinearLayout(MainActivity.this);
                ratioRow.setOrientation(LinearLayout.HORIZONTAL);
                if (isGrok() && nsfw) {
                    ratioRow.addView(UiKit.text(MainActivity.this, "NSFW 模式固定输出 " + ImageService.GROK_NSFW_RATIO, 13, UiKit.INK, true));
                } else {
                    for (int i = 0; i < ratios.length; i++) {
                        final int idx = i;
                        LinearLayout card = UiKit.ratioCard(MainActivity.this, ratios[i], idx == ratio, () -> {
                            if (ratio != idx) {
                                ratio = idx;
                                refreshHomeDynamic();
                                run();
                            }
                        });
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1);
                        if (i > 0) rlp.leftMargin = dp(7);
                        ratioRow.addView(card, rlp);
                    }
                }
                LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(-1, -2);
                rrlp.topMargin = dp(8);
                contentSlot.addView(ratioRow, rrlp);

                if (isGrok()) {
                    LinearLayout nsfwRow = new LinearLayout(MainActivity.this);
                    nsfwRow.setOrientation(LinearLayout.HORIZONTAL);
                    nsfwRow.setGravity(Gravity.CENTER_VERTICAL);
                    nsfwRow.setBackground(UiKit.card(MainActivity.this, 0xFFF3F6FC, 0xFFE4ECF6, 18));
                    nsfwRow.setPadding(dp(12), dp(10), dp(12), dp(10));
                    nsfwRow.setFocusable(true);

                    LinearLayout iconBox = new LinearLayout(MainActivity.this);
                    iconBox.setGravity(Gravity.CENTER);
                    iconBox.setBackground(UiKit.round(MainActivity.this, UiKit.BLUE_SOFT, 11));
                    iconBox.addView(UiKit.icon(MainActivity.this, R.drawable.ic_shield, 17, UiKit.BLUE));
                    nsfwRow.addView(iconBox, new LinearLayout.LayoutParams(dp(34), dp(34)));

                    LinearLayout texts = new LinearLayout(MainActivity.this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.addView(UiKit.text(MainActivity.this, "允许 NSFW", 13.5f, UiKit.INK, true));
                    texts.addView(UiKit.text(MainActivity.this, getString(R.string.grok_nsfw_help, ImageService.GROK_NSFW_RATIO), 11, UiKit.MUTED, false));
                    LinearLayout.LayoutParams txlp = new LinearLayout.LayoutParams(0, -2, 1);
                    txlp.leftMargin = dp(10);
                    nsfwRow.addView(texts, txlp);

                    Switch toggle = new Switch(MainActivity.this);
                    toggle.setChecked(nsfw);
                    toggle.setOnCheckedChangeListener((b, checked) -> {
                        nsfw = checked;
                        refreshHomeDynamic();
                        run();
                    });
                    nsfwRow.addView(toggle);
                    nsfwRow.setOnClickListener(v -> toggle.toggle());

                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
                    nlp.topMargin = dp(14);
                    contentSlot.addView(nsfwRow, nlp);
                }
            }
        };
        rebuild.run();

        LinearLayout doneBtn = UiKit.prismButton(this, 0, "完成", dialog::dismiss);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(48));
        dlp.topMargin = dp(18);
        sheet.addView(doneBtn, dlp);

        dialog.show();
    }

    // ==================== 设置底部面板（版本检查更新 / 安全浏览 / 清理缓存） ====================

    private void showSettingsSheet() {
        hideKeyboard();
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(UiKit.floatingPillBg(this, 30));
        UiKit.applySoftShadow(sheet, this, 18, 0x40182638);
        sheet.setPadding(dp(20), dp(12), dp(20), dp(18));

        View handle = new View(this);
        handle.setBackground(UiKit.round(this, 0xFFCBD5E1, 3));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hlp);

        // 顶部品牌识别行
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        brandRow.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout brandTexts = new LinearLayout(this);
        brandTexts.setOrientation(LinearLayout.VERTICAL);
        brandTexts.addView(UiKit.text(this, "见屿 · 设置", 17, UiKit.INK, true));
        brandTexts.addView(UiKit.text(this, "当前版本 v" + BuildConfig.VERSION_NAME, 12, UiKit.MUTED, false));
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(0, -2, 1);
        btlp.leftMargin = dp(12);
        brandRow.addView(brandTexts, btlp);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
        brlp.topMargin = dp(14);
        sheet.addView(brandRow, brlp);

        wrapper.addView(sheet, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = createBottomSheetDialog(wrapper);

        // 1. 版本与检查更新
        LinearLayout updateRow = settingsItemRow(
                R.drawable.ic_refresh,
                "检查更新",
                availableUpdate != null && availableUpdate.hasUpdate
                        ? "发现新版本 v" + availableUpdate.latestVersion
                        : "当前 v" + BuildConfig.VERSION_NAME + " · 支持应用内直装",
                null);
        TextView updateAction = UiKit.softBadge(
                this,
                availableUpdate != null && availableUpdate.hasUpdate ? "立即更新" : "检查 ›",
                availableUpdate != null && availableUpdate.hasUpdate ? UiKit.BLUE : UiKit.BLUE_SOFT,
                availableUpdate != null && availableUpdate.hasUpdate ? Color.WHITE : UiKit.BLUE);
        updateRow.addView(updateAction);
        updateRow.setOnClickListener(v -> {
            dialog.dismiss();
            if (availableUpdate != null && availableUpdate.hasUpdate) showUpdateDialog(availableUpdate);
            else checkUpdate(false);
        });
        LinearLayout.LayoutParams urlp = new LinearLayout.LayoutParams(-1, -2);
        urlp.topMargin = dp(16);
        sheet.addView(updateRow, urlp);

        // 2. 安全浏览开关（折叠敏感灵感缩略图）
        Switch safeSwitch = new Switch(this);
        safeSwitch.setChecked(safeBrowse);
        safeSwitch.setOnCheckedChangeListener((b, checked) -> {
            safeBrowse = checked;
            prefs.edit().putBoolean(KEY_SAFE_BROWSE, checked).apply();
            if (promptAdapter != null) {
                promptAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            }
            if (plazaAdapter != null) {
                plazaAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            }
            if (favsAdapter != null) {
                favsAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            }
        });
        LinearLayout safeRow = settingsItemRow(
                R.drawable.ic_shield,
                "安全浏览",
                "在灵感瀑布流中默认折叠敏感预览图",
                safeSwitch);
        safeRow.setOnClickListener(v -> safeSwitch.toggle());
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(10);
        sheet.addView(safeRow, srlp);

        // 3. 缓存管理
        TextView cacheSub = UiKit.text(this, "正在计算缓存占用…", 11.5f, UiKit.MUTED, false);
        TextView clearBadge = UiKit.softBadge(this, "清理", UiKit.SOFT, UiKit.INK);
        LinearLayout cacheRow = settingsItemRowWithSubView(R.drawable.ic_trash, "图片与画廊缓存", cacheSub, clearBadge);
        new Thread(() -> {
            long bytes = service.cacheSizeBytes();
            String formatted = formatBytes(bytes);
            uiHandler.post(() -> cacheSub.setText("已占用 " + formatted + "（不影响本机作品库）"));
        }, "jianyu-cache-size").start();
        cacheRow.setOnClickListener(v -> {
            new Thread(() -> {
                service.clearAllCaches();
                galleryData = null;
                uiHandler.post(() -> {
                    builtPromptMajorsSignature = "";
                    cacheSub.setText("已占用 0 KB（已清理完成）");
                    Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
                });
            }, "jianyu-cache-clear").start();
        });
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-1, -2);
        crlp.topMargin = dp(10);
        sheet.addView(cacheRow, crlp);

        TextView about = UiKit.text(this, "作品均保存在本机，支持一键保存至系统相册与分享。", 11.5f, UiKit.MUTED, false);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(16);
        sheet.addView(about, alp);

        dialog.show();
    }

    private LinearLayout settingsItemRow(int iconRes, String title, String subtitle, View trailing) {
        TextView sub = UiKit.text(this, subtitle, 11.5f, UiKit.MUTED, false);
        return settingsItemRowWithSubView(iconRes, title, sub, trailing);
    }

    private LinearLayout settingsItemRowWithSubView(int iconRes, String title, TextView subView, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.card(this, 0xFFF4F7FC, 0xFFE6EEF8, 18));
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setMinimumHeight(dp(56));
        row.setFocusable(true);

        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(UiKit.round(this, UiKit.BLUE_SOFT, 11));
        iconBox.addView(UiKit.icon(this, iconRes, 17, UiKit.BLUE));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(UiKit.text(this, title, 14, UiKit.INK, true));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(2);
        texts.addView(subView, slp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(10);
        row.addView(texts, tlp);

        if (trailing != null) row.addView(trailing);
        return row;
    }

    private AlertDialog createBottomSheetDialog(View content) {
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.BottomSheetAnimStyle);
        }
        return dialog;
    }

    // ==================== Tab 1: 提示词工坊（本地离线词库 + 发现灵感在线拉取 + 分类过滤 + 搜索 + 瀑布流） ====================

    private static String promptSourceTag(int index) {
        switch (index) {
            case 1: return "hot";
            case 2: return "addon";
            case 3: return "gallery";
            case 4: return "discover";
            default: return "all";
        }
    }

    private View buildPromptTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerPad = new LinearLayout(this);
        headerPad.setOrientation(LinearLayout.VERTICAL);
        headerPad.setPadding(dp(18), dp(14), dp(18), dp(8));

        headerPad.addView(UiKit.pageTitleHeader(this, "提示词工坊", "离线提示词库 · 在线精选灵感", null));

        // 来源分段切换条：[ 全部 | 热榜图鉴 | 设计精选 | 经典画廊 | 发现灵感 ]
        promptSourceSegHolder = new LinearLayout(this);
        promptSourceSegHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(-1, -2);
        shlp.topMargin = dp(12);
        headerPad.addView(promptSourceSegHolder, shlp);

        // 搜索栏
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(UiKit.liquidGlass(this, 24));
        UiKit.applySoftShadow(searchBar, this, 4, 0x1A182638);
        searchBar.setPadding(dp(14), dp(4), dp(8), dp(4));
        searchBar.addView(UiKit.icon(this, R.drawable.ic_search, 16, UiKit.MUTED));

        promptSearchInput = new EditText(this);
        promptSearchInput.setSingleLine(true);
        promptSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        promptSearchInput.setBackground(null);
        promptSearchInput.setTextSize(13.5f);
        promptSearchInput.setTextColor(UiKit.INK);
        promptSearchInput.setHintTextColor(0xFF94A3B8);
        promptSearchInput.setHint("搜索热榜、精选、画廊与在线发现灵感…");
        promptSearchInput.setText(promptQuery);
        promptSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            applyPromptSearch();
            return true;
        });
        promptSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (promptSearchClear != null) {
                    promptSearchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
        });
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(0, dp(38), 1);
        sip.leftMargin = dp(8);
        searchBar.addView(promptSearchInput, sip);

        LinearLayout clr = new LinearLayout(this);
        clr.setGravity(Gravity.CENTER);
        clr.setPadding(dp(8), dp(8), dp(8), dp(8));
        clr.addView(UiKit.icon(this, R.drawable.ic_close, 14, UiKit.MUTED));
        clr.setVisibility(promptQuery.isEmpty() ? View.GONE : View.VISIBLE);
        clr.setOnClickListener(v -> {
            promptSearchInput.setText("");
            applyPromptSearch();
        });
        promptSearchClear = clr;
        searchBar.addView(clr);

        TextView goBtn = UiKit.text(this, "搜索", 12, Color.WHITE, true);
        goBtn.setBackground(UiKit.round(this, UiKit.BLUE, 15));
        goBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        goBtn.setOnClickListener(v -> applyPromptSearch());
        searchBar.addView(goBtn);

        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-1, -2);
        sblp.topMargin = dp(10);
        headerPad.addView(searchBar, sblp);

        // 分类横滑胶囊条
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        promptMajorsRow = new LinearLayout(this);
        promptMajorsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(promptMajorsRow);
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(-1, -2);
        cslp.topMargin = dp(10);
        headerPad.addView(chipsScroll, cslp);

        promptCountLabel = UiKit.text(this, "", 11.5f, UiKit.MUTED, false);
        LinearLayout.LayoutParams gclp = new LinearLayout.LayoutParams(-1, -2);
        gclp.topMargin = dp(8);
        headerPad.addView(promptCountLabel, gclp);

        wrap.addView(headerPad, new LinearLayout.LayoutParams(-1, -2));

        // 瀑布流内容区 + 下拉刷新 + 空态覆盖
        FrameLayout body = new FrameLayout(this);
        promptSwipe = new SwipeRefreshLayout(this);
        promptSwipe.setColorSchemeColors(UiKit.BLUE);
        promptSwipe.setOnRefreshListener(() -> {
            promptOrder.reset();
            promptVisibleCount = GALLERY_PAGE_SIZE;
            if (promptSourceIndex == 4) fetchDiscover(true);
            else fetchGallery(true);
        });

        promptRecycler = new RecyclerView(this);
        StaggeredGridLayoutManager sglm = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        sglm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        promptRecycler.setLayoutManager(sglm);
        promptRecycler.setPadding(dp(13), dp(6), dp(13), dp(112));
        promptRecycler.setClipToPadding(false);
        promptRecycler.setItemAnimator(null);

        promptAdapter = new GalleryAdapters.InspirationAdapter(this, new GalleryAdapters.InspirationListener() {
            @Override public void onItemClick(InspirationRepository.Item item) {
                openInspireDetailOverlay(item);
            }
            @Override public void onQuickSameClick(InspirationRepository.Item item) {
                applyInspirationToHome(item);
            }
            @Override public void onFavoriteToggle(InspirationRepository.Item item) {
                boolean added = InspirationRepository.toggleFavorite(MainActivity.this, item);
                Toast.makeText(MainActivity.this, added ? "已收藏至作品库" : "已取消收藏", Toast.LENGTH_SHORT).show();
                promptAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
                if (plazaAdapter != null) plazaAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
                if (favsAdapter != null) refreshLibraryContent();
            }
        });
        promptRecycler.setAdapter(promptAdapter);

        // 触底自动分页加载
        promptRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                StaggeredGridLayoutManager lm = (StaggeredGridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int[] lastPositions = lm.findLastVisibleItemPositions(null);
                int maxLast = 0;
                for (int p : lastPositions) if (p > maxLast) maxLast = p;
                int total = promptAdapter.getItemCount();
                if (total > 0 && maxLast >= total - 6) {
                    if (promptSourceIndex == 4) {
                        if (discoverHasMore && !discoverLoading) {
                            fetchDiscover(false);
                        }
                    } else if (galleryData != null) {
                        List<InspirationRepository.Item> filtered = orderedPromptItems();
                        if (promptVisibleCount < filtered.size()) {
                            promptVisibleCount += GALLERY_PAGE_SIZE;
                            bindPromptToAdapter(filtered);
                        }
                    }
                }
            }
        });

        promptSwipe.addView(promptRecycler, new FrameLayout.LayoutParams(-1, -1));
        body.addView(promptSwipe, new FrameLayout.LayoutParams(-1, -1));

        promptStateBox = new FrameLayout(this);
        promptStateBox.setPadding(dp(18), dp(20), dp(18), dp(112));
        promptStateBox.setVisibility(View.GONE);
        body.addView(promptStateBox, new FrameLayout.LayoutParams(-1, -1));

        wrap.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        rebuildPromptSourceSwitcher();
        return wrap;
    }

    private void rebuildPromptSourceSwitcher() {
        promptSourceSegHolder.removeAllViews();
        promptSourceSegHolder.addView(UiKit.segmentedControl(this,
                new String[]{"全部", "热榜图鉴", "设计精选", "经典画廊", "发现灵感"}, promptSourceIndex, 12f, idx -> {
            if (promptSourceIndex != idx) {
                hideKeyboard();
                promptSourceIndex = idx;
                promptMajor = "全部";
                promptVisibleCount = GALLERY_PAGE_SIZE;
                builtPromptMajorsSignature = "";
                rebuildPromptSourceSwitcher();
                refreshPromptTab(true);
            }
        }), new LinearLayout.LayoutParams(-1, -2));
    }

    private void applyPromptSearch() {
        hideKeyboard();
        promptQuery = promptSearchInput.getText().toString().trim();
        promptVisibleCount = GALLERY_PAGE_SIZE;
        builtPromptMajorsSignature = "";
        if (promptSourceIndex == 4) {
            fetchDiscover(true);
        } else {
            refreshPromptTab(true);
        }
    }

    private void refreshPromptTab(boolean scrollToTop) {
        if (promptStateBox == null) return;
        promptStateBox.removeAllViews();
        promptStateBox.setVisibility(View.GONE);

        if (promptSourceIndex == 4) {
            rebuildDiscoverChips();
            String targetKey = promptQuery;
            if (!targetKey.equals(discoverFetchedKey) && !discoverLoading) {
                fetchDiscover(true);
                return;
            }
            if (discoverLoading && discoverItems.isEmpty()) {
                promptCountLabel.setText("正在加载精选灵感…");
                promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
                showStateIn(promptStateBox, loadingHintCard("正在按需拉取在线发现灵感…"));
                return;
            }
            if (!discoverError.isEmpty() && discoverItems.isEmpty()) {
                promptCountLabel.setText("在线灵感拉取失败");
                promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
                showStateIn(promptStateBox, emptyCard("在线拉取失败", discoverError, "重试拉取", () -> fetchDiscover(true)));
                return;
            }
            promptCountLabel.setText("发现灵感 · 已加载 " + discoverItems.size() + " 条"
                    + (promptQuery.isEmpty() ? "" : " · 检索：" + promptQuery));
            if (discoverItems.isEmpty()) {
                promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
                showStateIn(promptStateBox, emptyCard("没有找到匹配的在线灵感", "试试换个中文关键词。", "重置筛选", () -> {
                    promptQuery = "";
                    if (promptSearchInput != null) promptSearchInput.setText("");
                    builtPromptMajorsSignature = "";
                    fetchDiscover(true);
                }));
                return;
            }
            String footer = discoverLoading ? "正在按需拉取下一页…" : (discoverHasMore ? "上滑自动按需拉取更多灵感" : "已加载全部匹配灵感");
            promptAdapter.setData(discoverItems, favoriteKeySet(), safeBrowse, footer);
            if (scrollToTop && promptRecycler != null) promptRecycler.scrollToPosition(0);
            return;
        }

        if (galleryData == null && !galleryLoading && galleryError.isEmpty()) {
            fetchGallery(false);
            return;
        }
        if (galleryLoading && galleryData == null) {
            promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
            showStateIn(promptStateBox, loadingHintCard("正在加载 13,780+ 提示词库并优化离线秒开…"));
            return;
        }
        if (!galleryError.isEmpty() && galleryData == null) {
            promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
            showStateIn(promptStateBox, emptyCard("加载失败", galleryError, "重新加载", () -> fetchGallery(true)));
            return;
        }

        rebuildPromptMajorChips();
        List<InspirationRepository.Item> filtered = orderedPromptItems();
        String sourceName = promptSourceIndex == 1 ? "热榜图鉴" : (promptSourceIndex == 2 ? "设计精选" : (promptSourceIndex == 3 ? "经典画廊" : "全库"));
        promptCountLabel.setText("共 " + filtered.size() + " 条" + sourceName + "提示词 · 随机展示");
        if (filtered.isEmpty()) {
            promptAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
            String emptyMsg = (promptSourceIndex == 1 && galleryData != null && !galleryData.hotError.isEmpty())
                    ? "热榜资产读取异常：" + galleryData.hotError
                    : "试试切换分类或换个关键词搜索。";
            showStateIn(promptStateBox, emptyCard("没有找到匹配的提示词", emptyMsg, "重置筛选", () -> {
                promptMajor = "全部";
                promptQuery = "";
                if (promptSearchInput != null) promptSearchInput.setText("");
                promptVisibleCount = GALLERY_PAGE_SIZE;
                builtPromptMajorsSignature = "";
                refreshPromptTab(true);
            }));
            return;
        }
        bindPromptToAdapter(filtered);
        if (scrollToTop && promptRecycler != null) promptRecycler.scrollToPosition(0);
    }

    private void rebuildDiscoverChips() {
        if (promptMajorsRow == null) return;
        String sig = "discover|" + promptQuery;
        if (sig.equals(builtPromptMajorsSignature)) return;
        builtPromptMajorsSignature = sig;
        promptMajorsRow.removeAllViews();

        String[] topics = {"摄影", "人像", "海报", "3D", "插画", "动漫", "国风"};
        for (String topic : topics) {
            boolean active = topic.equalsIgnoreCase(promptQuery);
            TextView chip = UiKit.chip(this, topic, active, () -> {
                promptQuery = active ? "" : topic;
                if (promptSearchInput != null) {
                    promptSearchInput.setText(promptQuery);
                    promptSearchInput.setSelection(promptQuery.length());
                }
                builtPromptMajorsSignature = "";
                fetchDiscover(true);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.leftMargin = dp(7);
            promptMajorsRow.addView(chip, clp);
        }
    }

    private void fetchDiscover(boolean reset) {
        if (discoverLoading) return;
        discoverLoading = true;
        discoverError = "";
        final String querySnap = promptQuery;
        final String keySnap = querySnap;
        final int nextPage = reset ? 1 : discoverPage + 1;
        if (reset) {
            discoverItems.clear();
            discoverPage = 0;
            discoverHasMore = true;
            discoverFetchedKey = keySnap;
        }
        if (activeTab == 1 && promptSourceIndex == 4) {
            if (discoverItems.isEmpty()) refreshPromptTab(false);
            else if (promptAdapter != null) promptAdapter.setFooterText("正在按需拉取下一页…");
        }
        new Thread(() -> {
            try {
                InspirationRepository.PageResult result = InspirationRepository.fetchDiscoverPage(
                        querySnap, nextPage, GALLERY_PAGE_SIZE);
                uiHandler.post(() -> {
                    discoverLoading = false;
                    discoverPage = nextPage;
                    discoverHasMore = result.hasMore;
                    discoverFetchedKey = keySnap;
                    List<InspirationRepository.Item> page = new ArrayList<>(result.items);
                    Collections.shuffle(page);
                    discoverItems.addAll(page);
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.promptSwipe != null) act.promptSwipe.setRefreshing(false);
                        if (act.activeTab == 1 && act.promptSourceIndex == 4) act.refreshPromptTab(nextPage == 1);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "在线发现灵感拉取失败，请检查网络。" : e.getMessage();
                uiHandler.post(() -> {
                    discoverLoading = false;
                    discoverError = msg;
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.promptSwipe != null) act.promptSwipe.setRefreshing(false);
                        if (act.activeTab == 1 && act.promptSourceIndex == 4) act.refreshPromptTab(false);
                    }
                });
            }
        }, "jianyu-discover").start();
    }

    private List<InspirationRepository.Item> orderedPromptItems() {
        String tag = promptSourceTag(promptSourceIndex);
        String key = tag + "\n" + promptMajor + "\n" + promptQuery;
        return promptOrder.get(galleryData, key,
                () -> InspirationRepository.filterGallery(galleryData, tag, promptMajor, promptQuery));
    }

    private void bindPromptToAdapter(List<InspirationRepository.Item> filtered) {
        int showCount = Math.min(promptVisibleCount, filtered.size());
        String footer = showCount < filtered.size()
                ? "下滑自动加载更多（剩余 " + (filtered.size() - showCount) + " 条）"
                : "已展示全部 " + filtered.size() + " 条提示词";
        promptAdapter.setData(filtered.subList(0, showCount), favoriteKeySet(), safeBrowse, footer);
    }

    private void rebuildPromptMajorChips() {
        if (galleryData == null || promptMajorsRow == null) return;
        String sourceTag = promptSourceTag(promptSourceIndex);
        List<String> majors = galleryData.getMajors(sourceTag);
        String sig = sourceTag + "|" + promptMajor + "|" + majors.size();
        if (sig.equals(builtPromptMajorsSignature)) return;
        builtPromptMajorsSignature = sig;
        promptMajorsRow.removeAllViews();
        for (int i = 0; i < majors.size(); i++) {
            String major = majors.get(i);
            TextView chip = UiKit.chip(this, major, major.equals(promptMajor), () -> {
                if (!major.equals(promptMajor)) {
                    promptMajor = major;
                    promptVisibleCount = GALLERY_PAGE_SIZE;
                    builtPromptMajorsSignature = "";
                    refreshPromptTab(true);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) clp.leftMargin = dp(7);
            promptMajorsRow.addView(chip, clp);
        }
    }

    // ==================== Tab 2: 创意广场（实时全网灵感流 + 自动分页 + 瀑布流） ====================

    private View buildPlazaTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerPad = new LinearLayout(this);
        headerPad.setOrientation(LinearLayout.VERTICAL);
        headerPad.setPadding(dp(18), dp(14), dp(18), dp(8));

        headerPad.addView(UiKit.pageTitleHeader(this, "创意广场", "全网社区热门作品实时灵感流", null));
        wrap.addView(headerPad, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(this);
        plazaSwipe = new SwipeRefreshLayout(this);
        plazaSwipe.setColorSchemeColors(UiKit.BLUE);
        plazaSwipe.setOnRefreshListener(() -> fetchPlaza(true));

        plazaRecycler = new RecyclerView(this);
        StaggeredGridLayoutManager sglm = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        sglm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        plazaRecycler.setLayoutManager(sglm);
        plazaRecycler.setPadding(dp(13), dp(6), dp(13), dp(112));
        plazaRecycler.setClipToPadding(false);
        plazaRecycler.setItemAnimator(null);

        plazaAdapter = new GalleryAdapters.InspirationAdapter(this, new GalleryAdapters.InspirationListener() {
            @Override public void onItemClick(InspirationRepository.Item item) {
                openInspireDetailOverlay(item);
            }
            @Override public void onQuickSameClick(InspirationRepository.Item item) {
                applyInspirationToHome(item);
            }
            @Override public void onFavoriteToggle(InspirationRepository.Item item) {
                boolean added = InspirationRepository.toggleFavorite(MainActivity.this, item);
                Toast.makeText(MainActivity.this, added ? "已收藏至作品库" : "已取消收藏", Toast.LENGTH_SHORT).show();
                plazaAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
                if (promptAdapter != null) promptAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
                if (favsAdapter != null) refreshLibraryContent();
            }
        });
        plazaRecycler.setAdapter(plazaAdapter);

        // 触底自动分页加载
        plazaRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                StaggeredGridLayoutManager lm = (StaggeredGridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int[] lastPositions = lm.findLastVisibleItemPositions(null);
                int maxLast = 0;
                for (int p : lastPositions) if (p > maxLast) maxLast = p;
                int total = plazaAdapter.getItemCount();
                if (total > 0 && maxLast >= total - 6) {
                    if (plazaHasMore && !plazaLoading) {
                        fetchPlaza(false);
                    }
                }
            }
        });

        plazaSwipe.addView(plazaRecycler, new FrameLayout.LayoutParams(-1, -1));
        body.addView(plazaSwipe, new FrameLayout.LayoutParams(-1, -1));

        plazaStateBox = new FrameLayout(this);
        plazaStateBox.setPadding(dp(18), dp(20), dp(18), dp(112));
        plazaStateBox.setVisibility(View.GONE);
        body.addView(plazaStateBox, new FrameLayout.LayoutParams(-1, -1));

        wrap.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        return wrap;
    }

    private void refreshPlazaTab(boolean scrollToTop) {
        if (plazaStateBox == null) return;
        plazaStateBox.removeAllViews();
        plazaStateBox.setVisibility(View.GONE);

        if (plazaItems.isEmpty() && !plazaLoading && plazaError.isEmpty()) {
            fetchPlaza(true);
            return;
        }
        if (plazaLoading && plazaItems.isEmpty()) {
            plazaAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
            showStateIn(plazaStateBox, loadingHintCard("正在加载创意广场…"));
            return;
        }
        if (!plazaError.isEmpty() && plazaItems.isEmpty()) {
            plazaAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
            showStateIn(plazaStateBox, emptyCard("加载失败", plazaError, "重新加载", () -> fetchPlaza(true)));
            return;
        }
        String footer = plazaLoading ? "正在加载更多灵感…" : (plazaHasMore ? "上滑自动加载更多" : "已经到底啦");
        plazaAdapter.setData(plazaItems, favoriteKeySet(), safeBrowse, footer);
        if (scrollToTop && plazaRecycler != null) plazaRecycler.scrollToPosition(0);
    }

    private Set<String> favoriteKeySet() {
        Set<String> keys = new HashSet<>();
        for (InspirationRepository.Item item : InspirationRepository.getFavorites(this)) {
            keys.add(item.uniqueKey());
        }
        return keys;
    }

    private void showStateIn(FrameLayout container, View card) {
        container.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.TOP;
        container.addView(card, lp);
        container.setVisibility(View.VISIBLE);
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
        if (activeTab == 2) {
            if (plazaItems.isEmpty()) refreshPlazaTab(false);
            else if (plazaAdapter != null) plazaAdapter.setFooterText("正在加载更多灵感…");
        }
        new Thread(() -> {
            try {
                InspirationRepository.PageResult result = InspirationRepository.fetchPlazaPage(nextPage, PLAZA_PAGE_SIZE);
                uiHandler.post(() -> {
                    plazaLoading = false;
                    plazaPage = nextPage;
                    plazaHasMore = result.hasMore;
                    plazaItems.addAll(result.items);
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.plazaSwipe != null) act.plazaSwipe.setRefreshing(false);
                        if (act.activeTab == 2) act.refreshPlazaTab(false);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "创意广场加载失败，请检查网络。" : e.getMessage();
                uiHandler.post(() -> {
                    plazaLoading = false;
                    plazaError = msg;
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.plazaSwipe != null) act.plazaSwipe.setRefreshing(false);
                        if (act.activeTab == 2) act.refreshPlazaTab(false);
                    }
                });
            }
        }, "jianyu-plaza").start();
    }

    private void fetchGallery(boolean forceRefresh) {
        if (galleryLoading) return;
        galleryLoading = true;
        galleryError = "";
        if (forceRefresh) {
            galleryData = null;
            builtPromptMajorsSignature = "";
        }
        if (activeTab == 1) refreshPromptTab(false);
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                InspirationRepository.GalleryData data = InspirationRepository.loadGallery(appContext, forceRefresh);
                uiHandler.post(() -> {
                    galleryLoading = false;
                    galleryData = data;
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.promptSwipe != null) act.promptSwipe.setRefreshing(false);
                        if (act.activeTab == 1) act.refreshPromptTab(false);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "提示词库加载失败，请检查网络。" : e.getMessage();
                uiHandler.post(() -> {
                    galleryLoading = false;
                    galleryError = msg;
                    MainActivity act = visible;
                    if (act != null) {
                        if (act.promptSwipe != null) act.promptSwipe.setRefreshing(false);
                        if (act.activeTab == 1) act.refreshPromptTab(false);
                    }
                });
            }
        }, "jianyu-gallery").start();
    }

    // ==================== Tab 2: 作品库（原生大标题 + RecyclerView 瀑布流 + 批量多选删除） ====================

    private View buildLibraryTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerPad = new LinearLayout(this);
        headerPad.setOrientation(LinearLayout.VERTICAL);
        headerPad.setPadding(dp(18), dp(14), dp(18), dp(8));

        librarySelectBtn = UiKit.text(this, "选择", 13, UiKit.BLUE, true);
        librarySelectBtn.setBackground(UiKit.floatingPillBg(this, 18));
        UiKit.applySoftShadow(librarySelectBtn, this, 3, 0x1E182638);
        librarySelectBtn.setPadding(dp(15), dp(8), dp(15), dp(8));
        librarySelectBtn.setMinimumHeight(dp(38));
        librarySelectBtn.setGravity(Gravity.CENTER);
        librarySelectBtn.setOnClickListener(v -> {
            if (librarySelectMode) exitLibrarySelectMode();
            else enterLibrarySelectMode(null);
        });

        libraryClearRecordsBtn = UiKit.text(this, "清空记录", 13, UiKit.DANGER, true);
        libraryClearRecordsBtn.setBackground(UiKit.floatingPillBg(this, 18));
        UiKit.applySoftShadow(libraryClearRecordsBtn, this, 3, 0x1E182638);
        libraryClearRecordsBtn.setPadding(dp(15), dp(8), dp(15), dp(8));
        libraryClearRecordsBtn.setMinimumHeight(dp(38));
        libraryClearRecordsBtn.setGravity(Gravity.CENTER);
        libraryClearRecordsBtn.setVisibility(View.GONE);
        libraryClearRecordsBtn.setOnClickListener(v -> confirmClearRecords());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleLeft = new LinearLayout(this);
        titleLeft.setOrientation(LinearLayout.VERTICAL);
        titleLeft.addView(UiKit.text(this, "作品库", 29, UiKit.INK, true));
        librarySubtitleView = UiKit.text(this, "0 幅本机作品", 12, UiKit.MUTED, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(2);
        titleLeft.addView(librarySubtitleView, slp);
        titleRow.addView(titleLeft, new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(librarySelectBtn);
        titleRow.addView(libraryClearRecordsBtn);
        headerPad.addView(titleRow);

        librarySegHolder = new LinearLayout(this);
        librarySegHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.topMargin = dp(10);
        headerPad.addView(librarySegHolder, segLp);
        rebuildLibrarySwitcher();

        libraryFilterRow = new LinearLayout(this);
        libraryFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(12);
        headerPad.addView(libraryFilterRow, flp);
        wrap.addView(headerPad, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(this);
        libraryRecycler = new RecyclerView(this);
        StaggeredGridLayoutManager sglm = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        sglm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        libraryRecycler.setLayoutManager(sglm);
        libraryRecycler.setPadding(dp(13), dp(6), dp(13), dp(112));
        libraryRecycler.setClipToPadding(false);
        libraryRecycler.setItemAnimator(null);

        libraryAdapter = new GalleryAdapters.LocalLibraryAdapter(this, service, new GalleryAdapters.LocalArtworkListener() {
            @Override public void onArtworkClick(String id) {
                if (librarySelectMode) {
                    if (librarySelected.contains(id)) librarySelected.remove(id);
                    else librarySelected.add(id);
                    refreshLibraryContent();
                } else {
                    openDetailOverlay(id);
                }
            }

            @Override public void onArtworkLongClick(String id) {
                enterLibrarySelectMode(id);
            }
        });
        libraryRecycler.setAdapter(libraryAdapter);
        body.addView(libraryRecycler, new FrameLayout.LayoutParams(-1, -1));

        recordsRecycler = new RecyclerView(this);
        recordsRecycler.setLayoutManager(new LinearLayoutManager(this));
        recordsRecycler.setPadding(dp(13), dp(6), dp(13), dp(112));
        recordsRecycler.setClipToPadding(false);
        recordsRecycler.setItemAnimator(null);
        recordsRecycler.setVisibility(View.GONE);

        recordAdapter = new GalleryAdapters.RecordAdapter(this, new GalleryAdapters.RecordListener() {
            @Override public void onViewArtwork(String imageId) {
                openDetailOverlay(imageId);
            }
            @Override public void onRetry(GenerationRecord record) {
                retryRecord(record);
            }
            @Override public void onUsePrompt(String prompt) {
                usePrompt(prompt);
            }
            @Override public void onCopyPrompt(String prompt) {
                copyPrompt(prompt);
            }
            @Override public void onDeleteRecord(String recordId) {
                if (GenerationTasks.get(recordId) != null) {
                    Toast.makeText(MainActivity.this, "请先取消这条任务的等待，再删除记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                GenerationRecord.Repository.deleteRecord(MainActivity.this, recordId);
                refreshLibraryContent();
            }
        });
        recordsRecycler.setAdapter(recordAdapter);
        body.addView(recordsRecycler, new FrameLayout.LayoutParams(-1, -1));

        favsRecycler = new RecyclerView(this);
        StaggeredGridLayoutManager favLm = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        favLm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        favsRecycler.setLayoutManager(favLm);
        favsRecycler.setPadding(dp(13), dp(6), dp(13), dp(112));
        favsRecycler.setClipToPadding(false);
        favsRecycler.setItemAnimator(null);
        favsRecycler.setVisibility(View.GONE);

        favsAdapter = new GalleryAdapters.InspirationAdapter(this, new GalleryAdapters.InspirationListener() {
            @Override public void onItemClick(InspirationRepository.Item item) {
                openInspireDetailOverlay(item);
            }
            @Override public void onQuickSameClick(InspirationRepository.Item item) {
                applyInspirationToHome(item);
            }
            @Override public void onFavoriteToggle(InspirationRepository.Item item) {
                boolean added = InspirationRepository.toggleFavorite(MainActivity.this, item);
                Toast.makeText(MainActivity.this, added ? "已收藏至作品库" : "已取消收藏", Toast.LENGTH_SHORT).show();
                refreshLibraryContent();
                if (promptAdapter != null) promptAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
                if (plazaAdapter != null) plazaAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            }
        });
        favsRecycler.setAdapter(favsAdapter);
        body.addView(favsRecycler, new FrameLayout.LayoutParams(-1, -1));

        libraryEmptyBox = new FrameLayout(this);
        libraryEmptyBox.setPadding(dp(18), dp(20), dp(18), dp(112));
        libraryEmptyBox.setVisibility(View.GONE);
        body.addView(libraryEmptyBox, new FrameLayout.LayoutParams(-1, -1));

        wrap.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        return wrap;
    }

    private List<String> filteredLibraryIds() {
        String[] allIds = listLocalArtworkIds();
        List<String> out = new ArrayList<>();
        for (String id : allIds) {
            if (libraryFilter == 0) out.add(id);
            else {
                String m = service.modelLabel(id);
                if (libraryFilter == 1 && "GPT".equals(m)) out.add(id);
                else if (libraryFilter == 2 && "Grok".equals(m)) out.add(id);
            }
        }
        return out;
    }

    private void rebuildLibrarySwitcher() {
        if (librarySegHolder == null) return;
        librarySegHolder.removeAllViews();
        librarySegHolder.addView(UiKit.segmentedControl(this, new String[]{"成品作品", "生成记录", "灵感收藏"}, librarySubTab, 13.0f, idx -> {
            if (librarySubTab != idx) {
                librarySubTab = idx;
                rebuildLibrarySwitcher();
                refreshLibraryContent();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshLibraryContent() {
        if (libraryFilterRow == null || libraryAdapter == null) return;
        rebuildLibrarySwitcher();

        if (librarySubTab == 0) {
            recordsRecycler.setVisibility(View.GONE);
            if (favsRecycler != null) favsRecycler.setVisibility(View.GONE);
            libraryRecycler.setVisibility(View.VISIBLE);
            libraryClearRecordsBtn.setVisibility(View.GONE);

            String[] allIds = listLocalArtworkIds();
            librarySubtitleView.setText(allIds.length + " 幅本机作品 · 长按可多选管理");
            librarySelectBtn.setVisibility(allIds.length > 0 ? View.VISIBLE : View.GONE);
            librarySelectBtn.setText(librarySelectMode ? "完成" : "选择");

            libraryFilterRow.setVisibility(View.VISIBLE);
            libraryFilterRow.removeAllViews();
            String[] filters = {"全部", "GPT", "Grok"};
            for (int i = 0; i < filters.length; i++) {
                final int idx = i;
                TextView chip = UiKit.chip(this, filters[i], idx == libraryFilter, () -> {
                    if (libraryFilter != idx) {
                        libraryFilter = idx;
                        refreshLibraryContent();
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) clp.leftMargin = dp(8);
                libraryFilterRow.addView(chip, clp);
            }

            List<String> filtered = filteredLibraryIds();
            librarySelected.retainAll(filtered);
            libraryBatchCount.setText("已选 " + librarySelected.size() + " 项");

            if (filtered.isEmpty()) {
                libraryAdapter.setData(Collections.emptyList(), false, Collections.emptySet());
                String title = allIds.length == 0 ? "作品库还是空的" : "该筛选下暂无作品";
                String desc = allIds.length == 0
                        ? "生成的图片会自动保存在本机，随时可欣赏、直存相册或再创作。"
                        : "切换到「全部」查看已有作品，或去创作一幅新图。";
                showStateIn(libraryEmptyBox, emptyCard(title, desc, "开始创作", () -> switchTab(0)));
            } else {
                libraryEmptyBox.setVisibility(View.GONE);
                libraryAdapter.setData(filtered, librarySelectMode, librarySelected);
            }
        } else if (librarySubTab == 1) {
            if (librarySelectMode) exitLibrarySelectMode();
            libraryRecycler.setVisibility(View.GONE);
            if (favsRecycler != null) favsRecycler.setVisibility(View.GONE);
            recordsRecycler.setVisibility(View.VISIBLE);
            libraryFilterRow.setVisibility(View.GONE);
            librarySelectBtn.setVisibility(View.GONE);

            List<GenerationRecord> records = GenerationRecord.Repository.getAll(this);
            librarySubtitleView.setText("共 " + records.size() + " 条生成任务记录 · 实时保存");
            libraryClearRecordsBtn.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);

            if (records.isEmpty()) {
                recordAdapter.setData(Collections.emptyList());
                showStateIn(libraryEmptyBox, emptyCard("暂无生成记录", "每次绘制的任务状态、耗时、模型参数与提示词都将完整保存在这里，方便随时复用或重试。", "开始创作", () -> switchTab(0)));
            } else {
                libraryEmptyBox.setVisibility(View.GONE);
                recordAdapter.setData(records);
            }
        } else {
            if (librarySelectMode) exitLibrarySelectMode();
            libraryRecycler.setVisibility(View.GONE);
            recordsRecycler.setVisibility(View.GONE);
            if (favsRecycler != null) favsRecycler.setVisibility(View.VISIBLE);
            libraryFilterRow.setVisibility(View.GONE);
            librarySelectBtn.setVisibility(View.GONE);
            libraryClearRecordsBtn.setVisibility(View.GONE);

            List<InspirationRepository.Item> favs = InspirationRepository.getFavorites(this);
            librarySubtitleView.setText("共收藏 " + favs.size() + " 条提示词灵感 · 本机永久保存");

            if (favs.isEmpty()) {
                if (favsAdapter != null) favsAdapter.setData(Collections.emptyList(), favoriteKeySet(), safeBrowse, "");
                showStateIn(libraryEmptyBox, emptyCard("暂无收藏灵感", "在提示词工坊、发现灵感或创意广场轻触红心，即可将心仪的灵感收入作品库。", "去发现灵感", () -> {
                    promptSourceIndex = 4;
                    builtPromptMajorsSignature = "";
                    rebuildPromptSourceSwitcher();
                    switchTab(1);
                }));
            } else {
                libraryEmptyBox.setVisibility(View.GONE);
                if (favsAdapter != null) {
                    favsAdapter.setData(favs, favoriteKeySet(), safeBrowse, "共收藏 " + favs.size() + " 条提示词灵感");
                }
            }
        }
        updateFloatingBarsVisibility();
    }

    private void retryRecord(GenerationRecord record) {
        if (record == null) return;
        if (GenerationTasks.get(record.recordId) != null) {
            openLoadingOverlay(record.recordId);
            return;
        }
        if (GenerationTasks.canResume(this, record.recordId)) {
            if (!GenerationTasks.start(this, record, MainActivity::finished)) return;
            startGenTimer();
            refreshHomeDynamic();
            refreshLibraryContent();
            openLoadingOverlay(record.recordId);
            return;
        }
        if (record.status == GenerationRecord.STATUS_UNCONFIRMED) {
            new AlertDialog.Builder(this).setTitle("请先核对原任务")
                    .setMessage("提交结果尚未确认，服务端可能已接收。请先核对任务与额度记录；填入描述不会立即重新提交。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("已核对，填入参数", (d, w) -> loadRecordParameters(record)).show();
            return;
        }
        loadRecordParameters(record);
    }

    private void loadRecordParameters(GenerationRecord record) {
        nsfw = record.nsfw;
        selectedRoute = Math.max(0, Math.min(1, record.route));
        if (selectedRoute == 0) {
            if ("image-2".equals(record.wireModel) || "Grok".equalsIgnoreCase(record.model)) modelLine1 = 1;
            else modelLine1 = 0;
        } else {
            if (("gpt-image-2.5-flare".equals(record.wireModel) || "gpt-2.5-flare".equals(record.wireModel))) modelLine2 = 1;
            else if (("gpt-image-2.5-sunburst".equals(record.wireModel) || "gpt-2.5-sunburst".equals(record.wireModel))) modelLine2 = 2;
            else if (("gpt-image-2".equals(record.wireModel) || "gpt-2".equals(record.wireModel))) modelLine2 = 3;
            else modelLine2 = 0;
        }
        setRatioByName(record.ratio);
        draft = record.prompt;
        if (promptInput != null) {
            promptInput.setText(record.prompt);
            promptInput.setSelection(record.prompt.length());
        }
        if (record.hasRef && record.refUriStr != null && !record.refUriStr.isEmpty()) {
            reference = Uri.parse(record.refUriStr);
        } else {
            reference = null;
        }
        switchTab(0);
        refreshHomeDynamic();
        Toast.makeText(this, "已载入历史生图参数与描述", Toast.LENGTH_SHORT).show();
    }

    private void usePrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return;
        draft = prompt;
        if (promptInput != null) {
            promptInput.setText(prompt);
            promptInput.setSelection(prompt.length());
        }
        switchTab(0);
        refreshHomeDynamic();
        Toast.makeText(this, "已填入画面描述", Toast.LENGTH_SHORT).show();
    }

    private void copyPrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("prompt", prompt));
            Toast.makeText(this, "提示词已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearRecords() {
        new AlertDialog.Builder(this)
                .setTitle("清空生成记录")
                .setMessage("清空已结束的生成记录及其待取图信息？正在生成的任务和本地成品图会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    GenerationRecord.Repository.clearAll(this);
                    refreshLibraryContent();
                    Toast.makeText(this, "已清空结束的记录，正在生成的任务已保留", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void enterLibrarySelectMode(String initialId) {
        librarySelectMode = true;
        librarySelected.clear();
        if (initialId != null && !initialId.isEmpty()) librarySelected.add(initialId);
        refreshLibraryContent();
    }

    private void exitLibrarySelectMode() {
        librarySelectMode = false;
        librarySelected.clear();
        refreshLibraryContent();
    }

    private void toggleSelectAllLibrary() {
        List<String> visibleIds = filteredLibraryIds();
        if (librarySelected.size() >= visibleIds.size()) librarySelected.clear();
        else librarySelected.addAll(visibleIds);
        refreshLibraryContent();
    }

    private void confirmBatchDelete() {
        if (librarySelected.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的作品", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = librarySelected.size();
        new AlertDialog.Builder(this)
                .setTitle("批量删除作品")
                .setMessage("确定从本机作品库删除选中的 " + count + " 幅作品吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    for (String id : new ArrayList<>(librarySelected)) {
                        service.deleteArtwork(id);
                    }
                    exitLibrarySelectMode();
                    refreshHomeDynamic();
                    Toast.makeText(this, "已删除 " + count + " 幅作品", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ==================== 二级页面 1：生成中动态光球（可最小化 + 可主动取消等待） ====================

    private void showGenerationRecords() {
        popOverlaysByTag("loading");
        librarySubTab = 1;
        switchTab(3);
        refreshLibraryContent();
    }

    private void openLoadingOverlay() {
        if (GenerationTasks.count() != 1) {
            showGenerationRecords();
            return;
        }
        GenerationTasks.Task task = GenerationTasks.first();
        if (task != null) openLoadingOverlay(task.record.recordId);
    }

    private void openLoadingOverlay(String recordId) {
        GenerationTasks.Task task = GenerationTasks.get(recordId);
        if (task == null) { showGenerationRecords(); return; }
        if (isOverlayOpen("loading") && recordId.equals(loadingRecordId)) return;
        popOverlaysByTag("loading");
        loadingRecordId = recordId;
        GenerationRecord record = task.record;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(UiKit.auroraCanvasBg(this));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::popOverlay),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = UiKit.text(this, "任务进度", 16.5f, UiKit.INK, true);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(1)));
        page.addView(topBar);

        LinearLayout orbHolder = new LinearLayout(this);
        orbHolder.setOrientation(LinearLayout.VERTICAL);
        orbHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        AuroraOrbView orb = new AuroraOrbView(this);
        orbHolder.addView(orb, new LinearLayout.LayoutParams(dp(220), dp(220)));

        loadingStatusView = UiKit.text(this, GenerationRecord.statusLabel(record.status), 20, UiKit.INK, true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        orbHolder.addView(loadingStatusView, slp);

        long sec = Math.max(1L, (android.os.SystemClock.elapsedRealtime() - task.startedAt) / 1000L);
        loadingTimerView = UiKit.text(this, record.status == GenerationRecord.STATUS_DOWNLOADING
                ? task.service.downloadProgress() : "已等待 " + sec + " 秒 · 完成后自动存入作品库", 13, UiKit.MUTED, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(6);
        orbHolder.addView(loadingTimerView, tlp);

        LinearLayout.LayoutParams ohlp = new LinearLayout.LayoutParams(-1, -2);
        ohlp.topMargin = dp(12);
        page.addView(orbHolder, ohlp);

        String summaryPrompt = record.prompt;
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
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
            tagLp.topMargin = dp(10);
            String m = record.model;
            String r = record.ratio;
            tags.addView(UiKit.softBadge(this, m, UiKit.BLUE_SOFT, UiKit.BLUE));
            TextView rBadge = UiKit.softBadge(this, r, UiKit.SOFT, UiKit.MUTED);
            LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-2, -2);
            rbp.leftMargin = dp(6);
            tags.addView(rBadge, rbp);
            if (record.hasRef) {
                TextView refBadge = UiKit.softBadge(this, "图改图", UiKit.SOFT, UiKit.MUTED);
                LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(-2, -2);
                fbp.leftMargin = dp(6);
                tags.addView(refBadge, fbp);
            }
            card.addView(tags, tagLp);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = dp(18);
            page.addView(card, clp);
        }

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout bgBtn = UiKit.glassButton(this, R.drawable.ic_compass, "继续创作", UiKit.GLASS_STRONG, UiKit.BLUE, () -> {
            popOverlay();
            switchTab(0);
            Toast.makeText(this, "这条任务继续生成，可以输入另一条提示词", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp1.rightMargin = dp(6);
        actionRow.addView(bgBtn, lp1);

        LinearLayout cancelBtn = UiKit.glassButton(this, R.drawable.ic_close, "取消等待", 0xF0FFF0F0, UiKit.DANGER, () ->
                new AlertDialog.Builder(this)
                        .setTitle("取消本次等待")
                        .setMessage("仅中断这条任务的等待，其他任务继续生成。有任务编号时可在生成记录中继续获取。")
                        .setNegativeButton("继续等待", null)
                        .setPositiveButton("中断等待", (d, w) -> {
                            GenerationTasks.cancel(recordId);
                            popOverlaysByTag("loading");
                            refreshHomeDynamic();
                            refreshLibraryContent();
                            updateFloatingBarsVisibility();
                            Toast.makeText(this, "已取消等待", Toast.LENGTH_SHORT).show();
                        }).show());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp2.leftMargin = dp(6);
        actionRow.addView(cancelBtn, lp2);

        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(-1, -2);
        arlp.topMargin = dp(16);
        page.addView(actionRow, arlp);

        pushOverlay("loading", scroll);
    }

    // ==================== 二级页面 2：本机作品详情（全屏缩放 + 一键直存相册 + 分享 + 元数据） ====================

    private void openDetailOverlay(String id) {
        current = id;
        File localFile = id.isEmpty() ? null : new File(new File(getFilesDir(), "images"), id);
        if (localFile != null && !localFile.isFile()) {
            Toast.makeText(this, "作品文件不存在", Toast.LENGTH_SHORT).show();
            refreshLibraryContent();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(UiKit.auroraCanvasBg(this));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(UiKit.round(this, UiKit.SOFT, 26));
        frame.setClipToOutline(true);
        UiKit.applySoftShadow(frame, this, 16, 0x503B74E6);

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setMinimumHeight(dp(260));
        if (localFile == null) {
            image.setImageResource(R.drawable.coast);
        } else {
            InspirationRepository.loadLocalThumb(image, localFile, 1600);
        }
        image.setOnClickListener(v -> openFullscreenZoom(localFile, null, null));
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout backCircle = UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::popOverlay);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(44), dp(44));
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.leftMargin = dp(12);
        blp.topMargin = dp(12);
        frame.addView(backCircle, blp);

        LinearLayout rightCircles = new LinearLayout(this);
        rightCircles.setOrientation(LinearLayout.HORIZONTAL);
        if (localFile != null) {
            LinearLayout shareCircle = UiKit.circleIconBtn(this, R.drawable.ic_share, UiKit.INK, UiKit.GLASS_STRONG, () -> shareLocalArtwork(id, localFile));
            rightCircles.addView(shareCircle, new LinearLayout.LayoutParams(dp(44), dp(44)));

            LinearLayout delCircle = UiKit.circleIconBtn(this, R.drawable.ic_trash, UiKit.DANGER, 0xF0FFF0F0, () ->
                    new AlertDialog.Builder(this)
                            .setTitle("删除作品")
                            .setMessage("确定从本机作品库删除这幅作品吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (d, w) -> {
                                service.deleteArtwork(id);
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                                popOverlay();
                                refreshHomeDynamic();
                                refreshLibraryContent();
                            }).show());
            LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(dp(44), dp(44));
            dclp.leftMargin = dp(8);
            rightCircles.addView(delCircle, dclp);
        }
        FrameLayout.LayoutParams rclp = new FrameLayout.LayoutParams(-2, -2);
        rclp.gravity = Gravity.TOP | Gravity.END;
        rclp.rightMargin = dp(12);
        rclp.topMargin = dp(12);
        frame.addView(rightCircles, rclp);

        String badgeText = id.isEmpty() ? "内置示例" : artworkBadge(id);
        if (!badgeText.isEmpty()) {
            TextView tag = UiKit.frostedBadge(this, badgeText);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
            tlp.gravity = Gravity.BOTTOM | Gravity.START;
            tlp.leftMargin = dp(12);
            tlp.bottomMargin = dp(12);
            frame.addView(tag, tlp);
        }

        TextView zoomHint = UiKit.frostedBadge(this, "轻触全屏缩放");
        FrameLayout.LayoutParams zlp = new FrameLayout.LayoutParams(-2, -2);
        zlp.gravity = Gravity.BOTTOM | Gravity.END;
        zlp.rightMargin = dp(12);
        zlp.bottomMargin = dp(12);
        frame.addView(zoomHint, zlp);

        page.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        // 真实分辨率 / 文件体积 / 生成时间元数据条
        String metaSummary = service.artworkMetaSummary(id);
        if (!metaSummary.isEmpty()) {
            TextView metaView = UiKit.text(this, metaSummary, 12, UiKit.MUTED, false);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(10);
            mlp.leftMargin = dp(4);
            page.addView(metaView, mlp);
        }

        String promptText = description(id);
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(-1, -2);
        ptlp.topMargin = dp(12);
        page.addView(promptDetailCard(promptText), ptlp);

        LinearLayout savePrimary = UiKit.prismButton(this, R.drawable.ic_download, "一键保存至相册", () -> saveLocalArtwork(id));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-1, dp(52));
        splp.topMargin = dp(16);
        page.addView(savePrimary, splp);

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
            clearOverlays();
            switchTab(0);
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp1.rightMargin = dp(6);
        secondaryRow.addView(remix, lp1);

        LinearLayout asRef = UiKit.glassButton(this, R.drawable.ic_image_plus, "设为参考图", UiKit.GLASS_STRONG, UiKit.INK, () -> {
            if (localFile == null) {
                Toast.makeText(this, "内置示例仅支持同款再创作", Toast.LENGTH_SHORT).show();
                return;
            }
            reference = Uri.fromFile(localFile);
            Toast.makeText(this, "已设为参考图，可输入修改描述", Toast.LENGTH_SHORT).show();
            clearOverlays();
            switchTab(0);
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp2.leftMargin = dp(6);
        secondaryRow.addView(asRef, lp2);

        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(10);
        page.addView(secondaryRow, srlp);

        pushOverlay("detail", scroll);
    }

    // ==================== 二级页面 3：灵感详情（渐进高清加载 + 收藏 + 分享 + 一键直存 + 全屏缩放） ====================

    private void openInspireDetailOverlay(InspirationRepository.Item item) {
        activeInspiration = item;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(UiKit.auroraCanvasBg(this));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(UiKit.round(this, UiKit.SOFT, 26));
        frame.setClipToOutline(true);
        UiKit.applySoftShadow(frame, this, 16, 0x503B74E6);

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setMinimumHeight(dp(260));
        // 渐进式加载：0ms 秒显已缓存缩略图，后台高清原图无缝替换
        InspirationRepository.loadProgressiveImage(this, image, item.thumbUrl, item.imageUrl, 1600);
        image.setOnClickListener(v -> openFullscreenZoom(null, item.thumbUrl, item.imageUrl));
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout backCircle = UiKit.circleIconBtn(this, R.drawable.ic_back, UiKit.INK, UiKit.GLASS_STRONG, this::popOverlay);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(44), dp(44));
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.leftMargin = dp(12);
        blp.topMargin = dp(12);
        frame.addView(backCircle, blp);

        LinearLayout rightCircles = new LinearLayout(this);
        rightCircles.setOrientation(LinearLayout.HORIZONTAL);

        boolean initialFav = InspirationRepository.isFavorite(this, item);
        LinearLayout favCircle = new LinearLayout(this);
        favCircle.setGravity(Gravity.CENTER);
        favCircle.setBackground(UiKit.card(this, UiKit.GLASS_STRONG, 0xFFE4ECF6, 22));
        UiKit.applySoftShadow(favCircle, this, 5, 0x2B182638);
        ImageView favIcon = UiKit.icon(this, initialFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, 19,
                initialFav ? 0xFFE5484D : UiKit.INK);
        favCircle.addView(favIcon);
        MotionKit.attachPressSpring(favCircle, 0.90f);
        favCircle.setOnClickListener(v -> {
            boolean nowFav = InspirationRepository.toggleFavorite(this, item);
            MotionKit.heartBeat(favIcon);
            favIcon.setImageResource(nowFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            favIcon.setColorFilter(nowFav ? 0xFFE5484D : UiKit.INK, PorterDuff.Mode.SRC_IN);
            Toast.makeText(this, nowFav ? "已收藏至作品库" : "已取消收藏", Toast.LENGTH_SHORT).show();
            if (activeTab == 1) refreshPromptTab(false);
            else if (activeTab == 2) refreshPlazaTab(false);
            else if (activeTab == 3) refreshLibraryContent();
            if (promptAdapter != null) promptAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            if (plazaAdapter != null) plazaAdapter.updateFavoritesAndSafeBrowse(favoriteKeySet(), safeBrowse);
            if (favsAdapter != null) refreshLibraryContent();
        });
        rightCircles.addView(favCircle, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout shareCircle = UiKit.circleIconBtn(this, R.drawable.ic_share, UiKit.INK, UiKit.GLASS_STRONG, () -> shareRemoteImage(item));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(dp(44), dp(44));
        sclp.leftMargin = dp(8);
        rightCircles.addView(shareCircle, sclp);

        FrameLayout.LayoutParams rclp = new FrameLayout.LayoutParams(-2, -2);
        rclp.gravity = Gravity.TOP | Gravity.END;
        rclp.rightMargin = dp(12);
        rclp.topMargin = dp(12);
        frame.addView(rightCircles, rclp);

        String corner = !item.model.isEmpty() ? item.model + " · " + item.ratio
                : (!item.major.isEmpty() ? item.major + " · " + item.ratio : item.ratio);
        TextView cornerTag = UiKit.frostedBadge(this, corner);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.BOTTOM | Gravity.START;
        clp.leftMargin = dp(12);
        clp.bottomMargin = dp(12);
        frame.addView(cornerTag, clp);

        TextView zoomHint = UiKit.frostedBadge(this, "轻触全屏缩放");
        FrameLayout.LayoutParams zlp = new FrameLayout.LayoutParams(-2, -2);
        zlp.gravity = Gravity.BOTTOM | Gravity.END;
        zlp.rightMargin = dp(12);
        zlp.bottomMargin = dp(12);
        frame.addView(zoomHint, zlp);

        page.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        String heading = item.isDiscover() ? item.displayTitle() : !item.title.isEmpty() ? item.title : "创意广场灵感";
        metaRow.addView(UiKit.text(this, heading, 17.5f, UiKit.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView rightMetaView = UiKit.text(this, buildInspireRightMeta(item), 12, UiKit.MUTED, false);
        metaRow.addView(rightMetaView);
        LinearLayout.LayoutParams mrlp = new LinearLayout.LayoutParams(-1, -2);
        mrlp.topMargin = dp(12);
        page.addView(metaRow, mrlp);

        LinearLayout pCard = new LinearLayout(this);
        pCard.setOrientation(LinearLayout.VERTICAL);
        pCard.setBackground(UiKit.liquidGlass(this, 22));
        UiKit.applySoftShadow(pCard, this, 6, 0x1E182638);
        pCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiKit.text(this, "画面描述", 12.5f, UiKit.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiKit.pillTagButton(this, R.drawable.ic_copy, "复制", UiKit.BLUE_SOFT, UiKit.BLUE, () -> copyInspirationPrompt(item)));
        pCard.addView(head);

        String initialPrompt = item.needsDetailFetch() ? "正在获取中文提示词…" : item.displayPrompt();
        TextView promptBodyView = UiKit.text(this, initialPrompt, 13.5f, UiKit.INK, false);
        promptBodyView.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams pblp0 = new LinearLayout.LayoutParams(-1, -2);
        pblp0.topMargin = dp(8);
        pCard.addView(promptBodyView, pblp0);

        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(-1, -2);
        ptlp.topMargin = dp(12);
        page.addView(pCard, ptlp);

        if (item.needsDetailFetch()) {
            Context appCtx = getApplicationContext();
            new Thread(() -> {
                try {
                    String loaded = InspirationRepository.fetchDiscoverDetail(appCtx, item);
                    uiHandler.post(() -> {
                        promptBodyView.setText(item.displayPrompt());
                        rightMetaView.setText(buildInspireRightMeta(item));
                        String updatedCorner = !item.model.isEmpty() ? item.model + " · " + item.ratio
                                : (!item.major.isEmpty() ? item.major + " · " + item.ratio : item.ratio);
                        cornerTag.setText(updatedCorner);
                    });
                } catch (Exception e) {
                    uiHandler.post(() -> promptBodyView.setText("中文提示词加载失败，请重新打开重试"));
                }
            }, "jianyu-inspire-detail").start();
        }

        LinearLayout primaryBtn = UiKit.prismButton(this, R.drawable.ic_sparkles, "一键做同款", () -> applyInspirationToHome(item));
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, dp(52));
        pblp.topMargin = dp(16);
        page.addView(primaryBtn, pblp);

        LinearLayout subActions = new LinearLayout(this);
        subActions.setOrientation(LinearLayout.HORIZONTAL);
        boolean hasSpecificRef = item.refUrl != null && !item.refUrl.isEmpty();
        String refLabel = hasSpecificRef ? "设为原配参考图" : "设为参考图";
        LinearLayout asRef = UiKit.glassButton(this, R.drawable.ic_image_plus, refLabel, UiKit.GLASS_STRONG, UiKit.BLUE, () -> useRemoteAsReference(item));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp1.rightMargin = dp(6);
        subActions.addView(asRef, lp1);

        LinearLayout saveBtn = UiKit.glassButton(this, R.drawable.ic_download, "保存原图", UiKit.GLASS_STRONG, UiKit.INK, () -> saveRemoteImage(item));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp2.leftMargin = dp(6);
        subActions.addView(saveBtn, lp2);

        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-1, -2);
        salp.topMargin = dp(10);
        page.addView(subActions, salp);

        pushOverlay("inspire_detail", scroll);
    }

    // ==================== 二级页面 4：全屏双指缩放看图器 ====================

    private void openFullscreenZoom(File localFile, String thumbUrl, String fullUrl) {
        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackgroundColor(0xF50A0E17);

        ZoomImageView zoom = new ZoomImageView(this);
        zoom.setOnSingleTap(this::popOverlay);
        if (localFile != null) {
            InspirationRepository.loadLocalThumb(zoom, localFile, 2200);
        } else if (fullUrl != null) {
            InspirationRepository.loadProgressiveImage(this, zoom, thumbUrl, fullUrl, 2200);
        } else {
            zoom.setImageResource(R.drawable.coast);
        }
        viewer.addView(zoom, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout closeBtn = UiKit.circleIconBtn(this, R.drawable.ic_close, Color.WHITE, 0x88141E2E, this::popOverlay);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(44), dp(44));
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.topMargin = dp(14);
        clp.rightMargin = dp(14);
        viewer.addView(closeBtn, clp);

        TextView tip = UiKit.frostedBadge(this, "双指缩放 · 双击放大 · 轻触退出");
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tlp.bottomMargin = dp(24);
        viewer.addView(tip, tlp);

        pushOverlay("zoom", viewer);
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

    // ==================== 一键保存至相册 / 分享 / 同款 / 参考图 ====================

    private void saveLocalArtwork(String id) {
        String ext = id.isEmpty() ? "jpg" : id.substring(id.lastIndexOf('.') + 1);
        String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
        String displayName = "见屿-" + (id.isEmpty() ? "海岸晨光.jpg" : id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            new Thread(() -> {
                try (InputStream in = id.isEmpty()
                        ? getResources().openRawResource(R.drawable.coast)
                        : new FileInputStream(new File(new File(getFilesDir(), "images"), id))) {
                    service.saveToSystemGallery(in, displayName, mime);
                    uiHandler.post(() -> Toast.makeText(this, "已保存到相册 Pictures/见屿", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    uiHandler.post(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "jianyu-save-local").start();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, displayName);
        startActivityForResult(intent, SAVE_LOCAL_IMAGE);
    }

    private static String buildInspireRightMeta(InspirationRepository.Item item) {
        StringBuilder rightMeta = new StringBuilder();
        if (item.isDiscover()) return "精选灵感 · " + item.ratio;
        if (!item.author.isEmpty()) rightMeta.append("by ").append(item.author);
        else if (!item.category.isEmpty()) rightMeta.append(item.category);
        if (item.width > 0 && item.height > 0) {
            if (rightMeta.length() > 0) rightMeta.append(" · ");
            rightMeta.append(item.width).append("×").append(item.height);
        }
        return rightMeta.toString();
    }

    private void copyInspirationPrompt(InspirationRepository.Item item) {
        if (item == null) return;
        if (!item.needsDetailFetch()) {
            copyLoadedInspiration(item);
            return;
        }
        Toast.makeText(this, "正在获取中文提示词…", Toast.LENGTH_SHORT).show();
        Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                String loaded = InspirationRepository.fetchDiscoverDetail(appCtx, item);
                uiHandler.post(() -> copyLoadedInspiration(item));
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "中文提示词加载失败，请重试", Toast.LENGTH_SHORT).show());
            }
        }, "jianyu-copy-detail").start();
    }

    private void copyLoadedInspiration(InspirationRepository.Item item) {
        if (item.prompt.isEmpty()) {
            Toast.makeText(this, item.displayPrompt(), Toast.LENGTH_SHORT).show();
            return;
        }
        copyText(item.prompt);
    }

    private void saveRemoteImage(InspirationRepository.Item item) {
        String url = item.imageUrl.toLowerCase(Locale.ROOT);
        String ext = url.endsWith(".png") ? "png" : url.endsWith(".webp") ? "webp" : "jpg";
        String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
        String safeId = item.id.length() > 36 ? item.id.substring(0, 36) : item.id;
        String displayName = "见屿-灵感-" + safeId + "." + ext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Toast.makeText(this, "正在保存原图至相册…", Toast.LENGTH_SHORT).show();
            Context appContext = getApplicationContext();
            new Thread(() -> {
                try {
                    File cached = InspirationRepository.ensureCachedImage(appContext, item.imageUrl);
                    try (InputStream in = new FileInputStream(cached)) {
                        service.saveToSystemGallery(in, displayName, mime);
                    }
                    uiHandler.post(() -> Toast.makeText(this, "原图已保存到相册 Pictures/见屿", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    uiHandler.post(() -> Toast.makeText(this, "保存失败，请稍后重试。", Toast.LENGTH_LONG).show());
                }
            }, "jianyu-save-remote-q").start();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, displayName);
        startActivityForResult(intent, SAVE_REMOTE_IMAGE);
    }

    private void shareLocalArtwork(String id, File file) {
        String ext = id.substring(id.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
        try {
            service.shareImageFile(this, file, mime);
        } catch (Exception e) {
            Toast.makeText(this, "无法调起分享：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareRemoteImage(InspirationRepository.Item item) {
        Toast.makeText(this, "正在准备分享原图…", Toast.LENGTH_SHORT).show();
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                File cached = InspirationRepository.ensureCachedImage(appContext, item.imageUrl);
                String url = item.imageUrl.toLowerCase(Locale.ROOT);
                String mime = url.endsWith(".png") ? "image/png" : url.endsWith(".webp") ? "image/webp" : "image/jpeg";
                uiHandler.post(() -> {
                    MainActivity act = visible;
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    try {
                        act.service.shareImageFile(act, cached, mime);
                    } catch (Exception e) {
                        Toast.makeText(act, "无法调起分享：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "原图准备失败，请稍后重试。", Toast.LENGTH_SHORT).show());
            }
        }, "jianyu-share-remote").start();
    }

    private void applyInspirationToHome(InspirationRepository.Item item) {
        if (item == null) return;
        if (!item.needsDetailFetch()) {
            applyInspirationToHomeImmediate(item);
            return;
        }
        Toast.makeText(this, "正在拉取完整提示词…", Toast.LENGTH_SHORT).show();
        Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                InspirationRepository.fetchDiscoverDetail(appCtx, item);
            } catch (Exception ignored) { }
            uiHandler.post(() -> {
                MainActivity act = visible;
                if (act == null || act.isFinishing() || act.isDestroyed()) return;
                act.applyInspirationToHomeImmediate(item);
            });
        }, "jianyu-same-detail").start();
    }

    private void applyInspirationToHomeImmediate(InspirationRepository.Item item) {
        if (item.isDiscover() && item.prompt.isEmpty()) {
            Toast.makeText(this, item.displayPrompt(), Toast.LENGTH_SHORT).show();
            return;
        }
        draft = (item.prompt == null || item.prompt.isEmpty()) ? item.title : item.prompt;
        if (item.model != null && !item.model.isEmpty()) {
            setModelByName(item.model);
        }
        setRatioByName(item.ratio);
        reference = null;
        Toast.makeText(this, "已填入同款提示词与参数", Toast.LENGTH_SHORT).show();
        clearOverlays();
        switchTab(0);
    }

    private void useRemoteAsReference(InspirationRepository.Item item) {
        Toast.makeText(this, "正在准备参考图…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String target = (item.refUrl != null && !item.refUrl.isEmpty()) ? item.refUrl : item.imageUrl;
                Uri cached = service.cacheRemoteReference(target);
                uiHandler.post(() -> {
                    MainActivity act = visible;
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    act.reference = cached;
                    act.setRatioByName(item.ratio);
                    String tip = (item.refUrl != null && !item.refUrl.isEmpty()) ? "已载入原配参考图，可输入修改描述" : "已设为参考图，可输入修改描述";
                    Toast.makeText(act, tip, Toast.LENGTH_SHORT).show();
                    act.clearOverlays();
                    act.switchTab(0);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "无法加载参考图。" : e.getMessage();
                uiHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            }
        }, "jianyu-remote-ref").start();
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("prompt", text));
            Toast.makeText(this, "已复制提示词", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== AirPods 风格更新弹窗 ====================

    private void checkUpdate(boolean silent) {
        if (checkingUpdate) {
            if (!silent) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
            return;
        }
        checkingUpdate = true;
        if (!silent) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                UpdateManager.UpdateInfo info = UpdateManager.checkForUpdate();
                uiHandler.post(() -> {
                    checkingUpdate = false;
                    availableUpdate = info.hasUpdate ? info : null;
                    MainActivity activity = visible;
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    activity.refreshHomeDynamic();
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
                uiHandler.post(() -> {
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
        AlertDialog dialog = createBottomSheetDialog(wrapper);

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
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                File apk = UpdateManager.downloadApk(appContext, info, new UpdateManager.ProgressListener() {
                    @Override public void onProgress(int percent) {
                        uiHandler.post(() -> {
                            bar.setIndeterminate(percent < 0);
                            if (percent >= 0) bar.setProgress(percent);
                            progPct.setText(percent < 0 ? "下载中" : percent + "%");
                        });
                    }
                    @Override public void onChannelChanged(String channel) {
                        uiHandler.post(() -> progLabel.setText("正在使用" + channel + "下载更新…"));
                    }
                });
                uiHandler.post(() -> {
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
                uiHandler.post(() -> {
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
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(164), dp(46));
        blp.topMargin = dp(16);
        card.addView(btn, blp);
        return card;
    }

    private void pickReference() {
        Intent picker;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
        } else {
            picker = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            picker.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        }
        try {
            startActivityForResult(picker, PICK_REFERENCE);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("image/*");
                startActivityForResult(fallback, PICK_REFERENCE);
            } catch (Exception ignored) { }
        }
    }

    private void generate() {
        String wireModel = activeWireModelName();
        if (!service.isLineConfigured(wireModel)) {
            String lineName = selectedRoute == 0 ? "线路 1" : "线路 2";
            new AlertDialog.Builder(this).setTitle(lineName + " 尚未配置密钥")
                    .setMessage("当前" + lineName + "尚未配置生图密钥，可以切换另一条线路。")
                    .setPositiveButton("我知道了", null).show();
            return;
        }
        if (promptInput != null) draft = promptInput.getText().toString();
        String input = draft.trim();
        if (input.isEmpty()) {
            if (promptInput != null) {
                promptInput.setError("请输入画面描述");
                if (heroCardView != null) MotionKit.shake(heroCardView);
            }
            return;
        }
        GenerationRecord record = new GenerationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(),
                selectedRoute, routes[selectedRoute], activeModelDisplayName(), wireModel, activeRatioName(), input,
                reference != null, reference != null ? reference.toString() : "", isGrok() && nsfw,
                GenerationRecord.STATUS_RUNNING, "", "", 0L);
        if (!GenerationTasks.start(this, record, MainActivity::finished)) return;
        reference = null;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_NOTIFICATION_REQUESTED, false)) {
                prefs.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            }
        }
        draft = "";
        if (promptInput != null) promptInput.setText("");
        startGenTimer();
        refreshHomeDynamic();
        refreshLibraryContent();
        updateFloatingBarsVisibility();
        Toast.makeText(this, "已创建独立任务，可以继续输入下一条提示词", Toast.LENGTH_SHORT).show();
    }

    private void startGenTimer() {
        uiHandler.removeCallbacks(genTimerTick);
        uiHandler.post(genTimerTick);
    }

    static void finished(String recordId, String id, String error, boolean wasCancelled) {
        MainActivity activity = visible;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean wasWatching = activity.isOverlayOpen("loading") && recordId.equals(activity.loadingRecordId);
        if (wasWatching) {
            activity.popOverlaysByTag("loading");
            activity.loadingTimerView = null;
            activity.loadingStatusView = null;
        }
        if (GenerationTasks.count() == 0) activity.uiHandler.removeCallbacks(activity.genTimerTick);
        activity.refreshHomeDynamic();
        activity.refreshLibraryContent();
        activity.updateFloatingBarsVisibility();
        if (wasCancelled) return;
        if (id != null) {
            if (wasWatching) activity.openDetailOverlay(id);
            else Toast.makeText(activity, "一条任务已完成，作品已保存", Toast.LENGTH_SHORT).show();
        } else if (error != null) {
            if (wasWatching) new AlertDialog.Builder(activity).setMessage(error).setPositiveButton("知道了", null).show();
            else Toast.makeText(activity, GenerationTasks.canResume(activity, recordId) ? "一条任务已暂停等待，可在生成记录中继续获取" : "一条任务需要查看，请打开生成记录", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 工具方法 ====================

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
        if (name == null || name.isEmpty()) return;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("flare")) {
            selectedRoute = 1;
            modelLine2 = 1;
        } else if (lower.contains("sunburst")) {
            selectedRoute = 1;
            modelLine2 = 2;
        } else if (lower.equals("gpt-2") || lower.equals("gpt-image-2")) {
            selectedRoute = 1;
            modelLine2 = 3;
        } else if (lower.contains("2.5") || lower.contains("gpt-image-2.5")) {
            selectedRoute = 1;
            modelLine2 = 0;
        } else if (lower.contains("grok") || "image-2".equals(lower)) {
            selectedRoute = 0;
            modelLine1 = 1;
        } else if ("image-1".equals(lower) || "gpt".equals(lower)) {
            selectedRoute = 0;
            modelLine1 = 0;
        } else {
            for (int i = 0; i < modelsLine2.length; i++) {
                if (modelsLine2[i].equalsIgnoreCase(name) || wireModelsLine2[i].equalsIgnoreCase(name)) {
                    selectedRoute = 1;
                    modelLine2 = i;
                    prefs.edit().putInt("selected_route", selectedRoute).putInt("model_line2", modelLine2).apply();
                    return;
                }
            }
            for (int i = 0; i < modelsLine1.length; i++) {
                if (modelsLine1[i].equalsIgnoreCase(name) || wireModelsLine1[i].equalsIgnoreCase(name)) {
                    selectedRoute = 0;
                    modelLine1 = i;
                    prefs.edit().putInt("selected_route", selectedRoute).putInt("model_line1", modelLine1).apply();
                    return;
                }
            }
            return;
        }
        prefs.edit().putInt("selected_route", selectedRoute)
                .putInt("model_line1", modelLine1)
                .putInt("model_line2", modelLine2).apply();
    }

    private void setRatioByName(String name) {
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(name)) { ratio = i; return; }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return Math.max(1L, bytes / 1024L) + " KB";
    }

    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        if (code == PICK_REFERENCE) {
            Uri picked = intent.getData();
            try {
                getContentResolver().takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            reference = picked;
            refreshHomeDynamic();
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
            Context appContext = getApplicationContext();
            new Thread(() -> {
                try {
                    File cached = InspirationRepository.ensureCachedImage(appContext, url);
                    try (InputStream in = new FileInputStream(cached);
                         OutputStream out = getContentResolver().openOutputStream(dest)) {
                        if (out == null) throw new IOException();
                        ImageService.copy(in, out, ImageService.IMAGE_LIMIT, "作品文件过大。");
                    }
                    uiHandler.post(() -> Toast.makeText(this, "原图已保存", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    uiHandler.post(() -> Toast.makeText(this, "保存失败，请稍后重试。", Toast.LENGTH_LONG).show());
                }
            }, "jianyu-save-remote").start();
        }
    }

    @Override public void onBackPressed() {
        if (!overlayStack.isEmpty()) {
            popOverlay();
        } else if (activeTab == 3 && librarySelectMode) {
            exitLibrarySelectMode();
        } else if (activeTab != 0) {
            switchTab(0);
        } else {
            super.onBackPressed();
        }
    }
}
