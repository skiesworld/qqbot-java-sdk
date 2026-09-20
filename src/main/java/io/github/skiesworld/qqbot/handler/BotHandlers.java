package io.github.skiesworld.qqbot.handler;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that collects {@link On} methods.
 *
 * <p>The annotation is what the optional {@code BotHandlersProcessor} looks for when it writes the
 * {@link BotHandler} service manifest; at runtime the registry only needs the methods, so registering an
 * instance directly works with or without the processor on the compile classpath.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BotHandlers {

    /** Name used in log and error messages; defaults to the simple class name. */
    String value() default "";
}
