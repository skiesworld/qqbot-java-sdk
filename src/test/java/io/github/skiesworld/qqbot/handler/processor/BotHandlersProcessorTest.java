package io.github.skiesworld.qqbot.handler.processor;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.event.EventBus;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.util.Json;
import io.github.skiesworld.qqbot.event.model.C2CMessageCreate;
import io.github.skiesworld.qqbot.handler.On;
import io.github.skiesworld.qqbot.handler.BotHandler;
import io.github.skiesworld.qqbot.handler.BotHandlers;
import io.github.skiesworld.qqbot.handler.HandlerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import okhttp3.OkHttpClient;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the processor the way a consumer's build does: compile real sources with it, then read what came out
 * through the class loader ServiceLoader uses. Nothing here needs the QQ platform.
 */
class BotHandlersProcessorTest {

    private static final String MANIFEST = "META-INF/services/io.github.skiesworld.qqbot.handler.BotHandler";

    @TempDir
    Path work;

    @Test
    void writesAManifestTheLoaderCanUse() throws Exception {
        Result compiled = compile("QualifyingHandlers", """
                package sample;

                import io.github.skiesworld.qqbot.event.EventType;
                import io.github.skiesworld.qqbot.event.QQEvent;
                import io.github.skiesworld.qqbot.event.model.C2CMessageCreate;
                import io.github.skiesworld.qqbot.handler.On;
                import io.github.skiesworld.qqbot.handler.BotHandler;
                import io.github.skiesworld.qqbot.handler.BotHandlers;
                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;

                @BotHandlers
                public class QualifyingHandlers implements BotHandler {

                    public static final List<String> HITS = new CopyOnWriteArrayList<>();

                    @On(EventType.C2C_MESSAGE_CREATE)
                    public void onC2c(C2CMessageCreate msg, QQEvent raw) {
                        HITS.add(msg.content + "/" + raw.id());
                    }
                }
                """);
        assertTrue(compiled.success, compiled.messages());

        List<String> lines = Files.readAllLines(compiled.manifest());
        assertEquals(List.of("sample.QualifyingHandlers"),
                lines.stream().filter(l -> !l.startsWith("#")).toList());

        // the point of the manifest: a loader that only sees the output directory finds and wires the handler
        try (URLClassLoader loader = new URLClassLoader(new URL[]{compiled.output.toUri().toURL()},
                getClass().getClassLoader())) {
            EventBus bus = new EventBus();
            assertEquals(1, new HandlerRegistry(bus).registerDiscovered(loader));
            bus.dispatch(new QQEvent("M9", 0, 1L, "C2C_MESSAGE_CREATE", EventType.C2C_MESSAGE_CREATE,
                    Json.parseLenient("{\"content\":\"compiled\"}")));
            Class<?> loaded = loader.loadClass("sample.QualifyingHandlers");
            assertEquals(List.of("compiled/M9"), loaded.getField("HITS").get(null));
        }
    }

    @Test
    void refusesAClassTheLoaderCouldNotInstantiate() throws Exception {
        Result missingMarker = compile("NoMarker", """
                package sample;

                import io.github.skiesworld.qqbot.event.EventType;
                import io.github.skiesworld.qqbot.event.QQEvent;
                import io.github.skiesworld.qqbot.handler.On;
                import io.github.skiesworld.qqbot.handler.BotHandlers;

                @BotHandlers
                public class NoMarker {

                    @On(EventType.C2C_MESSAGE_CREATE)
                    public void onMessage(QQEvent event) {
                    }
                }
                """);
        assertTrue(missingMarker.messages().contains("must implement"), missingMarker.messages());

        Result noRoutes = compile("NoRoutes", """
                package sample;

                import io.github.skiesworld.qqbot.handler.BotHandler;
                import io.github.skiesworld.qqbot.handler.BotHandlers;

                @BotHandlers
                public class NoRoutes implements BotHandler {

                    public void notAHandler() {
                    }
                }
                """);
        assertTrue(noRoutes.messages().contains("would register nothing"), noRoutes.messages());

        Result nested = compile("Outer", """
                package sample;

                import io.github.skiesworld.qqbot.event.EventType;
                import io.github.skiesworld.qqbot.event.QQEvent;
                import io.github.skiesworld.qqbot.handler.On;
                import io.github.skiesworld.qqbot.handler.BotHandler;
                import io.github.skiesworld.qqbot.handler.BotHandlers;

                public class Outer {

                    @BotHandlers
                    public static class Inside implements BotHandler {

                        @On(EventType.C2C_MESSAGE_CREATE)
                        public void onMessage(QQEvent event) {
                        }
                    }
                }
                """);
        assertTrue(nested.messages().contains("must be top level"), nested.messages());
    }

    /** Compilation outcome plus the paths a consumer's build would hand to the next stage. */
    private record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path output) {

        String messages() {
            return diagnostics.stream().map(d -> d.getMessage(null)).collect(Collectors.joining("\n"));
        }

        Path manifest() {
            return output.resolve(MANIFEST);
        }
    }

    private Result compile(String className, String source) throws IOException, URISyntaxException {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "these tests need a JDK, not a JRE");
        Path output = Files.createDirectories(work.resolve("out-" + className));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        String classpath = classpath();
        List<String> options = List.of(
                "-classpath", classpath,
                "-processorpath", classpath,
                "-processor", BotHandlersProcessor.class.getName(),
                "-d", output.toString());
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        boolean success = compiler.getTask(null, null, diagnostics, options, null, List.of(unit)).call();
        return new Result(success, diagnostics.getDiagnostics(), output);
    }

    /**
     * The locations the SDK and its dependencies were loaded from. Assembled from the classes the generated
     * sources touch rather than from {@code java.class.path}, which a build tool is free to shorten.
     */
    private static String classpath() throws URISyntaxException {
        Set<Path> entries = new LinkedHashSet<>();
        Class<?>[] needed = {HandlerRegistry.class, BotHandler.class, BotHandlers.class, On.class,
                EventType.class, QQEvent.class, C2CMessageCreate.class, JsonObject.class, OkHttpClient.class,
                LoggerFactory.class};
        for (Class<?> type : needed) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        }
        return entries.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
