package studio.jianyu.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordLayoutTest {
    @Test public void longModelAndDurationFitAcrossWidthsFontsAndReferenceStates() {
        for (int width : new int[]{320, 360, 412}) {
            for (float scale : new float[]{1f, 1.3f, 1.5f}) {
                for (boolean hasReference : new boolean[]{false, true}) {
                    for (int status : new int[]{GenerationRecord.STATUS_SUCCESS, GenerationRecord.STATUS_RUNNING,
                            GenerationRecord.STATUS_WAITING, GenerationRecord.STATUS_FAILED}) {
                        Context context = context(width, scale);
                        GalleryAdapters.RecordAdapter.Holder holder = holder(context);
                        holder.bind(record(status, hasReference));
                        measure(holder.card, width);
                        assertEquals(11f * scale * context.getResources().getDisplayMetrics().density,
                                holder.statusBadge.getTextSize(), 0.1f);
                        assertFits(holder.card);
                        assertReadable(holder.statusBadge);
                        assertReadable(holder.routeBadge);
                        assertReadable(holder.modelBadge);
                        assertReadable(holder.ratioBadge);
                        if (hasReference) assertReadable(holder.refBadge);
                        assertEquals(hasReference ? View.VISIBLE : View.GONE, holder.refBadge.getVisibility());
                        assertReadable(holder.durationText);
                        assertReadable(holder.primaryActionLabel);
                        assertReadable(holder.secondaryActionLabel);
                        assertEquals("GPT-2.5 Sunburst", holder.modelBadge.getText().toString());
                        assertTrue(holder.durationText.getText().toString().contains("1429.6"));
                        assertNotSame(holder.modelBadge.getParent(), holder.durationText.getParent());
                        assertSeparated(holder.card, holder.durationText, holder.modelBadge);
                        assertSeparated(holder.card, holder.timeLabel, holder.primaryActionBtn);
                        assertTrue(holder.primaryActionBtn.getHeight() >= UiKit.dp(context, 48));
                        assertTrue(holder.secondaryActionBtn.getHeight() >= UiKit.dp(context, 48));
                        assertTrue(holder.deleteBtn.getHeight() >= UiKit.dp(context, 48));
                        assertTrue(holder.deleteBtn.getWidth() >= UiKit.dp(context, 48));
                    }
                }
            }
        }
    }

    @Test public void downloadPayloadUpdatesOnlySeparateProgressRowAndRemainsReadable() {
        String download = "已下载 23.0 MB / 24.0 MB · 95%";
        for (int width : new int[]{320, 360, 412}) {
            for (float scale : new float[]{1f, 1.3f, 1.5f}) {
                Context context = context(width, scale);
                GalleryAdapters.RecordAdapter adapter = new GalleryAdapters.RecordAdapter(context,
                        mock(GalleryAdapters.RecordListener.class));
                GenerationRecord record = record(GenerationRecord.STATUS_DOWNLOADING, true);
                adapter.setData(Collections.singletonList(record));
                GalleryAdapters.RecordAdapter.Holder holder = holder(context);
                adapter.onBindViewHolder(holder, 0);
                adapter.onBindViewHolder(holder, 0, Collections.singletonList(download));
                measure(holder.card, width);
                assertEquals(download, holder.durationText.getText().toString());
                assertEquals("下载中", holder.statusBadge.getText().toString());
                assertEquals("GPT-2.5 Sunburst", holder.modelBadge.getText().toString());
                assertReadable(holder.durationText);
                assertFits(holder.card);
                assertSeparated(holder.card, holder.durationText, holder.modelBadge);
            }
        }
    }

    @Test public void renderRepresentativeSuccessCardsForVisualReview() throws Exception {
        int[] widths = {320, 360, 412};
        float[] scales = {1.3f, 1f, 1.5f};
        File workingDirectory = new File(System.getProperty("user.dir"));
        File appDirectory = "app".equals(workingDirectory.getName()) ? workingDirectory : new File(workingDirectory, "app");
        File snapshots = new File(appDirectory, "build/reports/tests/testDebugUnitTest/layout-snapshots");
        assertTrue(snapshots.isDirectory() || snapshots.mkdirs());
        for (int index = 0; index < widths.length; index++) {
            Context context = context(widths[index], scales[index]);
            GalleryAdapters.RecordAdapter.Holder holder = holder(context);
            holder.bind(record(GenerationRecord.STATUS_SUCCESS, true));
            measure(holder.card, widths[index]);
            assertFits(holder.card);
            int padding = UiKit.dp(context, 8);
            Bitmap bitmap = Bitmap.createBitmap(UiKit.dp(context, widths[index]), holder.card.getHeight() + padding * 2,
                    Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(UiKit.PAPER);
                canvas.translate(padding, padding);
                holder.card.draw(canvas);
                File output = new File(snapshots, "success-" + widths[index] + "dp-font-" + scales[index] + ".png");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
                assertTrue(output.length() > 0);
            } finally { bitmap.recycle(); }
        }
    }

    private GalleryAdapters.RecordAdapter.Holder holder(Context context) {
        return new GalleryAdapters.RecordAdapter.Holder(context, mock(GalleryAdapters.RecordListener.class));
    }

    private Context context(int width, float scale) {
        Context application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(application.getResources().getConfiguration());
        configuration.fontScale = scale;
        configuration.screenWidthDp = width;
        Context context = new ContextThemeWrapper(application.createConfigurationContext(configuration),
                android.R.style.Theme_Material_Light_NoActionBar);
        context.getResources().getDisplayMetrics().scaledDensity = context.getResources().getDisplayMetrics().density * scale;
        return context;
    }

    private GenerationRecord record(int status, boolean reference) {
        return new GenerationRecord(UUID.randomUUID().toString(), 1795188120000L, 1, "线路 2",
                "GPT-2.5 Sunburst", "gpt-image-2.5-sunburst", "9:16", "超写实真人 Cosplay 自拍摄影，9:16 竖构图，日系梦幻美学，真实智能手机近距离拍摄质感，非动漫、非 3D 渲染。",
                reference, "", false, status, "", "", 1429600);
    }

    private void measure(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(UiKit.dp(view.getContext(), width - 16), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private void assertReadable(TextView text) {
        assertTrue(text.getText().length() > 0);
        assertNotNull(text.getLayout());
        int contentWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        assertTrue("Text has no usable width: " + text.getText(), contentWidth > 0);
        for (int line = 0; line < text.getLayout().getLineCount(); line++) {
            assertEquals("Text was truncated: " + text.getText(), 0, text.getLayout().getEllipsisCount(line));
            assertTrue("Text extends outside its view: " + text.getText(), text.getLayout().getLineWidth(line) <= contentWidth + 1);
        }
        assertTrue("Text height clips lines: " + text.getText(),
                text.getHeight() >= text.getLayout().getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
    }

    private void assertFits(ViewGroup parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child.getVisibility() == View.GONE) continue;
            assertTrue("Child crosses left edge", child.getLeft() >= parent.getPaddingLeft());
            assertTrue("Child crosses top edge", child.getTop() >= parent.getPaddingTop());
            assertTrue("Child crosses right edge", child.getRight() <= parent.getWidth() - parent.getPaddingRight());
            assertTrue("Child crosses bottom edge", child.getBottom() <= parent.getHeight() - parent.getPaddingBottom());
            for (int next = index + 1; next < parent.getChildCount(); next++) {
                View sibling = parent.getChildAt(next);
                if (sibling.getVisibility() != View.GONE) assertFalse("Sibling views overlap", Rect.intersects(bounds(child), bounds(sibling)));
            }
            if (child instanceof ViewGroup) assertFits((ViewGroup) child);
        }
    }

    private Rect bounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private void assertSeparated(ViewGroup ancestor, View first, View second) {
        Rect firstBounds = new Rect(0, 0, first.getWidth(), first.getHeight());
        Rect secondBounds = new Rect(0, 0, second.getWidth(), second.getHeight());
        ancestor.offsetDescendantRectToMyCoords(first, firstBounds);
        ancestor.offsetDescendantRectToMyCoords(second, secondBounds);
        assertFalse("Independent content rows overlap", Rect.intersects(firstBounds, secondBounds));
    }
}
