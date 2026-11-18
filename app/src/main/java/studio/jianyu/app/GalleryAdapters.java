package studio.jianyu.app;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import java.io.File;
import java.util.*;

final class GalleryAdapters {

    interface InspirationListener {
        void onItemClick(InspirationRepository.Item item);
        void onQuickSameClick(InspirationRepository.Item item);
        void onFavoriteToggle(InspirationRepository.Item item);
    }

    interface LocalArtworkListener {
        void onArtworkClick(String id);
        void onArtworkLongClick(String id);
    }

    /** 创意广场 / 提示词画廊 / 收藏 通用双列瀑布流 RecyclerView 适配器 */
    static final class InspirationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_FOOTER = 1;

        private final Context context;
        private final InspirationListener listener;
        private final List<InspirationRepository.Item> items = new ArrayList<>();
        private final Set<String> favKeys = new HashSet<>();
        private final Set<String> revealedKeys = new HashSet<>();
        private boolean safeBrowse = true;
        private String footerText = "";
        private final int columnWidthPx;

        InspirationAdapter(Context context, InspirationListener listener) {
            this.context = context;
            this.listener = listener;
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            this.columnWidthPx = Math.max(UiKit.dp(context, 130), (screenW - UiKit.dp(context, 42)) / 2);
        }

        void setData(List<InspirationRepository.Item> list, Set<String> favoriteKeys, boolean safeBrowseMode, String footer) {
            items.clear();
            if (list != null) items.addAll(list);
            favKeys.clear();
            if (favoriteKeys != null) favKeys.addAll(favoriteKeys);
            this.safeBrowse = safeBrowseMode;
            this.footerText = footer == null ? "" : footer;
            notifyDataSetChanged();
        }

        void updateFavoritesAndSafeBrowse(Set<String> favoriteKeys, boolean safeBrowseMode) {
            favKeys.clear();
            if (favoriteKeys != null) favKeys.addAll(favoriteKeys);
            if (!safeBrowseMode) revealedKeys.clear();
            this.safeBrowse = safeBrowseMode;
            notifyDataSetChanged();
        }

        void setFooterText(String footer) {
            String next = footer == null ? "" : footer;
            if (next.equals(footerText)) return;
            footerText = next;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() {
            return items.size() + (footerText.isEmpty() ? 0 : 1);
        }

        @Override public int getItemViewType(int position) {
            return position < items.size() ? TYPE_ITEM : TYPE_FOOTER;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == TYPE_FOOTER) {
                TextView tv = UiKit.text(context, "", 12, UiKit.MUTED, false);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(0, UiKit.dp(context, 14), 0, UiKit.dp(context, 18));
                StaggeredGridLayoutManager.LayoutParams lp = new StaggeredGridLayoutManager.LayoutParams(-1, -2);
                lp.setFullSpan(true);
                tv.setLayoutParams(lp);
                return new FooterHolder(tv);
            }
            return new ItemHolder(context);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof FooterHolder) {
                ((FooterHolder) holder).text.setText(footerText);
                return;
            }
            ItemHolder h = (ItemHolder) holder;
            InspirationRepository.Item item = items.get(position);
            int imgH = Math.round(columnWidthPx * item.aspectHeightOverWidth());
            ViewGroup.LayoutParams ilp = h.image.getLayoutParams();
            if (ilp.height != imgH) {
                ilp.height = imgH;
                h.image.setLayoutParams(ilp);
            }
            ViewGroup.LayoutParams clp = h.safeCover.getLayoutParams();
            if (clp.height != imgH) {
                clp.height = imgH;
                h.safeCover.setLayoutParams(clp);
            }

            boolean hiddenBySafe = safeBrowse && item.nsfw && !revealedKeys.contains(item.uniqueKey());
            if (hiddenBySafe) {
                h.image.setTag(null);
                h.image.setImageDrawable(null);
                h.safeCover.setVisibility(View.VISIBLE);
                h.safeCover.setOnClickListener(v -> {
                    revealedKeys.add(item.uniqueKey());
                    int idx = holder.getBindingAdapterPosition();
                    if (idx != RecyclerView.NO_POSITION) notifyItemChanged(idx);
                });
            } else {
                h.safeCover.setVisibility(View.GONE);
                InspirationRepository.loadImage(context, h.image, item.thumbUrl, 520);
            }

