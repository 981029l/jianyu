package studio.jianyu.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class GenerationTasks {
    interface Listener {
        void onFinished(String recordId, String imageId, String error, boolean cancelled);
    }

    static final class Task {
        final GenerationRecord record;
        final ImageService service;
        final long startedAt = System.currentTimeMillis();

        Task(GenerationRecord record, ImageService service) {
            this.record = record;
            this.service = service;
        }
    }

    private static final Map<String, Task> active = new LinkedHashMap<>();

    static synchronized int count() { return active.size(); }
    static synchronized Task get(String recordId) { return active.get(recordId); }
    static synchronized Task first() { return active.isEmpty() ? null : active.values().iterator().next(); }

    static boolean canResume(Context context, String recordId) {
        return get(recordId) == null && new ImageService(context, recordId).hasPending();
    }

    static int pendingCount(Context context) {
        int count = 0;
        for (GenerationRecord record : GenerationRecord.Repository.getAll(context)) {
            if (canResume(context, record.recordId)) count++;
        }
        return count;
    }

    static void start(Context context, GenerationRecord record, Listener listener) {
        start(context, record, new ImageService(context, record.recordId), listener);
    }

    static synchronized void start(Context context, GenerationRecord record, ImageService service, Listener listener) {
        if (active.containsKey(record.recordId)) return;
        Context app = context.getApplicationContext();
        record.status = GenerationRecord.STATUS_RUNNING;
        record.errorMessage = "";
        GenerationRecord.Repository.saveRecord(app, record);
        Task task = new Task(record, service);
        active.put(record.recordId, task);
        new Thread(() -> execute(app, task, listener), "jianyu-image-" + record.recordId).start();
    }

    static synchronized void cancel(String recordId) {
        Task task = active.get(recordId);
        if (task != null) task.service.cancelActive();
    }

    private static void execute(Context app, Task task, Listener listener) {
        GenerationRecord record = task.record;
        String imageId = null;
        String error = null;
        try {
            Uri reference = record.hasRef && !record.refUriStr.isEmpty() ? Uri.parse(record.refUriStr) : null;
            imageId = task.service.run(record.prompt, record.wireModel, record.ratio, reference, record.nsfw);
        } catch (Exception e) {
            error = e instanceof java.net.SocketTimeoutException || e instanceof java.net.SocketException
                    || e instanceof java.net.UnknownHostException
                    ? "网络连接中断，请先核对原任务记录。" : e.getMessage();
            if (error == null || error.isEmpty()) error = "无法获取作品，请检查网络或服务连接。";
        }
        boolean cancelled = imageId == null && task.service.isCancelled();
        int status = imageId != null ? GenerationRecord.STATUS_SUCCESS
                : cancelled ? GenerationRecord.STATUS_CANCELLED : GenerationRecord.STATUS_FAILED;
        long duration = record.durationMs + Math.max(0, System.currentTimeMillis() - task.startedAt);
        GenerationRecord.Repository.updateStatus(app, record.recordId, status, imageId,
                cancelled ? "用户已中断等待" : error, duration);
        synchronized (GenerationTasks.class) { active.remove(record.recordId); }
        String result = imageId;
        String message = error;
        new Handler(Looper.getMainLooper()).post(() -> listener.onFinished(record.recordId, result, message, cancelled));
    }

    static synchronized void restore(Context context) {
        Context app = context.getApplicationContext();
        List<GenerationRecord> records = GenerationRecord.Repository.getAll(app);
        SharedPreferences legacy = app.getSharedPreferences(ImageService.PENDING_PREFS, Context.MODE_PRIVATE);
        if (legacy.contains("task") || legacy.contains("url")) {
            String prompt = legacy.getString("prompt", "");
            String model = legacy.getString("model", "image-1");
            GenerationRecord owner = null;
            for (GenerationRecord record : records) {
                if (record.status != GenerationRecord.STATUS_SUCCESS && record.prompt.equals(prompt)
                        && record.wireModel.equalsIgnoreCase(model)) { owner = record; break; }
            }
            if (owner == null) {
                int route = ImageService.isLine2(model) ? 1 : 0;
                owner = new GenerationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), route,
                        "线路 " + (route + 1), model, model, legacy.getString("ratio", "1:1"), prompt,
                        false, "", false, GenerationRecord.STATUS_FAILED, "", "可继续获取原任务", 0);
                GenerationRecord.Repository.saveRecord(app, owner);
                records.add(owner);
            }
            SharedPreferences.Editor copy = app.getSharedPreferences(ImageService.PENDING_PREFS + "-" + owner.recordId,
                    Context.MODE_PRIVATE).edit();
            for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
                if (entry.getValue() instanceof String) copy.putString(entry.getKey(), (String) entry.getValue());
            }
            if (copy.commit()) legacy.edit().clear().commit();
        }
        for (GenerationRecord record : records) {
            if (record.status == GenerationRecord.STATUS_RUNNING && !active.containsKey(record.recordId)) {
                String message = canResume(app, record.recordId) ? "等待已中断，可继续获取原任务。"
                        : "应用退出时等待中断，请核对服务端任务后再重新创作。";
                GenerationRecord.Repository.updateStatus(app, record.recordId, GenerationRecord.STATUS_FAILED,
                        null, message, record.durationMs);
            }
        }
    }
}
