package com.aura.notification.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds {@code {name}} markers in a template. */
final class Placeholders {

    private static final Pattern PATTERN = Pattern.compile("\\{([a-zA-Z]+)}");

    private Placeholders() {
    }

    static List<String> in(String template) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    static String replace(String template, String name, String value) {
        return template.replace("{" + name + "}", value);
    }
}
