package bank.internalgateway.dsl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-safe holder for compiled benchmark DSL with last-known-good rollback.
 */
public class BenchmarkRouteRegistry {

    private final Path dslDirectory;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicReference<Consumer<CompiledBenchmarkModule>> reloadListener =
            new AtomicReference<>(module -> {
            });

    public BenchmarkRouteRegistry(Path dslDirectory) {
        this.dslDirectory = Objects.requireNonNull(dslDirectory, "dslDirectory");
    }

    public void setReloadListener(Consumer<CompiledBenchmarkModule> listener) {
        reloadListener.set(listener != null ? listener : module -> {
        });
    }

    public CompiledBenchmarkModule loadInitial() throws IOException {
        CompiledBenchmarkModule module = BenchmarkModuleCompiler.compileFromDirectory(dslDirectory);
        Snapshot snapshot = new Snapshot(module, Instant.now(), 1, "loaded");
        current.set(snapshot);
        return module;
    }

    public synchronized ReloadResult reload() {
        Snapshot previous = current.get();
        try {
            CompiledBenchmarkModule module = BenchmarkModuleCompiler.compileFromDirectory(dslDirectory);
            int nextVersion = previous != null ? previous.version() + 1 : 1;
            Snapshot snapshot = new Snapshot(module, Instant.now(), nextVersion, "reloaded");
            current.set(snapshot);
            reloadListener.get().accept(module);
            return ReloadResult.success(snapshot, previous);
        } catch (Exception ex) {
            return ReloadResult.failed(previous, ex.getMessage());
        }
    }

    public CompiledBenchmarkModule currentModule() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Benchmark DSL registry is not initialized");
        }
        return snapshot.module();
    }

    public Snapshot currentSnapshot() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Benchmark DSL registry is not initialized");
        }
        return snapshot;
    }

    public Path dslDirectory() {
        return dslDirectory;
    }

    public record Snapshot(
            CompiledBenchmarkModule module,
            Instant loadedAt,
            int version,
            String status
    ) {
    }

    public record ReloadResult(
            boolean success,
            Snapshot current,
            Snapshot previous,
            String error
    ) {
        public static ReloadResult success(Snapshot current, Snapshot previous) {
            return new ReloadResult(true, current, previous, null);
        }

        public static ReloadResult failed(Snapshot previous, String error) {
            return new ReloadResult(false, previous, previous, error);
        }
    }
}
