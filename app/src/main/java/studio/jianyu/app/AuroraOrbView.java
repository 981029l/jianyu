package studio.jianyu.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class AuroraOrbView extends View {
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private final Path starPath = new Path();
    private ValueAnimator animator;
    private float phase = 0f;

    AuroraOrbView(Context context) {
        super(context);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(UiKit.dp(context, 1.5f));
        rimPaint.setColor(0xE8FFFFFF);
        starPaint.setStyle(Paint.Style.FILL);
        starPaint.setColor(0xDD2F5CE0);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 360f);
            animator.setDuration(6800L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = Math.min(w, h) * 0.34f;
        if (r <= 1f) return;

        haloPaint.setShader(new RadialGradient(
                cx, cy, r * 1.48f,
                new int[]{0x4A6AA6FF, 0x30C1A4FF, 0x20FFCCB3, 0x00FFFFFF},
                new float[]{0f, 0.48f, 0.78f, 1f},
                Shader.TileMode.CLAMP));

        orbBasePaint.setShader(new RadialGradient(
                cx - r * 0.25f, cy - r * 0.28f, r * 1.25f,
                new int[]{0xFCFFFFFF, 0xDCEAF3FF, 0xC6D2E0FF, 0xCCFFE3D4},
                new float[]{0f, 0.42f, 0.78f, 1f},
                Shader.TileMode.CLAMP));

        ribbonPaint.setShader(new SweepGradient(
                cx, cy,
                new int[]{0x584488FF, 0x52B58CFF, 0x4EFFB8A2, 0x5272D8FF, 0x584488FF},
                null));

        sheenPaint.setShader(new RadialGradient(
                cx - r * 0.32f, cy - r * 0.35f, r * 0.72f,
                new int[]{0xE8FFFFFF, 0x40FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float baseR = Math.min(getWidth(), getHeight()) * 0.34f;
        if (baseR <= 1f) return;

        double rad = Math.toRadians(phase * 2.0);
        float breath = 1f + 0.045f * (float) Math.sin(rad);
        float r = baseR * breath;

        // 1. 浅色虹彩外晕
        canvas.drawCircle(cx, cy, baseR * (1.38f + 0.08f * (float) Math.cos(rad)), haloPaint);

        // 2. 玻璃球基底 + 旋转极光丝带
        canvas.drawCircle(cx, cy, r, orbBasePaint);
        Shader shader = ribbonPaint.getShader();
        if (shader != null) {
            sweepMatrix.setRotate(phase, cx, cy);
            shader.setLocalMatrix(sweepMatrix);
        }
        canvas.drawCircle(cx, cy, r * 0.96f, ribbonPaint);

        // 3. 顶部镜面月牙高光与亮白玻璃边缘
        canvas.drawCircle(cx, cy, r * 0.92f, sheenPaint);
        canvas.drawCircle(cx, cy, r - rimPaint.getStrokeWidth() * 0.5f, rimPaint);

        // 4. 中心呼吸四芒星
        float s = baseR * (0.26f + 0.025f * (float) Math.sin(rad));
        float inner = s * 0.28f;
        starPath.reset();
        starPath.moveTo(cx, cy - s);
        starPath.quadTo(cx + inner * 0.25f, cy - inner * 0.25f, cx + s, cy);
        starPath.quadTo(cx + inner * 0.25f, cy + inner * 0.25f, cx, cy + s);
        starPath.quadTo(cx - inner * 0.25f, cy + inner * 0.25f, cx - s, cy);
        starPath.quadTo(cx - inner * 0.25f, cy - inner * 0.25f, cx, cy - s);
        starPath.close();
        canvas.drawPath(starPath, starPaint);
    }
}
