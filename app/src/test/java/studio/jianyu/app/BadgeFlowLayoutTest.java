package studio.jianyu.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "mdpi")
public class BadgeFlowLayoutTest {
    @Test public void oversizedBadgeIsConstrainedAndVisibleBadgesNeverOverlap() {
        Context context = RuntimeEnvironment.getApplication();
        for (int width : new int[]{320, 360, 412}) {
            BadgeFlowLayout flow = new BadgeFlowLayout(context);
            flow.setPadding(8, 8, 8, 8);
            View longBadge = badge(context, 700, 24);
            flow.addView(longBadge);
            flow.addView(badge(context, 64, 24));
            flow.addView(badge(context, 48, 24));
            measure(flow, width);
            assertTrue(longBadge.getWidth() <= width - 16);
            assertChildrenFit(flow);
        }
    }

    @Test public void hidingBadgeReclaimsRowAndReflowWorksWhenWidthChanges() {
        Context context = RuntimeEnvironment.getApplication();
        BadgeFlowLayout flow = new BadgeFlowLayout(context);
        View first = badge(context, 100, 24);
        View hidden = badge(context, 100, 24);
        View last = badge(context, 100, 24);
        flow.addView(first);
        flow.addView(hidden);
        flow.addView(last);
        measure(flow, 220);
        assertTrue(last.getTop() > first.getBottom());
        int withReferenceHeight = flow.getHeight();
        hidden.setVisibility(View.GONE);
        measure(flow, 220);
        assertEquals(first.getTop(), last.getTop());
        assertTrue(flow.getHeight() < withReferenceHeight);
        assertChildrenFit(flow);
        measure(flow, 150);
        assertTrue(last.getTop() > first.getBottom());
        assertChildrenFit(flow);
    }

    @Test public void marginsAndRtlStillKeepBadgesInsideBoundsWithoutOverlap() {
        Context application = RuntimeEnvironment.getApplication();
        ApplicationInfo rtlApplication = new ApplicationInfo(application.getApplicationInfo());
        rtlApplication.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        rtlApplication.targetSdkVersion = 28;
        Context context = new ContextWrapper(application) {
            @Override public ApplicationInfo getApplicationInfo() { return rtlApplication; }
        };
        BadgeFlowLayout flow = new BadgeFlowLayout(context);
        flow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        flow.setPadding(7, 9, 11, 13);
        for (int index = 0; index < 4; index++) {
            View badge = badge(context, 80, 24);
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(80, 24);
            params.setMargins(3, 5, 7, 9);
            flow.addView(badge, params);
        }
        measure(flow, 220);
        assertEquals(View.LAYOUT_DIRECTION_RTL, flow.getLayoutDirection());
        assertChildrenFit(flow);
        assertTrue(flow.getChildAt(0).getLeft() > flow.getChildAt(1).getLeft());
        assertTrue(flow.getChildAt(2).getTop() > flow.getChildAt(0).getBottom());
    }

    private View badge(Context context, int desiredWidth, int height) {
        return new View(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(resolveSize(desiredWidth, widthSpec), resolveSize(height, heightSpec));
            }
        };
    }

    private void measure(BadgeFlowLayout flow, int width) {
        flow.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        flow.layout(0, 0, flow.getMeasuredWidth(), flow.getMeasuredHeight());
    }

    private void assertChildrenFit(BadgeFlowLayout flow) {
        for (int index = 0; index < flow.getChildCount(); index++) {
            View child = flow.getChildAt(index);
            if (child.getVisibility() == View.GONE) continue;
            Rect bounds = new Rect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            assertTrue(child.getLeft() >= flow.getPaddingLeft());
            assertTrue(child.getRight() <= flow.getWidth() - flow.getPaddingRight());
            assertTrue(child.getTop() >= flow.getPaddingTop());
            assertTrue(child.getBottom() <= flow.getHeight() - flow.getPaddingBottom());
            for (int next = index + 1; next < flow.getChildCount(); next++) {
                View sibling = flow.getChildAt(next);
                if (sibling.getVisibility() != View.GONE) assertFalse(Rect.intersects(bounds,
                        new Rect(sibling.getLeft(), sibling.getTop(), sibling.getRight(), sibling.getBottom())));
            }
        }
    }
}
