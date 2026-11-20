package studio.jianyu.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

final class ChinesePrompts {
    static boolean hasChinese(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    static String chineseBlocks(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String block : text.replace("\r\n", "\n").split("\n\s*\n")) {
            StringBuilder chinese = new StringBuilder();
            for (String line : block.split("\n")) {
                if (!hasChinese(line)) continue;
                if (chinese.length() > 0) chinese.append("\n");
                chinese.append(line.trim());
            }
            if (chinese.length() == 0) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(chinese);
        }
        return out.toString();
    }

    static String select(JSONArray prompts) {
        Set<String> selected = new LinkedHashSet<>();
        if (prompts == null) return "";
        for (int i = 0; i < prompts.length(); i++) {
            Object entry = prompts.opt(i);
            String text = "";
            if (entry instanceof JSONObject) {
                JSONObject obj = (JSONObject) entry;
                String language = obj.optString("type", obj.optString("lang", obj.optString("language", "")))
                        .toLowerCase(java.util.Locale.ROOT);
                if (language.equals("zh") || language.startsWith("zh-") || language.equals("cn")) {
                    text = obj.optString("text", "").trim();
                } else if (language.isEmpty()) {
                    text = chineseBlocks(obj.optString("zh", obj.optString("text", "")));
                }
            } else if (entry instanceof String) {
                text = chineseBlocks((String) entry);
            }
            if (hasChinese(text)) selected.add(text);
        }
        return String.join("\n\n", selected);
    }
}
