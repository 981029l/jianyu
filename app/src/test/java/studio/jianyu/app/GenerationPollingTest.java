package studio.jianyu.app;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenerationPollingTest {
    @Test public void intervalGrowsAndStopsAtDeadline() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        GenerationPolling polling = new GenerationPolling(clock::get);
        for (long expected : new long[]{2500, 5000, 10000, 20000, 30000, 30000}) {
            assertEquals(expected, polling.nextDelayMillis());
            clock.addAndGet(expected);
        }
        clock.set(100 + GenerationPolling.MAX_POLLING_MS - 1000);
        assertEquals(1000, polling.nextDelayMillis());
        assertEquals(1000, polling.queryTimeoutMillis());
        clock.addAndGet(1000);
        assertThrows(IOException.class, polling::nextDelayMillis);
        assertThrows(IOException.class, polling::queryTimeoutMillis);
        assertEquals(2500, new GenerationPolling(clock::get).nextDelayMillis());
    }
}
