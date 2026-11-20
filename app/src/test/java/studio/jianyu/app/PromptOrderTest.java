package studio.jianyu.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PromptOrderTest {
    @Test public void enteringAgainChangesFirstExampleWithoutLosingOrDuplicatingItems() {
        List<String> source = Arrays.asList("海边", "山林", "城市", "人物", "插画", "海报");
        PromptOrder<String> order = new PromptOrder<>(new Random(9));
        String previous = null;
        for (int i = 0; i < 20; i++) {
            order.reset();
            List<String> current = order.get(source, "全部", () -> source);
            assertEquals(source.size(), current.size());
            assertEquals(new HashSet<>(source), new HashSet<>(current));
            assertNotEquals(previous, current.get(0));
            previous = current.get(0);
        }
        assertEquals(Arrays.asList("海边", "山林", "城市", "人物", "插画", "海报"), source);
    }

    @Test public void pagingAndReturningFromDetailsKeepSameOrderWithoutRefiltering() {
        List<Integer> source = new ArrayList<>();
        for (int i = 0; i < 80; i++) source.add(i);
        AtomicInteger loads = new AtomicInteger();
        PromptOrder<Integer> order = new PromptOrder<>(new Random(5));
        List<Integer> first = order.get(source, "插画", () -> { loads.incrementAndGet(); return source; });
        List<Integer> prefix = new ArrayList<>(first.subList(0, 24));
        List<Integer> second = order.get(source, "插画", () -> { throw new AssertionError("Unexpected refilter"); });
        assertSame(first, second);
        assertEquals(prefix, second.subList(0, 24));
        assertEquals(1, loads.get());
    }

    @Test public void filterAndDataChangesUseOnlyMatchingItems() {
        Object source = new Object();
        PromptOrder<String> order = new PromptOrder<>(new Random(4));
        order.get(source, "全部", () -> Arrays.asList("插画", "人物"));
        assertEquals(Arrays.asList("人物"), order.get(source, "人物", () -> Arrays.asList("人物")));
        assertTrue(order.get(new Object(), "人物", ArrayList::new).isEmpty());
    }
}
