package studio.jianyu.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ChinesePromptsTest {
    @Test public void choosesChineseVariantAndPreservesRenderingTerms() throws Exception {
        String chinese = "生成水彩海报，保留文字 Hello\n\nUE5, Octane Render, 8K";
        JSONArray prompts = new JSONArray()
                .put(new JSONObject().put("type", "en").put("text", "Generate a poster containing Chinese text 海报"))
                .put(new JSONObject().put("type", "zh").put("text", chinese));
        assertEquals(chinese, ChinesePrompts.select(prompts));
    }

    @Test public void filtersUntaggedEnglishDuplicatesAndDeduplicatesChinese() {
        JSONArray prompts = new JSONArray().put("A watercolor lighthouse")
                .put("清晨的水彩灯塔").put("清晨的水彩灯塔")
                .put("Soft lighting\n柔和的光线，3D 风格");
        assertEquals("清晨的水彩灯塔\n\n柔和的光线，3D 风格", ChinesePrompts.select(prompts));
    }

    @Test public void englishOnlyNeverFallsBackToTitleAsPrompt() {
        InspirationRepository.Item item = item("English title", "");
        item.applyDetail(ChinesePrompts.select(new JSONArray().put("English only")), "", "", "", "");
        assertEquals("", item.prompt);
        assertFalse(item.needsDetailFetch());
        assertEquals("精选灵感", item.displayTitle());
        assertEquals("这条灵感暂未提供中文提示词", item.displayPrompt());
    }

    @Test public void legacyFavoritesHideProviderNamesAndEnglishVariants() {
        InspirationRepository.Item item = item("中文标题", "English version\n\n中文版本");
        assertEquals("中文版本", item.prompt);
        assertEquals("发现灵感", item.major);
        assertEquals("精选灵感", item.category);
        assertTrue(item.needsDetailFetch());
        assertEquals("opennana:test", item.uniqueKey());
    }

    @Test public void selectedChinesePromptSurvivesFavoriteRoundTrip() {
        String prompt = "中文画面说明\n\nUE5, Octane Render, 8K";
        InspirationRepository.Item item = item("标题", "");
        item.applyDetail(prompt, "", "", "", "");
        InspirationRepository.Item cached = InspirationRepository.Item.fromJson(item.toJson());
        assertNotNull(cached);
        assertFalse(cached.needsDetailFetch());
        assertEquals(prompt, cached.prompt);
    }

    private InspirationRepository.Item item(String title, String prompt) {
        return new InspirationRepository.Item("opennana", "test", title, prompt, "", "",
                "OpenNana", "OpenNana", "OpenNana 精选", "GPT", "1:1", false, "", 1024, 1024);
    }
}
