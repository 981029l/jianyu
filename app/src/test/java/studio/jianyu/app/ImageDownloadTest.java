package studio.jianyu.app;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageDownloadTest {
    private static final String ADDRESS = "https://example.test/generated.png";
    private Context context;
    private String id;
    private ImageService service;
    private byte[] png;
    private List<Long> waits;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        png = png(0xff0099ff);
        id = UUID.randomUUID().toString();
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit()
                .putString("task", "original-task").putString("url", ADDRESS).putString("prompt", "原任务")
                .putString("model", "gpt-image-2.5").putString("ratio", "1:1").commit();
        waits = new ArrayList<>();
        service = worker(id);
    }

    @Test public void interruptedDownloadRetriesOnlyMissingBytesAndSavesExactImage() throws Exception {
        int split = png.length / 2;
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, split, false));
        HttpsURLConnection rest = range(png, split, "\"v1\"");
        doReturn(first, rest).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(rest).setRequestProperty("Range", "bytes=" + split + "-");
        verify(rest).setRequestProperty("If-Range", "\"v1\"");
        verify(first, never()).setRequestMethod("POST");
        verify(rest, never()).setRequestMethod("POST");
        verify(service, times(2)).connect(any(URL.class), anyInt());
        assertEquals(Arrays.asList(2000L), waits);
        assertTrue(service.downloadProgress().contains("100%"));
        assertFalse(part(id).exists());
    }

    @Test public void serverIgnoringRangeReplacesPartialWithFullNewImage() throws Exception {
        byte[] changed = png(0xffdd3300);
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, png.length / 2, false));
        HttpsURLConnection second = full(changed, "\"v2\"", new ByteArrayInputStream(changed));
        doReturn(first, second).when(service).connect(any(URL.class), anyInt());
        assertImage(changed, run());
        verify(second).setRequestProperty(eq("Range"), anyString());
    }

    @Test public void malformedRangeAndChangedVersionRestartWithoutMixingBytes() throws Exception {
        for (String failure : new String[]{"offset", "etag", "total", "malformed", "length", "compressed"}) {
            prepare();
            int split = png.length / 2;
            HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, split, false));
            HttpsURLConnection invalid = range(png, split, "\"v1\"");
            if (failure.equals("etag")) when(invalid.getHeaderField("ETag")).thenReturn("\"v2\"");
            else if (failure.equals("length")) when(invalid.getContentLengthLong()).thenReturn(1L);
            else if (failure.equals("compressed")) when(invalid.getContentEncoding()).thenReturn("gzip");
            else when(invalid.getHeaderField("Content-Range")).thenReturn(failure.equals("malformed") ? "garbage"
                    : "bytes " + (failure.equals("offset") ? split + 1 : split) + "-" + (png.length - 1) + "/" + (failure.equals("total") ? png.length + 1 : png.length));
            HttpsURLConnection complete = full(png, "\"v1\"", new ByteArrayInputStream(png));
            doReturn(first, invalid, complete).when(service).connect(any(URL.class), anyInt());
            assertImage(png, run());
            verify(complete, never()).setRequestProperty(eq("Range"), anyString());
            verify(service, times(3)).connect(any(URL.class), anyInt());
        }
    }

    @Test public void unknownInitialLengthCanResumeWhenVersionMatches() throws Exception {
        int split = png.length / 2;
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, split, false));
        when(first.getContentLengthLong()).thenReturn(-1L);
        HttpsURLConnection rest = range(png, split, "\"v1\"");
        doReturn(first, rest).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(rest).setRequestProperty("Range", "bytes=" + split + "-");
    }

    @Test public void absentOrWeakValidatorRestartsInsteadOfAppendingUnverifiedBytes() throws Exception {
        for (String etag : new String[]{null, "W/\"v1\"", "\"v\"1\""}) {
            prepare();
            HttpsURLConnection first = full(png, etag, interrupted(png, png.length / 2, false));
            HttpsURLConnection second = full(png, etag, new ByteArrayInputStream(png));
            doReturn(first, second).when(service).connect(any(URL.class), anyInt());
            assertImage(png, run());
            verify(second, never()).setRequestProperty(eq("Range"), anyString());
        }
    }

    @Test public void strongLastModifiedCanValidateResumeWhenNoEtagExists() throws Exception {
        int split = png.length / 2;
        HttpsURLConnection first = full(png, null, interrupted(png, split, false));
        HttpsURLConnection rest = range(png, split, null);
        String modified = "Wed, 01 Jan 2020 00:00:00 GMT";
        for (HttpsURLConnection conn : new HttpsURLConnection[]{first, rest}) {
            when(conn.getHeaderField("Last-Modified")).thenReturn(modified);
            when(conn.getHeaderFieldDate("Last-Modified", -1)).thenReturn(1577836800000L);
            when(conn.getHeaderFieldDate("Date", -1)).thenReturn(1577836920000L);
        }
        doReturn(first, rest).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(rest).setRequestProperty("If-Range", modified);
    }

    @Test public void pausedDownloadPersistsAcrossNewServiceAndDeletedRecordClearsItsPart() throws Exception {
        int split = png.length / 2;
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, split, true));
        doReturn(first).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        assertEquals(split, part(id).length());
        assertTrue(service.hasPending());
        service = worker(id);
        HttpsURLConnection rest = range(png, split, "\"v1\"");
        doReturn(rest).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(rest).setRequestProperty("Range", "bytes=" + split + "-");
        prepare();
        doReturn(full(png, "\"v1\"", interrupted(png, split, true))).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        GenerationRecord record = new GenerationRecord(id, 0, 1, "线路 2", "GPT-2.5", "gpt-image-2.5", "1:1", "原任务",
                false, "", false, GenerationRecord.STATUS_WAITING, "", "", 0);
        GenerationRecord.Repository.saveRecord(context, record);
        GenerationRecord.Repository.deleteRecord(context, id);
        assertFalse(part(id).exists());
        assertFalse(new ImageService(context, id).hasPending());
    }

    @Test public void retriesAreBoundedAndExhaustionRetainsVerifiedPartial() throws Exception {
        int split = png.length / 2;
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, split, false));
        HttpsURLConnection stalled = mock(HttpsURLConnection.class);
        when(stalled.getResponseCode()).thenThrow(new SocketTimeoutException());
        doReturn(first, stalled).when(service).connect(any(URL.class), anyInt());
        IOException error = assertThrows(IOException.class, this::run);
        assertTrue(error.getMessage().contains("自动重连 3 次"));
        assertEquals(Arrays.asList(2000L, 4000L, 8000L), waits);
        verify(service, times(4)).connect(any(URL.class), anyInt());
        assertEquals(split, part(id).length());
        assertTrue(service.hasPending());
    }

    @Test public void temporaryHttpFailuresRetryButPermanentFailureDoesNot() throws Exception {
        HttpsURLConnection busy = mock(HttpsURLConnection.class), limited = mock(HttpsURLConnection.class);
        when(busy.getResponseCode()).thenReturn(503);
        when(limited.getResponseCode()).thenReturn(429);
        doReturn(busy, limited, full(png, "\"v1\"", new ByteArrayInputStream(png))).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        assertEquals(Arrays.asList(2000L, 4000L), waits);
        prepare();
        HttpsURLConnection denied = mock(HttpsURLConnection.class);
        when(denied.getResponseCode()).thenReturn(403);
        doReturn(denied).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        assertTrue(waits.isEmpty());
        verify(service, times(1)).connect(any(URL.class), anyInt());
    }

    @Test public void staleRange416RestartsFullDownload() throws Exception {
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, png.length / 2, false));
        HttpsURLConnection stale = mock(HttpsURLConnection.class);
        when(stale.getResponseCode()).thenReturn(416);
        HttpsURLConnection full = full(png, "\"v1\"", new ByteArrayInputStream(png));
        doReturn(first, stale, full).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(full, never()).setRequestProperty(eq("Range"), anyString());
    }

    @Test public void cancellationDuringRetryDelayDoesNotReconnect() throws Exception {
        HttpsURLConnection busy = mock(HttpsURLConnection.class);
        when(busy.getResponseCode()).thenReturn(503);
        doReturn(busy).when(service).connect(any(URL.class), anyInt());
        doAnswer(call -> { service.cancelActive(); throw new IOException("stopped"); }).when(service).waitForPoll(anyLong());
        assertThrows(IOException.class, this::run);
        verify(service, times(1)).connect(any(URL.class), anyInt());
    }

    @Test public void knownCompleteBodyDoesNotWaitForAnotherReadAt100Percent() throws Exception {
        InputStream body = new InputStream() {
            private boolean sent;
            @Override public int read() { throw new AssertionError("Use buffered reads"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (sent) throw new AssertionError("Complete payload must not wait for another read");
                sent = true;
                System.arraycopy(png, 0, bytes, offset, png.length);
                return png.length;
            }
        };
        doReturn(full(png, "\"v1\"", body)).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        assertTrue(waits.isEmpty());
    }

    @Test public void secondInterruptedRangeResumesFromTheNewOffset() throws Exception {
        int firstCut = png.length / 3, secondCut = png.length * 2 / 3;
        HttpsURLConnection first = full(png, "\"v1\"", interrupted(png, firstCut, false));
        HttpsURLConnection second = range(png, firstCut, "\"v1\"");
        when(second.getInputStream()).thenReturn(interrupted(Arrays.copyOfRange(png, firstCut, png.length), secondCut - firstCut, false));
        HttpsURLConnection third = range(png, secondCut, "\"v1\"");
        doReturn(first, second, third).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(second).setRequestProperty("Range", "bytes=" + firstCut + "-");
        verify(third).setRequestProperty("Range", "bytes=" + secondCut + "-");
        assertEquals(Arrays.asList(2000L, 4000L), waits);
    }

    @Test public void weakEtagOrUnreliableModifiedDateCannotAuthorizeResume() throws Exception {
        for (String failure : new String[]{"weak-etag", "recent-date", "missing-date"}) {
            prepare();
            HttpsURLConnection first = full(png, failure.equals("weak-etag") ? "W/\"v1\"" : null, interrupted(png, png.length / 2, false));
            when(first.getHeaderField("Last-Modified")).thenReturn("Wed, 01 Jan 2020 00:00:00 GMT");
            when(first.getHeaderFieldDate("Last-Modified", -1)).thenReturn(1577836800000L);
            when(first.getHeaderFieldDate("Date", -1)).thenReturn(failure.equals("missing-date") ? -1L
                    : 1577836800000L + (failure.equals("recent-date") ? 1000L : 120000L));
            HttpsURLConnection second = full(png, "\"v1\"", new ByteArrayInputStream(png));
            doReturn(first, second).when(service).connect(any(URL.class), anyInt());
            assertImage(png, run());
            verify(second, never()).setRequestProperty(eq("Range"), anyString());
        }
    }

    @Test public void concurrentTaskCheckpointsDoNotOverwriteOrDeleteEachOther() throws Exception {
        int split = png.length / 2;
        String firstId = id;
        doReturn(full(png, "\"v1\"", interrupted(png, split, true))).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        prepare();
        String secondId = id;
        doReturn(full(png, "\"v1\"", interrupted(png, split, true))).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        assertEquals(split, part(firstId).length());
        assertEquals(split, part(secondId).length());
        service = worker(firstId);
        HttpsURLConnection rest = range(png, split, "\"v1\"");
        doReturn(rest).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        assertFalse(part(firstId).exists());
        assertEquals(split, part(secondId).length());
        assertTrue(new ImageService(context, secondId).hasPending());
        new ImageService(context, secondId).forget();
        assertFalse(part(secondId).exists());
    }

    @Test public void completedDownloadSurvivesLocalSaveFailureWithoutAnotherRequest() throws Exception {
        File images = new File(context.getFilesDir(), "images");
        assertTrue(images.createNewFile());
        doReturn(full(png, "\"v1\"", new ByteArrayInputStream(png))).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        assertEquals(png.length, part(id).length());
        assertTrue(service.hasPending());
        assertTrue(images.delete());
        service = worker(id);
        assertImage(png, run());
        verify(service, never()).connect(any(URL.class), anyInt());
    }

    @Test public void changedSavedAddressDiscardsOldCheckpoint() throws Exception {
        int split = png.length / 2;
        doReturn(full(png, "\"v1\"", interrupted(png, split, true))).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, this::run);
        String changed = "https://example.test/replaced.png";
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit().putString("url", changed).commit();
        service = worker(id);
        HttpsURLConnection fresh = full(png, "\"v2\"", new ByteArrayInputStream(png));
        doReturn(fresh).when(service).connect(any(URL.class), anyInt());
        assertImage(png, run());
        verify(fresh, never()).setRequestProperty(eq("Range"), anyString());
        verify(service).connect(argThat(url -> url.toString().equals(changed)), anyInt());
    }

    private ImageService worker(String recordId) throws Exception {
        ImageService result = spy(new ImageService(context, recordId));
        doReturn(true).when(result).isDirectMode();
        doAnswer(call -> { waits.add(call.getArgument(0)); return null; }).when(result).waitForPoll(anyLong());
        doAnswer(call -> { throw new AssertionError("Unexpected request: " + call.getArgument(0)); }).when(result).connect(any(URL.class), anyInt());
        return result;
    }

    private String run() throws Exception { return service.run("原任务", "gpt-image-2.5", "1:1", null, false); }

    private void assertImage(byte[] expected, String imageId) throws IOException {
        assertArrayEquals(expected, Files.readAllBytes(new File(new File(context.getFilesDir(), "images"), imageId).toPath()));
        assertFalse(service.hasPending());
    }

    private File part(String recordId) {
        String name = ImageService.PENDING_PREFS + "-" + recordId;
        return new File(new File(context.getFilesDir(), "image-downloads"), UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)) + ".part");
    }

    private HttpsURLConnection full(byte[] bytes, String etag, InputStream body) throws Exception {
        HttpsURLConnection conn = mock(HttpsURLConnection.class);
        when(conn.getResponseCode()).thenReturn(200);
        when(conn.getContentLengthLong()).thenReturn((long) bytes.length);
        when(conn.getHeaderField("ETag")).thenReturn(etag);
        when(conn.getInputStream()).thenReturn(body);
        return conn;
    }

    private HttpsURLConnection range(byte[] bytes, int offset, String etag) throws Exception {
        HttpsURLConnection conn = full(Arrays.copyOfRange(bytes, offset, bytes.length), etag,
                new ByteArrayInputStream(Arrays.copyOfRange(bytes, offset, bytes.length)));
        when(conn.getResponseCode()).thenReturn(206);
        when(conn.getHeaderField("Content-Range")).thenReturn("bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
        return conn;
    }

    private InputStream interrupted(byte[] bytes, int split, boolean cancel) {
        return new InputStream() {
            private boolean sent;
            @Override public int read() { throw new AssertionError("Use buffered reads"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (sent) throw new SocketTimeoutException("simulated read stall");
                sent = true;
                System.arraycopy(bytes, 0, buffer, offset, split);
                if (cancel) service.cancelActive();
                return split;
            }
        };
    }

    private byte[] png(int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }
}
