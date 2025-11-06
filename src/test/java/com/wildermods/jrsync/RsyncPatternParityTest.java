package com.wildermods.jrsync;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wildermods.jrsync.RSyncPattern.RegexBackedPattern;

import org.junit.jupiter.api.Assertions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.stream.*;

import static com.wildermods.jrsync.OS.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RsyncPatternParityTest {

    private static final Path TEST_ROOT = Paths.get("src/test/resources/testData").toAbsolutePath();
    private static final Path EXCLUDE_DATA_ROOT = Paths.get("src/test/resources/excludeTestData").toAbsolutePath();

    private static final Path LINUX_RSYNC_DIR = Paths.get("/tmp/jrsync/rsyncCopy");
    private static final Path LINUX_JAVA_DIR = Paths.get("/tmp/jrsync/javaCopy");
    
    private static final Path LINUX_TO_WINDOWS_RESULTS_MOUNT = Paths.get("C:\\jrsync\\linux-results");
    
    private static final boolean IS_GITHUB_ACTIONS = "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    private static final OS OS = com.wildermods.jrsync.OS.getOS();
    
    private static final BiConsumer<Path, Path> CLEANUP = (expected, results) -> {deleteRecursively(expected); deleteRecursively(results);};
    private static final BiConsumer<Path, Path> NO_CLEANUP = (expected, results) -> {};
    
    static Stream<Entry<String, String>> providePatterns() throws IOException {
        Map<String, String> patterns = new LinkedHashMap<>();

        Files.walk(EXCLUDE_DATA_ROOT)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try (BufferedReader reader = Files.newBufferedReader(path)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                            
                            patterns.put(path.getFileName().toString(), line);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read pattern file: " + path, e);
                    }
                });

        return patterns.entrySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providePatterns")
    void testPatternParity(Entry<String, String> entry) throws Exception {
    	
    	final String name = entry.getKey();
    	final String pattern = entry.getValue();
    	
    	final BiConsumer<Path, Path> cleanup = IS_GITHUB_ACTIONS ? NO_CLEANUP : CLEANUP;
    	
    	if(OS == LINUX) {
    		final Path rsyncDir;
    		final Path javaDir;
    		if(!IS_GITHUB_ACTIONS) {
    	        rsyncDir = Files.createTempDirectory("rsyncCopy");
    	        javaDir = Files.createTempDirectory("javaCopy");
    		}
    		else {
    			rsyncDir = LINUX_RSYNC_DIR;
    			javaDir = LINUX_JAVA_DIR.resolve(name);
    		}
    		prepareStaticDirs(rsyncDir, javaDir);
    		runParityTest(
    			"comparing linux against rsync", 
    			() -> (RegexBackedPattern) RSyncPattern.compile(pattern), 
    			() -> {
    				runRsyncCopy(pattern, rsyncDir);
    				return rsyncDir;
    			}, 
    			() -> javaDir,
    			cleanup
    		);
    	}
    	else {
    		if (!IS_GITHUB_ACTIONS) {
    			assumeTrue(false, "Skipping Rsync parity test: not running on Github Actions");
    		}
    		else if (OS == WINDOWS) {
    			runParityTest(
    				"comparing windows against linux",
    				() -> (RegexBackedPattern) RSyncPattern.compile(pattern),
    				() -> LINUX_TO_WINDOWS_RESULTS_MOUNT.resolve(name),
    				() -> Files.createTempDirectory("javaCopy"),
    				cleanup
    			);
    		}
    		else {
    			assumeTrue(false, "Skipping Rsync parity test: unsupported OS");
    		}
    	}
    }
    
    private void runParityTest(String testName, Callable<RegexBackedPattern> patternProvider, Callable<Path> expectedProvider, Callable<Path> resultsProvider, BiConsumer<Path, Path> finalizer) throws Exception {
    	Path expected = null;
    	Path results = null;
    	RegexBackedPattern pattern = null;
    	String identifier = testName != null && !testName.trim().isEmpty() ? "(" + testName + ")" : ""; 
    	try {
    		expected = expectedProvider.call();
    		results = resultsProvider.call();
    		pattern = patternProvider.call();
    		
    		System.out.println("Expected: " + expected);
    		System.out.println("results: " + results);
    		
    		copyWithJava(pattern, results);
    		
            List<String> expectedFiles = listAllRelativeFiles(expected);
            List<String> resultingFiles = listAllRelativeFiles(results);

            Collections.sort(expectedFiles);
            Collections.sort(resultingFiles);
            
    		Assertions.assertEquals(expectedFiles, resultingFiles, 
    				"Directory mismatch " + identifier + " for pattern: " + pattern + " regex: " + pattern.regexPattern + " ");
    	}
    	finally {
    		finalizer.accept(expected, results);
    	}
    }

    private void runRsyncCopy(String excludePattern, Path dest) throws IOException, InterruptedException {
        Path tempExclude = Files.createTempFile("exclude", ".txt");
        Files.write(tempExclude, Collections.singleton(excludePattern), StandardOpenOption.TRUNCATE_EXISTING);

        ProcessBuilder pb = new ProcessBuilder(
                "rsync",
                "-av",
                "--exclude-from=" + tempExclude.toAbsolutePath(),
                TEST_ROOT.toAbsolutePath() + "/",
                dest.toAbsolutePath().toString() + "/"
        );
        
        System.out.println("Copying from: " + TEST_ROOT.toAbsolutePath() + " to " + dest.toAbsolutePath().toString());
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

    private void copyWithJava(RegexBackedPattern pattern, Path dest) throws IOException {
        System.out.println("Java dest: " + dest);
        
        Files.walk(TEST_ROOT)
                .forEach(sourcePath -> {
                    Path relative = TEST_ROOT.relativize(sourcePath);
                    Path targetPath = dest.resolve(relative);

                    try {
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else if (!pattern.matches(TEST_ROOT, sourcePath)) { // Exclude pattern
                            Files.createDirectories(targetPath.getParent());
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed copying file: " + sourcePath, e);
                    }
                });
    }

    private List<String> listAllRelativeFiles(Path root) throws IOException {
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString())
                .collect(Collectors.toList());
    }

    private static void deleteRecursively(Path root) {
    	try {
	        if (root != null && !Files.exists(root)) return;
	
	        Files.walk(root)
	                .sorted(Comparator.reverseOrder())
	                .forEach(path -> {
	                    try {
	                        Files.deleteIfExists(path);
	                    } catch (IOException ignored) {
	                    }
	                });
    	}
    	catch(IOException e) {
    		throw new UncheckedIOException(e);
    	}
    }
    
    private void prepareStaticDirs(Path... dirs) throws IOException {
    	for (Path dir : dirs) {
			//deleteRecursively(dir);
    		Files.createDirectories(dir);
    	}
    }
}