            boolean isFav = favKeys.contains(item.uniqueKey());
            h.favIcon.setImageResource(isFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            h.favIcon.setColorFilter(isFav ? 0xFFE5484D : Color.WHITE, PorterDuff.Mode.SRC_IN);
            h.favBtn.setOnClickListener(v -> listener.onFavoriteToggle(item));

            boolean isPlaza = "plaza".equals(item.source);
            if (isPlaza) {
                h.galleryScrim.setVisibility(View.GONE);
                h.plazaBar.setVisibility(View.VISIBLE);
                if (!item.model.isEmpty()) {
                    h.topBadge.setVisibility(View.VISIBLE);
                    h.topBadge.setText(item.model + " · " + item.ratio);
                } else {
                    h.topBadge.setVisibility(View.GONE);
                }
                h.author.setText(item.author.isEmpty() ? "广场作品" : item.author);
                h.quickSame.setOnClickListener(v -> listener.onQuickSameClick(item));
            } else {
                h.plazaBar.setVisibility(View.GONE);
                h.galleryScrim.setVisibility(View.VISIBLE);
                h.topBadge.setVisibility(View.GONE);
                h.galleryTitle.setText(item.title.isEmpty() ? item.prompt : item.title);
                String cat = !item.category.isEmpty() ? item.category : item.major;
                h.gallerySub.setVisibility(cat.isEmpty() ? View.GONE : View.VISIBLE);
                h.gallerySub.setText(cat);
            }

            h.card.setOnClickListener(v -> {
                if (hiddenBySafe) {
                    revealedKeys.add(item.uniqueKey());
                    int idx = holder.getBindingAdapterPosition();
                    if (idx != RecyclerView.NO_POSITION) notifyItemChanged(idx);
                } else {
                    listener.onItemClick(item);
                }
            });
        }

        static final class FooterHolder extends RecyclerView.ViewHolder {
            final TextView text;
            FooterHolder(TextView view) { super(view); this.text = view; }
        }

        static final class ItemHolder extends RecyclerView.ViewHolder {
            final FrameLayout card;
            final ImageView image;
            final LinearLayout safeCover;
            final TextView topBadge;
            final FrameLayout favBtn;
            final ImageView favIcon;
            final LinearLayout plazaBar;
            final TextView author;
            final TextView quickSame;
            final LinearLayout galleryScrim;
            final TextView galleryTitle;
            final TextView gallerySub;

            ItemHolder(Context ctx) {
                super(new FrameLayout(ctx));
                card = (FrameLayout) itemView;
                StaggeredGridLayoutManager.LayoutParams lp = new StaggeredGridLayoutManager.LayoutParams(-1, -2);
                int m = UiKit.dp(ctx, 5);
                lp.setMargins(m, 0, m, UiKit.dp(ctx, 10));
                card.setLayoutParams(lp);
                card.setBackground(UiKit.round(ctx, UiKit.SOFT, 20));
                card.setClipToOutline(true);
                UiKit.applySoftShadow(card, ctx, 5, 0x24182638);
                card.setFocusable(true);

                image = new ImageView(ctx);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new FrameLayout.LayoutParams(-1, UiKit.dp(ctx, 180)));

