package studio.jianyu.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

/**
 * 苹果液态玻璃风格全局动效引擎：
 * 统一管理按钮弹性按压、图标微动效、Tab 切换、二级页转场、底栏滑入滑出、
 * 图片异步淡入以及表单轻晃反馈，全部基于 Android 硬件加速属性动画（API 26+）。
 */
final class MotionKit {
    static final Interpolator APPLE_EASE_OUT = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    static final Interpolator APPLE_SPRING = new PathInterpolator(0.34f, 1.42f, 0.64f, 1.0f);
    static final Interpolator EXIT_EASE = new DecelerateInterpolator(1.6f);
    static final Interpolator OVERSHOOT = new OvershootInterpolator(2.1f);

    /** 为按钮/胶囊/图标/卡片挂载苹果式按压微缩 + 弹性回弹 + 轻触觉反馈 */
    static void attachPressSpring(View view, float pressedScale) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .alpha(0.90f)
                            .setDuration(95)
                            .setInterpolator(APPLE_EASE_OUT)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(240)
                            .setInterpolator(APPLE_SPRING)
                            .start();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(180)
                            .setInterpolator(APPLE_EASE_OUT)
                            .start();
                    break;
            }
            return false;
        });
    }

    static void attachPressSpring(View view) {
        attachPressSpring(view, 0.95f);
    }

    /** 图标轻跃弹跳（Tab 激活 / 复制 / 参数切换 / 勾选） */
    static void popIcon(View icon) {
        if (icon == null) return;
        icon.animate().cancel();
        icon.setScaleX(0.76f);
        icon.setScaleY(0.76f);
        icon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(OVERSHOOT)
                .start();
    }

    /** 收藏心形双拍弹性心跳动效 */
    static void heartBeat(View icon) {
        if (icon == null) return;
        icon.animate().cancel();
        icon.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        icon.animate()
                .scaleX(1.34f)
                .scaleY(1.34f)
                .setDuration(120)
                .setInterpolator(APPLE_EASE_OUT)
                .withEndAction(() -> icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(OVERSHOOT)
                        .start())
                .start();
    }

    /** 齿轮/刷新图标顺滑旋转微动效 */
    static void spinIcon(View icon, float degrees) {
        if (icon == null) return;
        icon.animate().cancel();
        icon.animate()
                .rotationBy(degrees)
                .setDuration(degrees >= 180f ? 420 : 260)
                .setInterpolator(APPLE_EASE_OUT)
                .start();
    }

    /** Tab 切换柔和上浮淡入 */
    static void animateTabEnter(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(UiKit.dp(view.getContext(), 10));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(APPLE_EASE_OUT)
                .start();
    }

    /** 卡片/横幅/贴纸动态出现动画 */
    static void revealCard(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.setTranslationY(UiKit.dp(view.getContext(), 8));
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(APPLE_EASE_OUT)
                .start();
    }

    /** 二级页面（详情 / 灵感详情 / 光球 / 全屏缩放）推入动画 */
    static void animateOverlayPush(String tag, View view) {
        if (view == null) return;
        view.animate().cancel();
        if ("zoom".equals(tag)) {
            view.setAlpha(0f);
            view.setScaleX(0.92f);
            view.setScaleY(0.92f);
            view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(APPLE_EASE_OUT)
                    .start();
        } else {
            view.setAlpha(0f);
            view.setTranslationX(UiKit.dp(view.getContext(), 34));
            view.setScaleX(0.985f);
            view.setScaleY(0.985f);
            view.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(APPLE_EASE_OUT)
                    .start();
        }
    }

    /** 二级页面退出动画（结束后自动执行回调移除视图） */
    static void animateOverlayPop(String tag, View view, Runnable onEnd) {
        if (view == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        view.animate().cancel();
        if ("zoom".equals(tag)) {
            view.animate()
                    .alpha(0f)
                    .scaleX(0.93f)
                    .scaleY(0.93f)
                    .setDuration(180)
                    .setInterpolator(EXIT_EASE)
                    .setListener(endOnce(onEnd))
                    .start();
        } else {
            view.animate()
                    .alpha(0f)
                    .translationX(UiKit.dp(view.getContext(), 36))
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(200)
                    .setInterpolator(EXIT_EASE)
                    .setListener(endOnce(onEnd))
                    .start();
        }
    }

    /** 底部悬浮药丸/微胶囊/批量栏平滑滑入滑出 */
    static void animateFloatingBar(View bar, boolean show, float hiddenOffsetDp) {
        if (bar == null) return;
        float offsetPx = UiKit.dp(bar.getContext(), hiddenOffsetDp);
        Object currentState = bar.getTag(R.id.tag_floating_bar_visible);
        boolean wasShown = Boolean.TRUE.equals(currentState);
        if (show) {
            if (wasShown && bar.getVisibility() == View.VISIBLE && bar.getAlpha() > 0.99f && bar.getTranslationY() == 0f) {
                return;
            }
            bar.setTag(R.id.tag_floating_bar_visible, Boolean.TRUE);
            bar.animate().cancel();
            if (bar.getVisibility() != View.VISIBLE) {
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(0f);
                bar.setTranslationY(offsetPx);
            }
            bar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(APPLE_SPRING)
                    .setListener(null)
                    .start();
        } else {
            if (!wasShown && bar.getVisibility() == View.GONE) return;
            bar.setTag(R.id.tag_floating_bar_visible, Boolean.FALSE);
            bar.animate().cancel();
            bar.animate()
                    .alpha(0f)
                    .translationY(offsetPx)
                    .setDuration(170)
                    .setInterpolator(EXIT_EASE)
                    .setListener(endOnce(() -> {
                        if (!Boolean.TRUE.equals(bar.getTag(R.id.tag_floating_bar_visible))) {
                            bar.setVisibility(View.GONE);
                        }
                    }))
                    .start();
        }
    }

    /** 输入校验错误时的苹果式轻晃反馈 */
    static void shake(View view) {
        if (view == null) return;
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        float d = UiKit.dp(view.getContext(), 7);
        ObjectAnimator anim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -d, d, -d * 0.65f, d * 0.65f, -d * 0.3f, 0f);
        anim.setDuration(360);
        anim.start();
    }

    /** 图片异步加载完成后的柔和淡入（避免瀑布流硬闪） */
    static void fadeInBitmap(ImageView target, Bitmap bitmap) {
        if (target == null || bitmap == null) return;
        boolean alreadyHadImage = target.getDrawable() != null;
        target.setImageBitmap(bitmap);
        if (!alreadyHadImage) {
            target.animate().cancel();
            target.setAlpha(0f);
            target.animate()
                    .alpha(1f)
                    .setDuration(190)
                    .setInterpolator(APPLE_EASE_OUT)
                    .start();
        } else {
            target.setAlpha(1f);
        }
    }

    /** 敏感预览遮罩点击后丝滑消散 */
    static void fadeOutAndHide(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(200)
                .setInterpolator(APPLE_EASE_OUT)
                .setListener(endOnce(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }))
                .start();
    }

    private static Animator.AnimatorListener endOnce(Runnable action) {
        return new AnimatorListenerAdapter() {
            private boolean done = false;
            @Override public void onAnimationEnd(Animator animation) {
                if (!done) {
                    done = true;
                    if (action != null) action.run();
                }
            }
            @Override public void onAnimationCancel(Animator animation) {
                done = true;
            }
        };
    }
}
