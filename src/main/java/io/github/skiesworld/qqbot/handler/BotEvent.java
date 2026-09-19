package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.event.EventType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method to one or more gateway events.
 *
 * <p>Parameters are filled by type, see {@link HandlerRegistry}. At least one of {@link #value()} and
 * {@link #name()} must be given, otherwise registration fails.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BotEvent {

    /** Modelled event types this method handles. */
    EventType[] value() default {};

    /**
     * Raw {@code t} values, for events newer than this SDK version. Registering by name is how a handler keeps
     * working after {@link EventType#from(String)} starts resolving the name to a real type as well.
     */
    String[] name() default {};
}
