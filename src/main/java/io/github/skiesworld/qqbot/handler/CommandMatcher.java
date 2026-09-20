package io.github.skiesworld.qqbot.handler;

import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.QQMessageEvent;
import io.github.skiesworld.qqbot.util.Strings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One {@code @On(command = ...)} declaration: its words or compiled patterns, the prefixes in force for it, and
 * the {@link OnContext} a matching message produces. No match is null, which is what keeps the method from running.
 */
final class CommandMatcher {

    private final String name;
    private final List<String> words;
    private final List<Pattern> patterns;
    private final List<String> prefixes;
    private final String description;

    CommandMatcher(On on, String name, List<String> registryPrefixes) {
        this.name = name;
        List<String> declared = new ArrayList<>(Arrays.asList(on.command()));
        declared.removeIf(String::isEmpty);
        this.words = List.copyOf(declared);
        this.patterns = new ArrayList<>();
        if (on.kind() == On.Kind.REGEX) {
            for (String source : this.words) {
                try {
                    patterns.add(Pattern.compile(source));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(name + " has a command pattern that does not compile: \""
                            + source + "\"", e);
                }
            }
        }
        if (this.words.isEmpty()) {
            throw new IllegalArgumentException(name + " has empty command()");
        }
        this.prefixes = on.prefix().length == 0 ? registryPrefixes : List.of(on.prefix());
        this.description = on.description();
    }

    /** The match for {@code event}, or null when its text is not this command. */
    OnContext context(QQEvent event) {
        if (!(event instanceof QQMessageEvent message)) {
            return null;
        }
        String text = message.content();
        if (text == null) {
            return null;
        }
        String stripped = stripPrefix(text.trim());
        if (stripped == null) {
            return null;
        }
        return patterns.isEmpty() ? wordMatch(message, stripped) : regexMatch(message, stripped);
    }

    private OnContext wordMatch(QQMessageEvent message, String stripped) {
        for (String word : words) {
            if (stripped.equals(word)) {
                return new OnContext(message, word, stripped, "", List.of());
            }
            if (stripped.length() > word.length() && stripped.startsWith(word)
                    && Character.isWhitespace(stripped.charAt(word.length()))) {
                return new OnContext(message, word, stripped, stripped.substring(word.length()).trim(), List.of());
            }
        }
        return null;
    }

    private OnContext regexMatch(QQMessageEvent message, String stripped) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(stripped);
            if (matcher.matches()) {
                List<String> groups = new ArrayList<>();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    groups.add(matcher.group(i));
                }
                return new OnContext(message, pattern.pattern(), stripped, stripped, groups);
            }
        }
        return null;
    }

    private String stripPrefix(String text) {
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) {
                return text;
            }
            if (text.startsWith(prefix) && text.length() > prefix.length()) {
                return text.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** One line for {@link HandlerRegistry#describe()}: the words, the prefix if it needs one, the description. */
    String describe() {
        StringBuilder out = new StringBuilder();
        if (!patterns.isEmpty()) {
            out.append(patterns.get(0).pattern());
            if (patterns.size() > 1) {
                out.append(" (+").append(patterns.size() - 1).append(" more)");
            }
        } else {
            out.append(String.join(" | ", words));
        }
        if (!prefixes.get(0).isEmpty()) {
            out.append(" [prefix ").append(prefixes.get(0)).append(']');
        }
        if (Strings.isNotBlank(description)) {
            out.append(" - ").append(description);
        }
        return out.toString();
    }
}
