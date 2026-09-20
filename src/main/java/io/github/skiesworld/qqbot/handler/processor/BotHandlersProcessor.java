package io.github.skiesworld.qqbot.handler.processor;

import io.github.skiesworld.qqbot.handler.BotHandler;
import io.github.skiesworld.qqbot.handler.BotHandlers;
import io.github.skiesworld.qqbot.handler.On;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the {@link BotHandler} service manifest for the {@link BotHandlers} classes it sees, which is what lets
 * {@link java.util.ServiceLoader} — and therefore {@code bot.handlers().registerDiscovered()} — find them.
 *
 * <p>Completely optional: nothing in the SDK requires it, and a handler passed to {@code register(obj)} needs no
 * manifest at all. It exists because a runtime class-path scan is not something a mod loader or a shaded jar can be
 * trusted with, so discovery goes through the standard loader instead and this processor keeps the manifest that
 * loader reads in step with the sources.
 *
 * <p>A class qualifies only if the loader can actually instantiate it: top level, public, implementing the marker
 * interface, a usable no-arg constructor, and at least one {@link On} method. Anything else is reported where
 * it was written, at compile time, instead of going missing at runtime.
 */
@SupportedAnnotationTypes("io.github.skiesworld.qqbot.handler.BotHandlers")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class BotHandlersProcessor extends AbstractProcessor {

    private static final String MANIFEST = "META-INF/services/io.github.skiesworld.qqbot.handler.BotHandler";
    private static final String ON = "io.github.skiesworld.qqbot.handler.On";

    /** Accumulated across rounds; a later round sees fewer elements and must not drop the earlier ones. */
    private final Set<String> collected = new TreeSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            for (Element element : roundEnv.getElementsAnnotatedWith(BotHandlers.class)) {
                TypeElement type = (TypeElement) element;
                if (check(type)) {
                    collected.add(type.getQualifiedName().toString());
                }
            }
            return false;
        }
        if (!collected.isEmpty()) {
            emit();
        }
        return !collected.isEmpty();
    }

    private boolean check(TypeElement type) {
        String name = type.getQualifiedName().toString();
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            return error(type, "@BotHandlers class " + name + " must be top level;"
                    + " ServiceLoader cannot instantiate a nested or local class");
        }
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            return error(type, "@BotHandlers class " + name + " must be public");
        }
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(BotHandler.class.getName());
        if (marker == null) {
            return error(type, "@BotHandlers is processed without the SDK on the compile classpath");
        }
        if (!processingEnv.getTypeUtils().isAssignable(type.asType(), marker.asType())) {
            return error(type, name + " must implement " + BotHandler.class.getName() + ", a marker interface with"
                    + " no methods, to be discovered; otherwise register the instance with"
                    + " bot.handlers().register(...) and no manifest is needed");
        }
        if (!hasUsableConstructor(type)) {
            return error(type, name + " needs a public no-arg constructor for ServiceLoader to instantiate it");
        }
        if (!hasRoutedMethod(type)) {
            return error(type, name + " has no @On methods, so discovering it would register nothing");
        }
        return true;
    }

    private boolean hasUsableConstructor(TypeElement type) {
        boolean declared = false;
        for (Element m : type.getEnclosedElements()) {
            if (m.getKind() == ElementKind.CONSTRUCTOR) {
                declared = true;
                if (m instanceof ExecutableElement ctor && m.getModifiers().contains(Modifier.PUBLIC)
                        && ctor.getParameters().isEmpty()) {
                    return true;
                }
            }
        }
        return !declared;
    }

    private boolean hasRoutedMethod(TypeElement type) {
        for (Element m : type.getEnclosedElements()) {
            if (m.getKind() == ElementKind.METHOD && hasAnnotation(m, ON)) {
                return true;
            }
        }
        TypeMirror superclass = type.getSuperclass();
        // inherited routes are registered as well, so a superclass handler still counts; asElement() answers
        // null for anything that is not a declared type, so no kind check is needed
        Element parent = processingEnv.getTypeUtils().asElement(superclass);
        if (parent instanceof TypeElement parentType
                && !parentType.getQualifiedName().contentEquals("java.lang.Object")) {
            return hasRoutedMethod(parentType);
        }
        return false;
    }

    private static boolean hasAnnotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement t
                    && t.getQualifiedName().contentEquals(qualifiedName)) {
                return true;
            }
        }
        return false;
    }

    /** Written once, after the last round: the filer rejects a second create for the same resource path. */
    private void emit() {
        try {
            FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    MANIFEST);
            try (Writer out = resource.openWriter()) {
                out.write("# Generated by " + BotHandlersProcessor.class.getName() + ". Do not edit.\n");
                for (String name : collected) {
                    out.write(name);
                    out.write('\n');
                }
            }
        } catch (IOException | RuntimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "could not write " + MANIFEST + ": " + e);
        }
    }

    private boolean error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
        return false;
    }
}
