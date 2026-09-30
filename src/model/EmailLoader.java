package model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads mailbox_test_dataset.json (our simulated inbox) without external libraries.
 *
 * Only sender, subject and body become an Email: "expectedAgent" is the correct answer
 * for the experiments, so it is NEVER given to the router (it is read separately).
 */
public class EmailLoader {

    public static List<Email> load(String path) throws IOException {
        List<Email> emails = new ArrayList<>();
        for (String object : splitObjects(Files.readString(Path.of(path)))) {
            emails.add(new Email(field(object, "sender"), field(object, "subject"), field(object, "body")));
        }
        return emails;
    }

    /** The expected agent of each email, in the same order as load(). Used only by the evaluation. */
    public static List<String> loadExpectedAgents(String path) throws IOException {
        List<String> expected = new ArrayList<>();
        for (String object : splitObjects(Files.readString(Path.of(path)))) {
            expected.add(field(object, "expectedAgent"));
        }
        return expected;
    }

    /** Cuts the JSON array into its objects {...}, ignoring braces written inside strings. */
    private static List<String> splitObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;                  // skip the escaped character
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) objects.add(json.substring(start, i + 1));
            }
        }
        return objects;
    }

    /** Value of a text field like "subject": "...", or "" if the field does not exist. */
    private static String field(String object, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(object);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> { }
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
