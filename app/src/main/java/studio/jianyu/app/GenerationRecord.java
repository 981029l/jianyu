package studio.jianyu.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class GenerationRecord {
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_CANCELLED = 3;
    public static final int STATUS_SUBMITTING = 4;
    public static final int STATUS_QUEUED = 5;
    public static final int STATUS_DOWNLOADING = 6;
    public static final int STATUS_WAITING = 7;
    public static final int STATUS_UNCONFIRMED = 8;

    public static boolean isActive(int status) {
        return status == STATUS_RUNNING || status == STATUS_SUBMITTING
                || status == STATUS_QUEUED || status == STATUS_DOWNLOADING;
    }

    public static String statusLabel(int status) {
        switch (status) {
            case STATUS_SUBMITTING: return "提交中";
            case STATUS_QUEUED: return "服务端排队";
            case STATUS_RUNNING: return "生成中";
            case STATUS_DOWNLOADING: return "下载中";
            case STATUS_WAITING: return "待继续获取";
            case STATUS_UNCONFIRMED: return "待核对";
            case STATUS_SUCCESS: return "绘制成功";
            case STATUS_FAILED: return "绘制失败";
            case STATUS_CANCELLED: return "已停止等待";
            default: return "待核对";
        }
    }

    public final String recordId;
    public final long timestamp;
    public final int route;          // 0: 线路 1, 1: 线路 2
    public final String routeName;   // "线路 1" / "线路 2"
    public final String model;       // display model name e.g. "GPT-2.5", "Grok"
    public final String wireModel;   // e.g. "image-1", "gpt-2.5"
    public final String ratio;       // "1:1", "3:4", etc.
    public final String prompt;
    public final boolean hasRef;
    public final String refUriStr;
    public final boolean nsfw;

    public volatile int status;
    public String resultImageId;
    public String errorMessage;
    public long durationMs;

    public GenerationRecord(String recordId, long timestamp, int route, String routeName,
                            String model, String wireModel, String ratio, String prompt,
                            boolean hasRef, String refUriStr, boolean nsfw,
                            int status, String resultImageId, String errorMessage, long durationMs) {
        this.recordId = recordId;
        this.timestamp = timestamp;
        this.route = route;
        this.routeName = routeName;
        this.model = model;
        this.wireModel = wireModel;
        this.ratio = ratio;
        this.prompt = prompt;
        this.hasRef = hasRef;
        this.refUriStr = refUriStr == null ? "" : refUriStr;
        this.nsfw = nsfw;
        this.status = status;
        this.resultImageId = resultImageId == null ? "" : resultImageId;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.durationMs = durationMs;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", recordId);
            obj.put("time", timestamp);
            obj.put("route", route);
            obj.put("routeName", routeName);
            obj.put("model", model);
            obj.put("wireModel", wireModel);
            obj.put("ratio", ratio);
            obj.put("prompt", prompt);
            obj.put("hasRef", hasRef);
            obj.put("refUri", refUriStr);
            obj.put("nsfw", nsfw);
            obj.put("status", status);
            obj.put("resultImageId", resultImageId);
            obj.put("errorMessage", errorMessage);
            obj.put("durationMs", durationMs);
        } catch (Exception ignored) { }
        return obj;
    }

    public static GenerationRecord fromJson(JSONObject obj) {
        if (obj == null) return null;
        return new GenerationRecord(
                obj.optString("id", String.valueOf(System.currentTimeMillis())),
                obj.optLong("time", System.currentTimeMillis()),
                obj.optInt("route", 0),
                obj.optString("routeName", "线路 1"),
                obj.optString("model", "GPT"),
                obj.optString("wireModel", "image-1"),
                obj.optString("ratio", "1:1"),
                obj.optString("prompt", ""),
                obj.optBoolean("hasRef", false),
                obj.optString("refUri", ""),
                obj.optBoolean("nsfw", false),
                obj.optInt("status", STATUS_FAILED),
                obj.optString("resultImageId", ""),
                obj.optString("errorMessage", ""),
                obj.optLong("durationMs", 0)
        );
    }

    public static final class Repository {
        private static final String PREF_NAME = "generation_records_store";
        private static final String KEY_RECORDS = "records";
        private static final int MAX_RECORDS = 200;

        public static synchronized List<GenerationRecord> getAll(Context context) {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_RECORDS, "[]");
            List<GenerationRecord> list = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    GenerationRecord r = GenerationRecord.fromJson(arr.optJSONObject(i));
                    if (r != null) list.add(r);
                }
            } catch (Exception ignored) { }
            return list;
        }

        public static synchronized void saveRecord(Context context, GenerationRecord record) {
            if (record == null) return;
            List<GenerationRecord> list = getAll(context);
            int existingIdx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).recordId.equals(record.recordId)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0) {
                list.set(existingIdx, record);
            } else {
                list.add(0, record);
            }
            for (int i = list.size() - 1; list.size() > MAX_RECORDS && i >= 0; i--) {
                GenerationRecord older = list.get(i);
                if (!isActive(older.status) && older.status != STATUS_UNCONFIRMED
                        && !new ImageService(context, older.recordId).hasPending()) list.remove(i);
            }
            writeBack(context, list);
        }

        public static synchronized void updateStatus(Context context, String recordId, int status,
                                                     String resultImageId, String errorMessage, long durationMs) {
            if (recordId == null || recordId.isEmpty()) return;
            List<GenerationRecord> list = getAll(context);
            for (GenerationRecord r : list) {
                if (recordId.equals(r.recordId)) {
                    r.status = status;
                    if (resultImageId != null) r.resultImageId = resultImageId;
                    if (errorMessage != null) r.errorMessage = errorMessage;
                    r.durationMs = durationMs;
                    break;
                }
            }
            writeBack(context, list);
        }

        public static synchronized void deleteRecord(Context context, String recordId) {
            if (recordId == null) return;
            List<GenerationRecord> list = getAll(context);
            list.removeIf(r -> recordId.equals(r.recordId) && !isActive(r.status));
            if (list.stream().noneMatch(r -> recordId.equals(r.recordId))) new ImageService(context, recordId).forget();
            writeBack(context, list);
        }

        public static synchronized void clearAll(Context context) {
            List<GenerationRecord> list = getAll(context);
            list.removeIf(r -> {
                if (isActive(r.status)) return false;
                new ImageService(context, r.recordId).forget();
                return true;
            });
            writeBack(context, list);
        }

        private static void writeBack(Context context, List<GenerationRecord> list) {
            JSONArray arr = new JSONArray();
            for (GenerationRecord r : list) {
                arr.put(r.toJson());
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_RECORDS, arr.toString()).commit();
        }
    }
}
