package studio.jianyu.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

final class BadgeFlowLayout extends ViewGroup {
    private final int gap;

    BadgeFlowLayout(Context context) {
        this(context, 6);
    }

    BadgeFlowLayout(Context context, int gapDp) {
        super(context);
        gap = UiKit.dp(context, gapDp);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int limit = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, limit - getPaddingLeft() - getPaddingRight());
        int rowWidth = 0, rowHeight = 0, widest = 0;
        int height = getPaddingTop() + getPaddingBottom();
        int childState = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int spacing = rowWidth > 0 ? gap : 0;
            if (rowWidth > 0 && rowWidth + spacing + width > available) {
                height += rowHeight + gap;
                rowWidth = rowHeight = spacing = 0;
            }
            rowWidth += spacing + width;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
            widest = Math.max(widest, rowWidth);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }
        setMeasuredDimension(resolveSizeAndState(widest + getPaddingLeft() + getPaddingRight(), widthMeasureSpec, childState),
                resolveSizeAndState(height + rowHeight, heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        int rowWidth = 0, rowHeight = 0, y = getPaddingTop();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int spacing = rowWidth > 0 ? gap : 0;
            if (rowWidth > 0 && rowWidth + spacing + width > available) {
                y += rowHeight + gap;
                rowWidth = rowHeight = spacing = 0;
            }
            int x = rtl ? right - left - getPaddingRight() - rowWidth - spacing - params.rightMargin - child.getMeasuredWidth()
                    : getPaddingLeft() + rowWidth + spacing + params.leftMargin;
            child.layout(x, y + params.topMargin, x + child.getMeasuredWidth(), y + params.topMargin + child.getMeasuredHeight());
            rowWidth += spacing + width;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override public LayoutParams generateLayoutParams(AttributeSet attributes) {
        return new MarginLayoutParams(getContext(), attributes);
    }

    @Override protected LayoutParams generateLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) params) : new MarginLayoutParams(params);
    }

    @Override protected boolean checkLayoutParams(LayoutParams params) { return params instanceof MarginLayoutParams; }
}
