package network.vonix.threadedhorizons.platform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderHooksVersionResolutionTest {

    @Test
    void emptyPopulatedLookupUsesEarlyLoadingFallback() throws Exception {
        assertEquals(
                Optional.of("2.7.0"),
                resolve(
                        "iobvariantloader",
                        Optional::empty,
                        () -> Optional.of("2.7.0")));
    }

    @Test
    void latePopulatedLookupWinsAfterAnEarlyEmptyObservation() throws Exception {
        AtomicReference<Optional<String>> populated = new AtomicReference<>(Optional.empty());
        Supplier<Optional<String>> populatedLookup = populated::get;
        Supplier<Optional<String>> earlyLookup = Optional::empty;

        assertTrue(resolve("isleofberk", populatedLookup, earlyLookup).isEmpty());
        populated.set(Optional.of("1.2.0"));
        assertEquals(
                Optional.of("1.2.0"),
                resolve("isleofberk", populatedLookup, earlyLookup));
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> resolve(
            String id,
            Supplier<Optional<String>> populatedLookup,
            Supplier<Optional<String>> earlyLookup) throws Exception {
        Method method = LoaderHooks.class.getDeclaredMethod(
                "resolveModVersion", String.class, Supplier.class, Supplier.class);
        method.setAccessible(true);
        return (Optional<String>) method.invoke(null, id, populatedLookup, earlyLookup);
    }
}
