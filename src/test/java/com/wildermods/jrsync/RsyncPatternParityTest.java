package com.wildermods.jrsync;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wildermods.jrsync.RSyncPattern.RegexBackedPattern;

import org.junit.jupiter.api.Assertions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class RsyncPatternParityTest {

    private static final Path TEST_ROOT = Paths.get("src/test/resources/testData").toAbsolutePath();
    private static final Path EXCLUDE_DATA_ROOT = Paths.get("src/test/resources/excludeTestData").toAbsolutePath();

    static Stream<String> providePatterns() throws IOException {
        List<String> patterns = new ArrayList<>();

        Files.walk(EXCLUDE_DATA_ROOT)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try (BufferedReader reader = Files.newBufferedReader(path)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                            patterns.add(line);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read pattern file: " + path, e);
                    }
                });

        return patterns.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providePatterns")
    void testPatternParity(String pattern) throws Exception {
        Path rsyncTempDir = Files.createTempDirectory("rsyncCopy");
        Path javaTempDir = Files.createTempDirectory("javaCopy");

        try {
            // Copy with rsync using pattern as exclusion
            runRsyncCopy(pattern, rsyncTempDir);

            // Copy with Java using your Pattern class
            RegexBackedPattern javapattern = (RegexBackedPattern) copyWithJava(pattern, javaTempDir);

            // Compare directory structures
            List<String> rsyncFiles = listAllRelativeFiles(rsyncTempDir);
            List<String> javaFiles = listAllRelativeFiles(javaTempDir);

            Collections.sort(rsyncFiles);
            Collections.sort(javaFiles);

            Assertions.assertEquals(rsyncFiles, javaFiles,
                    "Directory mismatch for pattern: " + pattern + " regex: " + javapattern.regexPattern + " ");
        } finally {
            deleteRecursively(rsyncTempDir);
            deleteRecursively(javaTempDir);
        }
    }

    private void runRsyncCopy(String excludePattern, Path dest) throws IOException, InterruptedException {
        Path tempExclude = Files.createTempFile("exclude", ".txt");
        Files.write(tempExclude, Collections.singleton(excludePattern), StandardOpenOption.TRUNCATE_EXISTING);

        ProcessBuilder pb = new ProcessBuilder(
                "rsync",
                "-a",
                "--exclude-from=" + tempExclude.toAbsolutePath(),
                TEST_ROOT.toAbsolutePath() + "/",
                dest.toAbsolutePath().toString() + "/"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            reader.lines().forEach(System.out::println); // optional: debug output
        }

        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("rsync failed with exit code " + exit);
        }

        Files.deleteIfExists(tempExclude);
    }

    private RSyncPattern copyWithJava(String pattern, Path dest) throws IOException {
        RegexBackedPattern javaPattern = (RegexBackedPattern) RSyncPattern.compile(pattern);

        Files.walk(TEST_ROOT)
                .forEach(sourcePath -> {
                    Path relative = TEST_ROOT.relativize(sourcePath);
                    Path targetPath = dest.resolve(relative);

                    try {
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else if (!javaPattern.matches(TEST_ROOT, sourcePath)) { // Exclude pattern
                            Files.createDirectories(targetPath.getParent());
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed copying file: " + sourcePath, e);
                    }
                });
      return javaPattern;
    }

    private List<String> listAllRelativeFiles(Path root) throws IOException {
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString())
                .collect(Collectors.toList());
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;

        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
    }
}