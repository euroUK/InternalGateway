package bank.internalgateway.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private PathTemplate() {
    }

    public static boolean matches(String template, String path) {
        return extract(template, path) != null;
    }

    public static String expand(String template, Map<String, String> values) {
        if (template == null) {
            throw new IllegalArgumentException("Path template is null");
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder expanded = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            expanded.append(template, last, matcher.start());
            String name = matcher.group(1);
            String value = values != null ? values.get(name) : null;
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing path variable: " + name);
            }
            expanded.append(value);
            last = matcher.end();
        }
        expanded.append(template.substring(last));
        return expanded.toString();
    }

    public static Map<String, String> extract(String template, String path) {
        if (template == null || path == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = VARIABLE.matcher(template);
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(last, matcher.start())));
            names.add(matcher.group(1));
            regex.append("([^/]+)");
            last = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        regex.append("$");

        Matcher pathMatcher = Pattern.compile(regex.toString()).matcher(path);
        if (!pathMatcher.matches()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            values.put(names.get(i), pathMatcher.group(i + 1));
        }
        return values;
    }
}
