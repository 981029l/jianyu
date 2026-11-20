package studio.jianyu.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class UpdateManagerTest {
    private Context context;
    private UpdateManager.UpdateInfo info;
    private PackageManager packages;
    private PackageInfo archive;

    @Before public void prepare() throws Exception {
        context = spy(RuntimeEnvironment.getApplication());
        packages = mock(PackageManager.class);
        doReturn(packages).when(context).getPackageManager();
        info = new UpdateManager.UpdateInfo(true, BuildConfig.VERSION_CODE + 1, "9.0.0", BuildConfig.VERSION_NAME,
                "更新", "https://github.com/example/project/releases/download/v9.0.0/app.apk");
        PackageInfo installed = new PackageInfo();
        installed.signatures = new Signature[]{new Signature("aabb")};
        archive = new PackageInfo();
        archive.packageName = context.getPackageName();
        archive.versionCode = info.latestCode;
        archive.versionName = info.latestVersion;
        archive.signatures = installed.signatures;
        when(packages.getPackageInfo(anyString(), anyInt())).thenReturn(installed);
        when(packages.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archive);
    }

    @Test public void acceleratedChannelComesFirstAndFailedChannelFallsBack() throws Exception {
        List<URL> calls = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();
        HttpsURLConnection bad = response(503, new ByteArrayInputStream(new byte[0]), 0);
        HttpsURLConnection good = response(200, new ByteArrayInputStream(new byte[2048]), 2048);
        try (MockedStatic<UpdateManager> mocked = mockStatic(UpdateManager.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> UpdateManager.open(any(URL.class), anyInt(), anyInt(), anyBoolean())).thenAnswer(call -> {
                URL url = call.getArgument(0);
                calls.add(url);
                return calls.size() == 1 ? bad : good;
            });
            File apk = UpdateManager.downloadApk(context, info, progress::add);
            assertTrue(calls.get(0).toString().startsWith("https://ghfast.top/https://github.com/"));
            assertEquals(info.downloadUrl, calls.get(1).toString());
            assertEquals(2048, apk.length());
            assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
            assertEquals(1, progress.stream().filter(value -> value == 100).count());
        }
    }

    @Test public void slowContinuousTransferHasDeadlineAndFallsBack() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        InputStream slow = new InputStream() {
            @Override public int read() { throw new AssertionError(); }
            @Override public int read(byte[] bytes) {
                clock.addAndGet(70000);
                bytes[0] = 1;
                return 1;
            }
        };
        HttpsURLConnection first = response(200, slow, -1);
        HttpsURLConnection second = response(200, new ByteArrayInputStream(new byte[2048]), 2048);
        List<URL> calls = new ArrayList<>();
        try (MockedStatic<UpdateManager> mocked = mockStatic(UpdateManager.class, CALLS_REAL_METHODS)) {
            mocked.when(UpdateManager::nowMillis).thenAnswer(call -> clock.get());
            mocked.when(() -> UpdateManager.open(any(URL.class), anyInt(), anyInt(), anyBoolean())).thenAnswer(call -> {
                calls.add(call.getArgument(0));
                return calls.size() == 1 ? first : second;
            });
            assertEquals(2048, UpdateManager.downloadApk(context, info, percent -> {}).length());
            assertEquals(2, calls.size());
            verify(first).disconnect();
        }
    }

    @Test public void truncatedTransferFailsBeforeReportingSuccess() throws Exception {
        HttpsURLConnection conn = response(200, new ByteArrayInputStream(new byte[1024]), 2048);
        List<Integer> progress = new ArrayList<>();
        File output = File.createTempFile("truncated-", ".apk", context.getCacheDir());
        try (MockedStatic<UpdateManager> mocked = mockStatic(UpdateManager.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> UpdateManager.open(any(URL.class), anyInt(), anyInt(), anyBoolean())).thenReturn(conn);
            assertThrows(IOException.class, () -> UpdateManager.downloadToFile(info.downloadUrl, output, progress::add));
            assertFalse(progress.contains(100));
        } finally { output.delete(); }
    }

    @Test public void modifiedSignatureWrongPackageAndWrongVersionAreRejected() throws Exception {
        File output = File.createTempFile("signed-", ".apk", context.getCacheDir());
        try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(new byte[2048]); }
        try {
            assertTrue(UpdateManager.isValidApkFile(context, output, info));
            archive.signatures = new Signature[]{new Signature("ccdd")};
            assertFalse(UpdateManager.isValidApkFile(context, output, info));
            archive.signatures = new Signature[]{new Signature("aabb")};
            archive.packageName = "other.package";
            assertFalse(UpdateManager.isValidApkFile(context, output, info));
            archive.packageName = context.getPackageName();
            archive.versionCode = BuildConfig.VERSION_CODE;
            assertFalse(UpdateManager.isValidApkFile(context, output, info));
        } finally { output.delete(); }
    }

    @Test public void otherDownloadHostsAreNotSentToGithubMirror() throws Exception {
        assertArrayEquals(new String[]{"https://downloads.example.test/app.apk"},
                UpdateManager.downloadCandidates("https://downloads.example.test/app.apk"));
        assertThrows(IOException.class, () -> UpdateManager.downloadCandidates("https://user:secret@example.test/app.apk"));
    }

    private HttpsURLConnection response(int status, InputStream input, long length) throws Exception {
        HttpsURLConnection conn = mock(HttpsURLConnection.class);
        when(conn.getResponseCode()).thenReturn(status);
        when(conn.getInputStream()).thenReturn(input);
        when(conn.getContentLengthLong()).thenReturn(length);
        return conn;
    }
}
