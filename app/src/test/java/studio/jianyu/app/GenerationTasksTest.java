package studio.jianyu.app;

import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GenerationTasksTest {
    private Context context;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        GenerationRecord.Repository.clearAll(context);
    }

    @Test public void twoPromptsRunTogetherAndFinishInReverseOrderWithoutActivity() throws Exception {
        GenerationRecord first = record("海边灯塔");
        GenerationRecord second = record("雪山小屋");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        ImageService serviceA = blockedService(entered, releaseFirst, "lighthouse.png");
        ImageService serviceB = blockedService(entered, releaseSecond, "cabin.png");
        try {
            GenerationTasks.start(context, first, serviceA, (r, i, e, c) -> {});
            GenerationTasks.start(context, second, serviceB, (r, i, e, c) -> {});
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, GenerationTasks.count());
            assertEquals(2, GenerationRecord.Repository.getAll(context).size());
            releaseSecond.countDown();
            awaitFinished(second.recordId);
            assertNotNull(GenerationTasks.get(first.recordId));
            assertEquals("cabin.png", saved(second.recordId).resultImageId);
            assertEquals(GenerationRecord.STATUS_SUBMITTING, saved(first.recordId).status);
            releaseFirst.countDown();
            awaitFinished(first.recordId);
            assertEquals("lighthouse.png", saved(first.recordId).resultImageId);
            assertEquals("海边灯塔", saved(first.recordId).prompt);
            assertEquals("雪山小屋", saved(second.recordId).prompt);
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown();
            awaitFinished(first.recordId); awaitFinished(second.recordId);
        }
    }

    @Test public void cancellingOneTaskLeavesOtherRunningAndDoesNotDuplicateRecord() throws Exception {
        GenerationRecord first = record("取消这一条");
        GenerationRecord second = record("继续这一条");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ImageService serviceA = mock(ImageService.class);
        when(serviceA.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            entered.countDown(); releaseFirst.await(10, TimeUnit.SECONDS); throw new IOException("cancelled");
        });
        when(serviceA.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(true); releaseFirst.countDown(); return null; }).when(serviceA).cancelActive();
        ImageService serviceB = blockedService(entered, releaseSecond, "kept.png");
        try {
            GenerationTasks.start(context, first, serviceA, (r, i, e, c) -> {});
            GenerationTasks.start(context, second, serviceB, (r, i, e, c) -> {});
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            GenerationTasks.start(context, first, serviceA, (r, i, e, c) -> {});
            GenerationTasks.cancel(first.recordId);
            awaitFinished(first.recordId);
            assertEquals(GenerationRecord.STATUS_CANCELLED, saved(first.recordId).status);
            assertNotNull(GenerationTasks.get(second.recordId));
            verify(serviceB, never()).cancelActive();
            releaseSecond.countDown();
            awaitFinished(second.recordId);
            assertEquals("kept.png", saved(second.recordId).resultImageId);
            assertEquals(2, GenerationRecord.Repository.getAll(context).size());
            verify(serviceA, times(1)).run(anyString(), anyString(), anyString(), isNull(), anyBoolean());
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown();
            awaitFinished(first.recordId); awaitFinished(second.recordId);
        }
    }

    @Test public void retryUpdatesOriginalRecordAfterFailure() throws Exception {
        GenerationRecord record = record("断线后继续获取");
        ImageService failed = mock(ImageService.class);
        when(failed.run(anyString(), anyString(), anyString(), isNull(), anyBoolean()))
                .thenThrow(new IOException("temporary disconnect"));
        GenerationTasks.start(context, record, failed, (r, i, e, c) -> {});
        awaitFinished(record.recordId);
        assertEquals(GenerationRecord.STATUS_FAILED, saved(record.recordId).status);
        ImageService resumed = mock(ImageService.class);
        when(resumed.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenReturn("recovered.png");
        GenerationTasks.start(context, saved(record.recordId), resumed, (r, i, e, c) -> {});
        awaitFinished(record.recordId);
        assertEquals(1, GenerationRecord.Repository.getAll(context).size());
        assertEquals("recovered.png", saved(record.recordId).resultImageId);
        assertEquals(GenerationRecord.STATUS_SUCCESS, saved(record.recordId).status);
        assertEquals("", saved(record.recordId).errorMessage);
    }

    @Test public void pendingStateIsIsolatedAndLegacyTaskMigratesToItsRecord() {
        GenerationRecord first = record("第一条");
        GenerationRecord second = record("第二条");
        GenerationRecord.Repository.saveRecord(context, first);
        GenerationRecord.Repository.saveRecord(context, second);
        context.getSharedPreferences(ImageService.PENDING_PREFS, 0).edit()
                .putString("task", "legacy-task").putString("prompt", first.prompt)
                .putString("model", first.wireModel).putString("ratio", "1:1").commit();
        GenerationTasks.restore(context);
        ImageService a = new ImageService(context, first.recordId);
        ImageService b = new ImageService(context, second.recordId);
        assertTrue(a.hasPending());
        assertFalse(b.hasPending());
        assertFalse(new ImageService(context).hasPending());
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + second.recordId, 0).edit()
                .putString("task", "second-task").commit();
        a.forget();
        assertTrue(b.hasPending());
        assertEquals(2, GenerationRecord.Repository.getAll(context).size());
        assertEquals(GenerationRecord.STATUS_WAITING, saved(first.recordId).status);
    }

    @Test public void clearAndDeletePreserveRunningRecords() {
        GenerationRecord running = record("正在生成");
        GenerationRecord done = record("已经结束");
        done.status = GenerationRecord.STATUS_SUCCESS;
        GenerationRecord.Repository.saveRecord(context, running);
        GenerationRecord.Repository.saveRecord(context, done);
        GenerationRecord.Repository.deleteRecord(context, running.recordId);
        assertEquals(2, GenerationRecord.Repository.getAll(context).size());
        GenerationRecord.Repository.clearAll(context);
        assertEquals(1, GenerationRecord.Repository.getAll(context).size());
        assertEquals(running.recordId, GenerationRecord.Repository.getAll(context).get(0).recordId);
        GenerationTasks.restore(context);
    }

    @Test public void retainedTaskAndUnconfirmedSubmissionAreNotFailures() throws Exception {
        GenerationRecord pending = record("原任务继续获取");
        ImageService service = mock(ImageService.class);
        when(service.hasPending()).thenReturn(true);
        when(service.run(anyString(), anyString(), anyString(), isNull(), anyBoolean()))
                .thenThrow(new java.net.SocketTimeoutException());
        GenerationTasks.start(context, pending, service, (r, i, e, c) -> {});
        awaitFinished(pending.recordId);
        assertEquals(GenerationRecord.STATUS_WAITING, saved(pending.recordId).status);
        assertTrue(saved(pending.recordId).errorMessage.contains("不会重复提交"));
        GenerationRecord unknown = record("提交断网");
        ImageService uncertain = mock(ImageService.class);
        when(uncertain.isSubmissionUnconfirmed()).thenReturn(true);
        when(uncertain.run(anyString(), anyString(), anyString(), isNull(), anyBoolean()))
                .thenThrow(new java.net.SocketException());
        GenerationTasks.start(context, unknown, uncertain, (r, i, e, c) -> {});
        awaitFinished(unknown.recordId);
        assertEquals(GenerationRecord.STATUS_UNCONFIRMED, saved(unknown.recordId).status);
    }

    @Test public void stoppingWaitKeepsResumableTask() throws Exception {
        GenerationRecord record = record("只停止等待");
        ImageService service = mock(ImageService.class);
        when(service.hasPending()).thenReturn(true);
        when(service.isCancelled()).thenReturn(true);
        when(service.run(anyString(), anyString(), anyString(), isNull(), anyBoolean()))
                .thenThrow(new IOException("stopped"));
        GenerationTasks.start(context, record, service, (r, i, e, c) -> {});
        awaitFinished(record.recordId);
        assertEquals(GenerationRecord.STATUS_WAITING, saved(record.recordId).status);
    }

    @Test public void activeStagesPersistAndCannotBeDeleted() throws Exception {
        GenerationRecord record = record("下载阶段");
        ImageService service = mock(ImageService.class);
        java.util.concurrent.atomic.AtomicReference<java.util.function.IntConsumer> progress = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { progress.set(call.getArgument(0)); return null; }).when(service).setProgressListener(any());
        when(service.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            for (int status : new int[]{GenerationRecord.STATUS_QUEUED, GenerationRecord.STATUS_RUNNING, GenerationRecord.STATUS_DOWNLOADING}) {
                progress.get().accept(status);
                assertEquals(status, saved(record.recordId).status);
                GenerationRecord.Repository.deleteRecord(context, record.recordId);
                GenerationRecord.Repository.clearAll(context);
                assertEquals(status, saved(record.recordId).status);
            }
            return "saved.png";
        });
        GenerationTasks.start(context, record, service, (r, i, e, c) -> {});
        awaitFinished(record.recordId);
        assertEquals(GenerationRecord.STATUS_SUCCESS, saved(record.recordId).status);
    }

    @Test public void restoringPreservesPauseReasonAndMarksInterruptedStagesRecoverable() {
        GenerationRecord paused = record("查询达到时限");
        paused.status = GenerationRecord.STATUS_WAITING;
        paused.errorMessage = "已达到 20 分钟自动查询时限";
        GenerationRecord.Repository.saveRecord(context, paused);
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + paused.recordId, 0)
                .edit().putString("task", "paused-task").commit();
        GenerationRecord downloading = record("下载中退出");
        downloading.status = GenerationRecord.STATUS_DOWNLOADING;
        GenerationRecord.Repository.saveRecord(context, downloading);
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + downloading.recordId, 0)
                .edit().putString("url", "https://example.test/image.png").commit();
        GenerationRecord submitting = record("提交时退出");
        submitting.status = GenerationRecord.STATUS_SUBMITTING;
        GenerationRecord.Repository.saveRecord(context, submitting);
        GenerationTasks.restore(context);
        assertEquals("已达到 20 分钟自动查询时限", saved(paused.recordId).errorMessage);
        assertEquals(GenerationRecord.STATUS_WAITING, saved(downloading.recordId).status);
        assertEquals(GenerationRecord.STATUS_UNCONFIRMED, saved(submitting.recordId).status);
    }

    @Test public void downloadTimeoutLeavesActiveStateAndExplainsImageAlreadyGenerated() throws Exception {
        GenerationRecord record = record("下载断网");
        ImageService service = mock(ImageService.class);
        java.util.concurrent.atomic.AtomicReference<java.util.function.IntConsumer> progress = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { progress.set(call.getArgument(0)); return null; }).when(service).setProgressListener(any());
        when(service.hasPending()).thenReturn(true);
        when(service.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            progress.get().accept(GenerationRecord.STATUS_DOWNLOADING);
            throw new java.net.SocketTimeoutException();
        });
        GenerationTasks.start(context, record, service, (r, i, e, c) -> {});
        awaitFinished(record.recordId);
        assertEquals(GenerationRecord.STATUS_WAITING, saved(record.recordId).status);
        assertTrue(saved(record.recordId).errorMessage.contains("图片已生成"));
        assertTrue(saved(record.recordId).errorMessage.contains("不会重复提交"));
        assertNull(GenerationTasks.get(record.recordId));
    }

    private GenerationRecord record(String prompt) {
        return new GenerationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), 0, "线路 1",
                "GPT", "image-1", "1:1", prompt, false, "", false, GenerationRecord.STATUS_RUNNING, "", "", 0);
    }

    private ImageService blockedService(CountDownLatch entered, CountDownLatch release, String image) throws Exception {
        ImageService service = mock(ImageService.class);
        when(service.run(anyString(), anyString(), anyString(), isNull(), anyBoolean())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timed out");
            return image;
        });
        return service;
    }

    private GenerationRecord saved(String recordId) {
        for (GenerationRecord record : GenerationRecord.Repository.getAll(context)) {
            if (record.recordId.equals(recordId)) return record;
        }
        throw new AssertionError("missing record " + recordId);
    }

    private void awaitFinished(String recordId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (GenerationTasks.get(recordId) != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull(GenerationTasks.get(recordId));
        shadowOf(Looper.getMainLooper()).idle();
    }
}
