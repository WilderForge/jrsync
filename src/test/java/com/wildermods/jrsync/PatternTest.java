package com.wildermods.jrsync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.wildermods.jrsync.RSyncPattern.Comment;
import com.wildermods.jrsync.RSyncPattern.Empty;
import com.wildermods.jrsync.RSyncPattern.RegexBackedPattern;

public class PatternTest {

	private static Path root;

	@BeforeAll
	static void setup() throws Exception {
		root = Files.createTempDirectory("rsync-root").toAbsolutePath();
		Files.createDirectories(root.resolve("src/main/java"));
		Files.createFile(root.resolve("src/main/java/Main.java"));
		Files.createFile(root.resolve("src/main/java/Main.class"));
	}

	// ---------------------------------------------------------
	// Pattern.compile() logic
	// ---------------------------------------------------------
	@Nested
	@DisplayName("Pattern.compile() tests")
	class CompileTests {
		@Test
		void testBlankPatternProducesEmpty() {
			RSyncPattern p = RSyncPattern.compile("   ");
			assertTrue(p instanceof Empty);
			assertFalse(p.matches(root.resolve("any")));
		}

		@Test
		void testNullPatternProducesEmpty() {
			RSyncPattern p = RSyncPattern.compile(null);
			assertTrue(p instanceof Empty);
		}

		@Test
		void testCommentPatternProducesComment() {
			RSyncPattern p1 = RSyncPattern.compile("#comment");
			RSyncPattern p2 = RSyncPattern.compile(";comment");
			assertTrue(p1 instanceof Comment);
			assertTrue(p2 instanceof Comment);
			assertFalse(p1.matches(root.resolve("whatever")));
		}

		@Test
		void testNormalPatternProducesRegexBacked() {
			RSyncPattern p = RSyncPattern.compile("*.java");
			assertTrue(p instanceof RegexBackedPattern);
		}
	}

	// ---------------------------------------------------------
	// RegexBackedPattern behavior
	// ---------------------------------------------------------
	@Nested
	@DisplayName("RegexBackedPattern behavior")
	class RegexBackedPatternTests {

		@Test
		void testMatchesSimpleExtension() {
			RegexBackedPattern p = new RegexBackedPattern("*.java");
			assertTrue(p.matches(root, Paths.get("src/main/java/Main.java")));
			assertFalse(p.matches(root, Paths.get("src/main/java/Main.class")));
		}

		@Test
		void testMatchesAbsolutePath() {
			RegexBackedPattern p = new RegexBackedPattern("*.java");
			Path abs = root.resolve("src/main/java/Main.java");
			assertTrue(p.matches(abs));
		}

		@Test
		void testRejectsRelativePathInAbsoluteMatch() {
			RegexBackedPattern p = new RegexBackedPattern("*.java");
			Path relative = Paths.get("src/main/java/Main.java");
			assertThrows(IllegalArgumentException.class, () -> p.matches(relative));
		}

		@Test
		void testRejectsNonAbsoluteRoot() {
			RegexBackedPattern p = new RegexBackedPattern("*.java");
			Path nonAbsRoot = Paths.get("not/absolute");
			Path file = root.resolve("src/main/java/Main.java");
			assertThrows(IllegalArgumentException.class, () -> p.matches(nonAbsRoot, file));
		}

		@Test
		void testEqualsAndHashCode() {
			RegexBackedPattern p1 = new RegexBackedPattern("*.java");
			RegexBackedPattern p2 = new RegexBackedPattern("*.java");
			RegexBackedPattern p3 = new RegexBackedPattern("*.class");

			assertEquals(p1, p2);
			assertNotEquals(p1, p3);
			assertEquals(p1.hashCode(), p2.hashCode());
		}
	}

	// ---------------------------------------------------------
	// Error handling
	// -------------------------------------------------------------------------------------------------------------
	@Nested
	@DisplayName("Error handling")
	class ErrorHandling {

		@Test
		void testStackOverflowErrorGetsWrapped() {
			// Build a very long invalid pattern to *try* to trigger overflow or syntax failure
			StringBuilder insane = new StringBuilder("(");
			for (int i = 0; i < 100000; i++) {
				insane.append('*');
			}
			insane.append(')');

			assertThrows(PatternSyntaxException.class, () -> {
				new RegexBackedPattern(insane.toString());
			});
		}
	}
}