package studio.jianyu.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

final class PromptOrder<T> {
    private final Random random;
    private Object source;
    private String filter;
    private List<T> ordered = Collections.emptyList();
    private T previousFirst;

    PromptOrder() { this(new Random()); }
    PromptOrder(Random random) { this.random = random; }

    void reset() { source = null; filter = null; }

    List<T> get(Object data, String key, Supplier<List<T>> loader) {
        if (source != data || !Objects.equals(filter, key)) {
            ordered = new ArrayList<>(loader.get());
            Collections.shuffle(ordered, random);
            if (ordered.size() > 1 && Objects.equals(ordered.get(0), previousFirst)) Collections.rotate(ordered, 1);
            previousFirst = ordered.isEmpty() ? null : ordered.get(0);
            source = data;
            filter = key;
        }
        return ordered;
    }
}
