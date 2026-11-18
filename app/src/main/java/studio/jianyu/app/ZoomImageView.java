package studio.jianyu.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

final class ZoomImageView extends ImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 4.5f;

    private final Matrix drawMatrix = new Matrix();
    private final float[] values = new float[9];
    private final PointF lastTouch = new PointF();
    private float baseScale = 1f;
    private float currentZoom = 1f;
    private boolean dragging = false;
    private Runnable onSingleTap;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float targetZoom = clamp(currentZoom * factor, MIN_SCALE, MAX_SCALE);
                float delta = targetZoom / currentZoom;
                currentZoom = targetZoom;
                drawMatrix.postScale(delta, delta, detector.getFocusX(), detector.getFocusY());
                fixBounds();
                setImageMatrix(drawMatrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentZoom > 1.2f) {
                    fitToScreen();
                } else {
                    float target = 2.6f;
                    float delta = target / currentZoom;
                    currentZoom = target;
                    drawMatrix.postScale(delta, delta, e.getX(), e.getY());
                    fixBounds();
                    setImageMatrix(drawMatrix);
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onSingleTap != null) onSingleTap.run();
                return true;
            }
        });
    }

    void setOnSingleTap(Runnable action) {
        this.onSingleTap = action;
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToScreen);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToScreen();
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null || getWidth() <= 0 || getHeight() <= 0) return;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float vw = getWidth();
        float vh = getHeight();
        baseScale = Math.min(vw / dw, vh / dh);
        currentZoom = 1f;
        drawMatrix.reset();
        drawMatrix.postScale(baseScale, baseScale);
        drawMatrix.postTranslate((vw - dw * baseScale) * 0.5f, (vh - dh * baseScale) * 0.5f);
        setImageMatrix(drawMatrix);
    }

    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        drawMatrix.mapRect(rect);
        float vw = getWidth();
        float vh = getHeight();
        float dx = 0f, dy = 0f;
        if (rect.width() <= vw) dx = (vw - rect.width()) * 0.5f - rect.left;
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < vw) dx = vw - rect.right;

        if (rect.height() <= vh) dy = (vh - rect.height()) * 0.5f - rect.top;
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < vh) dy = vh - rect.bottom;
        drawMatrix.postTranslate(dx, dy);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            dragging = false;
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                dragging = currentZoom > 1.02f;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && currentZoom > 1.02f) {
                    float dx = event.getX() - lastTouch.x;
                    float dy = event.getY() - lastTouch.y;
                    lastTouch.set(event.getX(), event.getY());
                    drawMatrix.postTranslate(dx, dy);
                    fixBounds();
                    setImageMatrix(drawMatrix);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
