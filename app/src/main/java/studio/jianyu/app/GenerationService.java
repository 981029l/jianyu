package studio.jianyu.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.util.List;

public final class GenerationService extends Service {
    static final String CHANNEL = "generation-tasks";
    static final String SHOW_RECORDS = "show-generation-records";
    static final String STOP_WAITING = "studio.jianyu.app.STOP_WAITING";
    static final int NOTIFICATION_ID = 100;
    private static final long UPDATE_INTERVAL_MS = 2000;
    private static final long WAKE_TIMEOUT_MS = 60 * 60 * 1000;
    private static volatile GenerationService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (GenerationTasks.count() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            notifySafely(NOTIFICATION_ID, progressNotification());
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    static void ensureRunning(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, GenerationService.class));
    }

    static void taskChanged() {
        GenerationService service = instance;
        if (service != null) service.handler.post(() -> {
            if (GenerationTasks.count() > 0) service.notifySafely(NOTIFICATION_ID, service.progressNotification());
        });
    }

    static void taskFinished(String recordId, String imageId, String error, boolean cancelled) {
        GenerationService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            if (!cancelled) {
                String title = imageId != null ? "绘制成功" : "生成任务已暂停";
                String message = imageId != null ? "作品已保存，点击查看作品库。" : error;
                Notification notification = service.builder(title, message == null ? "点击查看生成记录。" : message)
                        .setAutoCancel(true).build();
                service.notifySafely(1024 + (recordId.hashCode() & 0x7fff), notification);
            }
            service.handler.removeCallbacks(service.ticker);
            service.handler.post(service.ticker);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "生成任务", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":generation");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(NOTIFICATION_ID, progressNotification()); }
        catch (RuntimeException error) {
            GenerationTasks.cancelAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && STOP_WAITING.equals(intent.getAction())) {
            GenerationTasks.cancelAll();
        } else {
            wakeLock.acquire(WAKE_TIMEOUT_MS);
            if (intent == null) resumeInterrupted();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_STICKY;
    }

    private void resumeInterrupted() {
        List<GenerationRecord> records = GenerationRecord.Repository.getAll(this);
        GenerationTasks.restore(this);
        for (GenerationRecord record : records) {
            if (!GenerationRecord.isActive(record.status) || GenerationTasks.get(record.recordId) != null) continue;
            ImageService service = new ImageService(this, record.recordId);
            if (service.hasPending()) GenerationTasks.start(this, record, service, MainActivity::finished);
        }
    }

    private NotificationCompat.Builder builder(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).putExtra(SHOW_RECORDS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_wand)
                .setContentTitle(title).setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content).setOnlyAlertOnce(true);
    }

    private Notification progressNotification() {
        GenerationTasks.Task task = GenerationTasks.first();
        String text = task == null ? "正在准备生成任务…" : GenerationRecord.statusLabel(task.record.status);
        if (task != null && task.record.status == GenerationRecord.STATUS_DOWNLOADING) text += " · " + task.service.downloadProgress();
        Intent stop = new Intent(this, GenerationService.class).setAction(STOP_WAITING);
        PendingIntent action = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder(GenerationTasks.count() + " 条绘图任务进行中", text).setOngoing(true)
                .addAction(0, "停止全部等待", action).build();
    }

    private void notifySafely(int id, Notification notification) {
        try { NotificationManagerCompat.from(this).notify(id, notification); }
        catch (SecurityException ignored) { }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        GenerationTasks.cancelAll();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