                // 安全浏览敏感内容遮罩层
                safeCover = new LinearLayout(ctx);
                safeCover.setOrientation(LinearLayout.VERTICAL);
                safeCover.setGravity(Gravity.CENTER);
                safeCover.setBackgroundColor(0xF0EDF2FA);
                safeCover.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12));
                safeCover.addView(UiKit.icon(ctx, R.drawable.ic_shield, 22, UiKit.MUTED));
                TextView safeTxt = UiKit.text(ctx, "敏感预览已折叠", 12, UiKit.INK, true);
                LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, -2);
                stlp.topMargin = UiKit.dp(ctx, 6);
                safeCover.addView(safeTxt, stlp);
                TextView safeHint = UiKit.text(ctx, "轻触查看", 11, UiKit.BLUE, true);
                LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(-2, -2);
                shlp.topMargin = UiKit.dp(ctx, 4);
                safeCover.addView(safeHint, shlp);
                safeCover.setVisibility(View.GONE);
                card.addView(safeCover, new FrameLayout.LayoutParams(-1, UiKit.dp(ctx, 180)));

                topBadge = UiKit.frostedBadge(ctx, "");
                FrameLayout.LayoutParams tblp = new FrameLayout.LayoutParams(-2, -2);
                tblp.gravity = Gravity.TOP | Gravity.START;
                tblp.leftMargin = UiKit.dp(ctx, 8);
                tblp.topMargin = UiKit.dp(ctx, 8);
                card.addView(topBadge, tblp);

                // 右上角收藏心形（40dp 点击热区）
                favBtn = new FrameLayout(ctx);
                FrameLayout.LayoutParams fblp = new FrameLayout.LayoutParams(UiKit.dp(ctx, 40), UiKit.dp(ctx, 40));
                fblp.gravity = Gravity.TOP | Gravity.END;
                LinearLayout favBg = new LinearLayout(ctx);
                favBg.setGravity(Gravity.CENTER);
                favBg.setBackground(UiKit.darkFrosted(ctx, 15));
                favIcon = UiKit.icon(ctx, R.drawable.ic_heart, 14, Color.WHITE);
                favBg.addView(favIcon);
                FrameLayout.LayoutParams bglp = new FrameLayout.LayoutParams(UiKit.dp(ctx, 30), UiKit.dp(ctx, 30));
                bglp.gravity = Gravity.CENTER;
                favBtn.addView(favBg, bglp);
                card.addView(favBtn, fblp);

                // 创意广场底部磨砂浮条 + 同款按钮
                plazaBar = new LinearLayout(ctx);
                plazaBar.setOrientation(LinearLayout.HORIZONTAL);
                plazaBar.setGravity(Gravity.CENTER_VERTICAL);
                plazaBar.setBackground(UiKit.darkFrosted(ctx, 15));
                plazaBar.setPadding(UiKit.dp(ctx, 9), UiKit.dp(ctx, 5), UiKit.dp(ctx, 5), UiKit.dp(ctx, 5));

                author = UiKit.text(ctx, "", 11, Color.WHITE, false);
                author.setMaxLines(1);
                author.setEllipsize(TextUtils.TruncateAt.END);
                plazaBar.addView(author, new LinearLayout.LayoutParams(0, -2, 1));

                quickSame = UiKit.text(ctx, "同款", 11, UiKit.BLUE, true);
                quickSame.setBackground(UiKit.round(ctx, 0xF5FFFFFF, 11));
                quickSame.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 5), UiKit.dp(ctx, 10), UiKit.dp(ctx, 5));
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
                qlp.leftMargin = UiKit.dp(ctx, 6);
                plazaBar.addView(quickSame, qlp);

                FrameLayout.LayoutParams pblp = new FrameLayout.LayoutParams(-1, -2);
                pblp.gravity = Gravity.BOTTOM;
                pblp.leftMargin = UiKit.dp(ctx, 7);
                pblp.rightMargin = UiKit.dp(ctx, 7);
                pblp.bottomMargin = UiKit.dp(ctx, 7);
                card.addView(plazaBar, pblp);

                // 提示词画廊底部渐变标题层
                galleryScrim = new LinearLayout(ctx);
                galleryScrim.setOrientation(LinearLayout.VERTICAL);
                galleryScrim.setBackground(UiKit.scrimBottom(20, ctx));
                galleryScrim.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 26), UiKit.dp(ctx, 10), UiKit.dp(ctx, 10));

                galleryTitle = UiKit.text(ctx, "", 12.5f, Color.WHITE, true);
                galleryTitle.setMaxLines(1);
                galleryTitle.setEllipsize(TextUtils.TruncateAt.END);
                galleryScrim.addView(galleryTitle);

                gallerySub = UiKit.text(ctx, "", 10.5f, 0xD9FFFFFF, false);
                gallerySub.setMaxLines(1);
                gallerySub.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams gslp = new LinearLayout.LayoutParams(-1, -2);
                gslp.topMargin = UiKit.dp(ctx, 2);
                galleryScrim.addView(gallerySub, gslp);

                FrameLayout.LayoutParams sclp = new FrameLayout.LayoutParams(-1, -2);
                sclp.gravity = Gravity.BOTTOM;
                card.addView(galleryScrim, sclp);
            }
        }
    }

    /** 本机作品库双列瀑布流 + 长按/多选批量删除适配器 */
    static final class LocalLibraryAdapter extends RecyclerView.Adapter<LocalLibraryAdapter.Holder> {
        private static final Map<String, Float> RATIO_CACHE = new HashMap<>();

        private final Context context;
        private final ImageService service;
        private final LocalArtworkListener listener;
        private final File imageDir;
        private final List<String> ids = new ArrayList<>();
        private final Set<String> selected = new HashSet<>();
        private boolean selectMode = false;
        private final int columnWidthPx;

        LocalLibraryAdapter(Context context, ImageService service, LocalArtworkListener listener) {
            this.context = context;
            this.service = service;
            this.listener = listener;
            this.imageDir = new File(context.getFilesDir(), "images");
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            this.columnWidthPx = Math.max(UiKit.dp(context, 130), (screenW - UiKit.dp(context, 42)) / 2);
        }

        void setData(List<String> list, boolean inSelectMode, Set<String> selectedIds) {
            ids.clear();
            if (list != null) ids.addAll(list);
            selectMode = inSelectMode;
            selected.clear();
            if (selectedIds != null) selected.addAll(selectedIds);
            notifyDataSetChanged();
        }

        private float resolveAspect(String id, File file) {
            Float cached = RATIO_CACHE.get(id);
            if (cached != null) return cached;
            float ratio = 1.0f;
            String rLabel = service.ratioLabel(id);
            if ("3:4".equals(rLabel)) ratio = 1.32f;
            else if ("4:3".equals(rLabel)) ratio = 0.78f;
            else if ("9:16".equals(rLabel)) ratio = 1.44f;
            else if ("16:9".equals(rLabel)) ratio = 0.68f;
            else if (file.isFile()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), o);
                if (o.outWidth > 0 && o.outHeight > 0) {
                    ratio = Math.max(0.68f, Math.min(1.46f, (float) o.outHeight / (float) o.outWidth));
                }
            }
            RATIO_CACHE.put(id, ratio);
            return ratio;
        }

        @Override public int getItemCount() { return ids.size(); }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(context);
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            String id = ids.get(position);
            File file = new File(imageDir, id);
            int imgH = Math.round(columnWidthPx * resolveAspect(id, file));
            ViewGroup.LayoutParams ilp = h.image.getLayoutParams();
            if (ilp.height != imgH) {
                ilp.height = imgH;
                h.image.setLayoutParams(ilp);
            }
            InspirationRepository.loadLocalThumb(h.image, file, 520);

            String m = service.modelLabel(id);
            String r = service.ratioLabel(id);
            String badge = !m.isEmpty() && !r.isEmpty() ? m + " · " + r : (!m.isEmpty() ? m : r);
            h.badge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
            h.badge.setText(badge);

            boolean isSelected = selectMode && selected.contains(id);
            h.checkWrap.setVisibility(selectMode ? View.VISIBLE : View.GONE);
            h.checkWrap.setBackground(isSelected
                    ? UiKit.round(context, UiKit.BLUE, 14)
                    : UiKit.card(context, 0x88141E2E, 0xCCFFFFFF, 14));
            h.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            h.selectedVeil.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            h.card.setOnClickListener(v -> listener.onArtworkClick(id));
            h.card.setOnLongClickListener(v -> {
                listener.onArtworkLongClick(id);
                return true;
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout card;
            final ImageView image;
            final View selectedVeil;
            final TextView badge;
            final LinearLayout checkWrap;
            final ImageView checkIcon;

            Holder(Context ctx) {
                super(new FrameLayout(ctx));
                card = (FrameLayout) itemView;
                StaggeredGridLayoutManager.LayoutParams lp = new StaggeredGridLayoutManager.LayoutParams(-1, -2);
                int m = UiKit.dp(ctx, 5);
                lp.setMargins(m, 0, m, UiKit.dp(ctx, 10));
                card.setLayoutParams(lp);
                card.setBackground(UiKit.round(ctx, UiKit.SOFT, 20));
                card.setClipToOutline(true);
                UiKit.applySoftShadow(card, ctx, 5, 0x24182638);
                card.setFocusable(true);

                image = new ImageView(ctx);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new FrameLayout.LayoutParams(-1, UiKit.dp(ctx, 180)));

                selectedVeil = new View(ctx);
                selectedVeil.setBackground(UiKit.card(ctx, 0x242F5CE0, UiKit.BLUE, 20));
                selectedVeil.setVisibility(View.GONE);
                card.addView(selectedVeil, new FrameLayout.LayoutParams(-1, -1));

                badge = UiKit.frostedBadge(ctx, "");
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
                blp.gravity = Gravity.BOTTOM | Gravity.START;
                blp.leftMargin = UiKit.dp(ctx, 9);
                blp.bottomMargin = UiKit.dp(ctx, 9);
                card.addView(badge, blp);

                checkWrap = new LinearLayout(ctx);
                checkWrap.setGravity(Gravity.CENTER);
                checkIcon = UiKit.icon(ctx, R.drawable.ic_check, 15, Color.WHITE);
                checkWrap.addView(checkIcon);
                checkWrap.setVisibility(View.GONE);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(UiKit.dp(ctx, 28), UiKit.dp(ctx, 28));
                clp.gravity = Gravity.TOP | Gravity.END;
                clp.rightMargin = UiKit.dp(ctx, 9);
                clp.topMargin = UiKit.dp(ctx, 9);
                card.addView(checkWrap, clp);
            }
        }
    }
}
