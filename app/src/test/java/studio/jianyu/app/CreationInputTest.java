package studio.jianyu.app;

import android.net.Uri;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreationInputTest {
    private MainActivity activity;
    private EditText input;
    private MockedStatic<GenerationTasks> tasks;
    private final List<GenerationRecord> created = new ArrayList<>();

    @Before public void prepare() throws Exception {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        for (String name : new String[]{"routes", "modelsLine1", "wireModelsLine1", "modelsLine2", "wireModelsLine2", "ratios"}) {
            String resource = name.replace("Line", "_line").replace("wireModels", "wire_models");
            int id = activity.getResources().getIdentifier(resource, "array", activity.getPackageName());
            set(name, activity.getResources().getStringArray(id));
        }
        ImageService service = mock(ImageService.class);
        when(service.isLineConfigured(anyString())).thenReturn(true);
        set("service", service);
        set("prefs", activity.getSharedPreferences("creation-test", 0));
        input = new EditText(activity);
        set("promptInput", input);
        tasks = mockStatic(GenerationTasks.class);
        tasks.when(() -> GenerationTasks.start(eq(activity), any(GenerationRecord.class), any(GenerationTasks.Listener.class)))
                .thenAnswer(call -> { created.add(call.getArgument(1)); return true; });
    }

    @After public void cleanUp() throws Exception {
        ((Handler) get("uiHandler")).removeCallbacksAndMessages(null);
        tasks.close();
    }

    @Test public void successfulCreationConsumesReferenceButKeepsItInOriginalRecord() throws Exception {
        Uri reference = Uri.parse("content://test/reference/one");
        set("reference", reference);
        input.setText("海边灯塔🌊".repeat(1000));
        String original = input.getText().toString();
        invoke("generate");
        assertEquals(1, created.size());
        assertEquals(original, created.get(0).prompt);
        assertTrue(created.get(0).hasRef);
        assertEquals(reference.toString(), created.get(0).refUriStr);
        assertNull(get("reference"));
        assertEquals("", input.getText().toString());
        input.setText("下一幅风景");
        invoke("generate");
        assertEquals(2, created.size());
        assertFalse(created.get(1).hasRef);
        assertEquals("", created.get(1).refUriStr);
    }

    @Test public void failedStartAndEmptyPromptKeepReferenceAndDraft() throws Exception {
        Uri reference = Uri.parse("content://test/reference/one");
        set("reference", reference);
        input.setText(" ");
        invoke("generate");
        assertTrue(created.isEmpty());
        assertEquals(reference, get("reference"));
        assertNotNull(input.getError());
        tasks.when(() -> GenerationTasks.start(eq(activity), any(GenerationRecord.class), any(GenerationTasks.Listener.class)))
                .thenReturn(false);
        input.setText("海边灯塔");
        invoke("generate");
        assertEquals(reference, get("reference"));
        assertEquals("海边灯塔", input.getText().toString());
    }

    @Test public void nsfwSummaryAndRecordUseFixedRatioAndClosingRestoresSelection() throws Exception {
        set("modelLine1", 1);
        set("ratio", 4);
        set("nsfw", true);
        TextView summary = new TextView(activity);
        set("paramSummaryText", summary);
        invoke("refreshHomeDynamic");
        assertTrue(summary.getText().toString().contains("2:3"));
        input.setText("成年角色艺术摄影");
        invoke("generate");
        assertEquals("2:3", created.get(0).ratio);
        assertTrue(created.get(0).nsfw);
        set("nsfw", false);
        invoke("refreshHomeDynamic");
        assertTrue(summary.getText().toString().contains("16:9"));
        assertFalse(summary.getText().toString().contains("NSFW"));
    }

    @Test public void parameterSwitchHidesOtherRatiosAndRestoresThem() throws Exception {
        set("modelLine1", 1);
        set("ratio", 4);
        invoke("showParameterSheet");
        android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        try {
            View root = dialog.getWindow().getDecorView();
            assertTrue(labels(root).contains("16:9"));
            findSwitch(root).setChecked(true);
            assertFalse(labels(root).contains("16:9"));
            assertTrue(labels(root).contains("NSFW 模式固定输出 2:3"));
            assertTrue(labels(root).stream().anyMatch(text -> text.contains("18 岁") && text.contains("不支持未成年人")));
            findSwitch(root).setChecked(false);
            assertTrue(labels(root).contains("16:9"));
            assertEquals(4, get("ratio"));
        } finally { dialog.dismiss(); }
    }

    private List<String> labels(View view) {
        List<String> result = new ArrayList<>();
        if (view instanceof TextView) result.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) result.addAll(labels(group.getChildAt(index)));
        }
        return result;
    }

    private Switch findSwitch(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                Switch found = findSwitch(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private void invoke(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }
}
