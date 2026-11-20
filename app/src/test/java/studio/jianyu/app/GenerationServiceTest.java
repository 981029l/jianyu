package studio.jianyu.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GenerationServiceTest {
    @Test public void foregroundServiceKeepsTaskNotificationAndReleasesWakeLockAfterCompletion() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        GenerationRecord record = record();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ImageService network = mock(ImageService.class);
        when(network.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
            return "background-saved.png";
        });
        GenerationTasks.start(app, record, network, (r, i, e, c) -> {});
        ServiceController<GenerationService> controller = Robolectric.buildService(GenerationService.class).create();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            GenerationService service = controller.get();
            service.onStartCommand(new Intent(app, GenerationService.class), 0, 1);
            Notification foreground = shadowOf(service).getLastForegroundNotification();
            assertNotNull(foreground);
            assertTrue(foreground.extras.getString(Notification.EXTRA_TITLE).contains("1 条"));
            assertEquals(1, GenerationTasks.count());
            PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
            assertTrue(lock.isHeld());
            finish.countDown();
            awaitFinished(record.recordId);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(GenerationRecord.STATUS_SUCCESS, saved(record.recordId).status);
            assertEquals("background-saved.png", saved(record.recordId).resultImageId);
            NotificationManager notifications = app.getSystemService(NotificationManager.class);
            assertTrue(java.util.Arrays.stream(notifications.getActiveNotifications()).anyMatch(item ->
                    "绘制成功".contentEquals(item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, ""))));
            controller.destroy();
            assertFalse(lock.isHeld());
        } finally {
            finish.countDown();
            awaitFinished(record.recordId);
        }
    }

    @Test public void systemForegroundTimeoutCancelsWaitingAndKeepsOriginalTaskRecoverable() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        GenerationRecord record = record();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ImageService network = mock(ImageService.class);
        when(network.hasPending()).thenReturn(true);
        when(network.isCancelled()).thenAnswer(call -> stopped.getCount() == 0);
        doAnswer(call -> { stopped.countDown(); return null; }).when(network).cancelActive();
        when(network.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            entered.countDown();
            if (!stopped.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
            throw new IOException("stopped");
        });
        GenerationTasks.start(app, record, network, (r, i, e, c) -> {});
        ServiceController<GenerationService> controller = Robolectric.buildService(GenerationService.class).create();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.get().onStartCommand(new Intent(app, GenerationService.class), 0, 1);
            controller.get().onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            awaitFinished(record.recordId);
            assertEquals(GenerationRecord.STATUS_WAITING, saved(record.recordId).status);
            assertTrue(saved(record.recordId).errorMessage.contains("原任务"));
            verify(network).cancelActive();
        } finally {
            stopped.countDown();
            awaitFinished(record.recordId);
            controller.destroy();
        }
    }

    @Test public void emptyRestartDoesNotSubmitNewGeneration() {
        Context app = RuntimeEnvironment.getApplication();
        GenerationRecord record = record();
        record.status = GenerationRecord.STATUS_SUBMITTING;
        GenerationRecord.Repository.saveRecord(app, record);
        app.getSharedPreferences(ImageService.PENDING_PREFS + "-" + record.recordId, 0).edit()
                .putBoolean("submissionUnconfirmed", true).commit();
        ServiceController<GenerationService> controller = Robolectric.buildService(GenerationService.class).create();
        try {
            controller.get().onStartCommand(null, 0, 1);
            assertEquals(0, GenerationTasks.count());
            assertEquals(GenerationRecord.STATUS_UNCONFIRMED, saved(record.recordId).status);
        } finally { controller.destroy(); }
    }

    @Test public void restartResumesOnlyInterruptedKnownTaskAndLeavesManualPauseAlone() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        GenerationRecord interrupted = record();
        GenerationRecord paused = record();
        paused.status = GenerationRecord.STATUS_WAITING;
        GenerationRecord.Repository.saveRecord(app, interrupted);
        GenerationRecord.Repository.saveRecord(app, paused);
        CountDownLatch fetched = new CountDownLatch(1);
        try (MockedConstruction<ImageService> construction = mockConstruction(ImageService.class, (network, call) -> {
            String owner = (String) call.arguments().get(1);
            when(network.hasPending()).thenReturn(owner.equals(interrupted.recordId) || owner.equals(paused.recordId));
            when(network.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(invocation -> {
                assertEquals(interrupted.recordId, owner);
                fetched.countDown();
                return "resumed-original.png";
            });
        })) {
            ServiceController<GenerationService> controller = Robolectric.buildService(GenerationService.class).create();
            try {
                controller.get().onStartCommand(null, 0, 1);
                assertTrue(fetched.await(5, TimeUnit.SECONDS));
                awaitFinished(interrupted.recordId);
                assertEquals(GenerationRecord.STATUS_SUCCESS, saved(interrupted.recordId).status);
                assertEquals(GenerationRecord.STATUS_WAITING, saved(paused.recordId).status);
                int runs = 0;
                for (ImageService network : construction.constructed()) {
                    runs += (int) mockingDetails(network).getInvocations().stream().filter(call -> call.getMethod().getName().equals("run")).count();
                }
                assertEquals(1, runs);
            } finally { controller.destroy(); }
        }
    }

    private GenerationRecord record() {
        return new GenerationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), 0, "线路 1",
                "GPT", "image-1", "1:1", "后台绘图测试", false, "", false,
                GenerationRecord.STATUS_RUNNING, "", "", 0);
    }

    private GenerationRecord saved(String id) {
        return GenerationRecord.Repository.getAll(RuntimeEnvironment.getApplication()).stream()
                .filter(record -> id.equals(record.recordId)).findFirst().orElseThrow(AssertionError::new);
    }

    private void awaitFinished(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (GenerationTasks.get(id) != null && System.nanoTime() < until) Thread.sleep(10);
        assertNull(GenerationTasks.get(id));
    }
}
