import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Seal build helper: prints a per-module surefire summary and one line per
 * skipped test with its reason, so the skip report is the last output of the
 * test run. Run from the repository root: java .github/seal/SkipReport.java
 */
public class SkipReport {

    static final Pattern DISABLED = Pattern.compile("@Disabled\\s*(\\((.*)\\))?");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        List<Path> reports;
        try (Stream<Path> s = Files.walk(root)) {
            reports = s.filter(p -> p.getFileName().toString().startsWith("TEST-")
                    && p.getFileName().toString().endsWith(".xml")
                    && p.getParent() != null
                    && p.getParent().getFileName().toString().equals("surefire-reports"))
                    .sorted().collect(Collectors.toList());
        }
        Map<String, int[]> modules = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        for (Path report : reports) {
            Path moduleDir = report.getParent().getParent().getParent();
            String module = root.relativize(moduleDir).toString().replace('\\', '/');
            int[] counts = modules.computeIfAbsent(module, k -> new int[4]);
            Document doc = dbf.newDocumentBuilder().parse(report.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                counts[0]++;
                if (tc.getElementsByTagName("failure").getLength() > 0) {
                    counts[2]++;
                } else if (tc.getElementsByTagName("error").getLength() > 0) {
                    counts[3]++;
                } else if (tc.getElementsByTagName("skipped").getLength() > 0) {
                    counts[1]++;
                    Element sk = (Element) tc.getElementsByTagName("skipped").item(0);
                    String cls = tc.getAttribute("classname");
                    String name = tc.getAttribute("name");
                    String reason = reasonFor(moduleDir, cls, name, sk.getAttribute("message"));
                    skipped.add("SKIPPED " + module + " " + cls + "#" + name + " -- " + reason);
                }
            }
        }
        int[] total = new int[4];
        System.out.println("==== SEAL TEST REPORT (surefire XML) ====");
        for (Map.Entry<String, int[]> e : modules.entrySet()) {
            int[] c = e.getValue();
            for (int i = 0; i < 4; i++) total[i] += c[i];
            System.out.println("MODULE " + e.getKey() + ": tests=" + c[0] + " passed=" + (c[0] - c[1] - c[2] - c[3])
                    + " skipped=" + c[1] + " failures=" + c[2] + " errors=" + c[3]);
        }
        skipped.forEach(System.out::println);
        System.out.println("TOTAL: tests=" + total[0] + " passed=" + (total[0] - total[1] - total[2] - total[3])
                + " skipped=" + total[1] + " failures=" + total[2] + " errors=" + total[3]);
    }

    static String reasonFor(Path moduleDir, String cls, String method, String message) {
        String fromSource = null;
        try {
            fromSource = sourceReason(moduleDir, cls, method);
        } catch (IOException ignored) {
            // fall through to surefire message
        }
        String msg = message == null ? "" : message.trim();
        if (fromSource != null && !fromSource.isEmpty()) {
            return msg.isEmpty() ? fromSource : msg + " | " + fromSource;
        }
        return msg.isEmpty() ? "skipped by the test runner (no reason recorded)" : msg;
    }

    static String sourceReason(Path moduleDir, String cls, String method) throws IOException {
        String outer = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
        Path src = moduleDir.resolve("src/test/java").resolve(outer.replace('.', '/') + ".java");
        if (!Files.exists(src)) return null;
        List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
        String bare = method.replaceAll("[\\(\\[].*$", "").trim();
        Pattern decl = Pattern.compile("\\b(void|public|private|protected)\\b.*\\b" + Pattern.quote(bare) + "\\s*\\(");
        for (int i = 0; i < lines.size(); i++) {
            if (!decl.matcher(lines.get(i)).find()) continue;
            // annotations and comments directly above the method
            StringBuilder sb = new StringBuilder();
            for (int j = i - 1; j >= Math.max(0, i - 8); j--) {
                String l = lines.get(j).trim();
                if (l.endsWith("}") || l.endsWith(";")) break;
                Matcher m = DISABLED.matcher(l);
                if (m.find()) sb.insert(0, "@Disabled" + (m.group(2) != null ? " " + m.group(2) : "") + " ");
                else if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*")) sb.insert(0, l.replaceAll("^[/*\\s]+", "") + " ");
            }
            // assumptions inside the method body
            for (int j = i + 1; j < Math.min(lines.size(), i + 25); j++) {
                String l = lines.get(j).trim();
                if (l.contains("assume") || l.contains("Assume")) {
                    sb.append("assumption not met: ").append(l).append(' ');
                    break;
                }
                if (l.startsWith("@Test")) break;
            }
            if (sb.length() > 0) return sb.toString().trim();
            break;
        }
        // class-level @Disabled
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("public class") || t.startsWith("class ")) break;
            Matcher m = DISABLED.matcher(t);
            if (m.find()) return "class-level @Disabled" + (m.group(2) != null ? " " + m.group(2) : "");
        }
        return null;
    }
}
