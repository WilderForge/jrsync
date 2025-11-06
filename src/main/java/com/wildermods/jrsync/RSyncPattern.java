package com.wildermods.jrsync;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

import com.wildermods.jrsync.internal.StringHelper;

/**
 * Provides a framework for matching filesystem paths against rsync-style patterns.
 * <p>
 * The {@code Pattern} class hierarchy converts rsync exclude syntax into Java
 * {@link java.util.regex.Pattern regex} expressions, enabling consistent filtering
 * of files and directories in a platform-agnostic way.
 * <p>
 * Each {@code Pattern} subclass implements a different kind of filter:
 * <ul>
 *   <li>{@link RegexBackedPattern} – A compiled regex derived from an rsync-style pattern.</li>
 *   <li>{@link Empty} – A pattern representing a blank or null entry (never matches).</li>
 *   <li>{@link Comment} – A pattern representing a comment line (never matches).</li>
 * </ul>
 *
 * <p>
 * The conversion logic is based closely on rsync’s documented matching rules, including
 * support for wildcards ({@code *}, {@code **}, {@code ?}), POSIX character classes,
 * and escape sequences.
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * Pattern p = Pattern.compile("*.java");
 * boolean matches = p.matches(Path.of("/home/user/Main.java")); // true
 * }</pre>
 *
 * <p>
 * Unsupported features:
 * <ul>
 *   <li>Include operations ({@code + }) are not supported and throw {@link PatternSyntaxException}.</li>
 *   <li>Complex rsync negation patterns are not implemented.</li>
 * </ul>
 *
 * @author Gamebuster
 * 
 * @see <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a>
 * @see java.util.regex.Pattern
 */
public abstract class RSyncPattern implements FileFilter {

	/** 
	 * The original rsync-style pattern string. 
	 */
	public final String pattern;
	
	/**
	 * Constructs a new {@code Pattern} with the given rsync-style pattern string.
	 *
	 * @param rsyncPattern the raw rsync-style pattern (may be {@code null})
	 */
	protected RSyncPattern(String rsyncPattern) {
		this.pattern = rsyncPattern;
	}
	
	@Override
	public String toString() {
		return pattern.toString();
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(pattern);
	}
	
	@Override
	public abstract boolean equals(Object o);
	
	
	// ---------------------------------------------------------------------
	// Regex-backed implementation
	// ---------------------------------------------------------------------
	
	
	/**
	 * A {@link RSyncPattern} implementation backed by a compiled regular expression.
	 * <p>
	 * Converts an rsync-style pattern into a Java regex via {@link #toRegex(String)}.
	 * Supports all rsync wildcards, POSIX classes, and escape semantics.
	 * 
	 * @see <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a>
	 */
	public static class RegexBackedPattern extends RSyncPattern {

		/**
		 *  The compiled regex equivalent of the rsync pattern. 
		 */
		protected final java.util.regex.Pattern regexPattern;
		
		
		/**
		 * Constructs a {@code RegexBackedPattern} by compiling the provided rsync-style pattern.
		 *
		 * @param rsyncPattern the rsync-style pattern to compile
		 * @throws PatternSyntaxException if the pattern is syntactically invalid or a StackOverflowError occurs
		 */
		public RegexBackedPattern(String rsyncPattern) throws PatternSyntaxException {
			super(rsyncPattern);
			try {
				this.regexPattern = java.util.regex.Pattern.compile(toRegex(rsyncPattern));
			}
			catch(StackOverflowError e) {
				PatternSyntaxException pse = new PatternSyntaxException("Rsync to regex conversion caused stack overflow during compilation (too complex or recursive)", rsyncPattern, -1);
				pse.initCause(e);
				throw pse;
			}
		}
		

		/**
		 * Returns the compiled Java regex representation of this pattern.
		 *
		 * @return the compiled {@link java.util.regex.Pattern}
		 */
		public java.util.regex.Pattern getRegex() {
			return regexPattern;
		}
		
		private boolean matches(Path root, Path path, boolean recursive) {
			if(!root.isAbsolute()) {
				throw new IllegalArgumentException("Root path must be absolute!");
			}
			
			Path absolutePath = root.resolve(path); //resolve relative paths against root
			Path relative = root.relativize(absolutePath);
			Matcher matcher = regexPattern.matcher(relative.toString().replace('\\', '/'));
			
			if(!matcher.matches()) {
				return false;
			}
			
			if(recursive) {
				if(pattern.endsWith("/")) {
					
					Path checking = absolutePath.getParent();
					do {
						if(matches(root, checking, false)) { //a parent directory caused the match
							return true;
						}
						checking = checking.getParent();
					}
					while(checking != null);
					
					//otherwise this file was matched exactly. If it matched, must be an actual file to really match
					if(Files.isDirectory(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
						if(Files.isSymbolicLink(path)) {
							return false;
						}
					}
				}
			}
			

			return true;

		}
		
		/**
		 * Tests whether the provided {@code path} matches this pattern when interpreted
		 * relative to the specified {@code root}.
		 * <p>
		 * If the rsync pattern ends with a slash ({@code /}), this method will only match
		 * directories that are not symbolic links.
		 * <p>
		 * See <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a> 
		 * for more detailed documentation.
		 * 
		 * @see <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a>
		 *
		 * @param root the absolute root directory against which {@code path} is resolved. Considered the 'Transfer-Root' in rsync lingo
		 * @param path the relative or absolute path to test
		 * @return {@code true} if the path matches the pattern, otherwise {@code false}
		 * @throws IllegalArgumentException if {@code root} is not absolute
		 */
		public boolean matches(Path root, Path path) {
			return matches(root, path, true);
		}

		/**
		 * Tests whether the provided {@code path} matches this pattern.
		 * <p>
		 * The path must be absolute; otherwise an exception is thrown.
		 * <p>
		 * The transfer-root is considered to be the root directory of the
		 * current working directory. Equivalent to calling {@code matches(Path.of(".").toAbsolutePath().getRoot(), path);}
		 * <p>
		 * See <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a> 
		 * for more detailed documentation.
		 * 
		 * @see <a href="https://man7.org/linux/man-pages/man1/rsync.1.html#FILTER_RULES">Rsync Filter rules - Pattern Matching Rules</a>
		 * @param path the absolute path to test
		 * @return {@code true} if the path matches, otherwise {@code false}
		 * @throws IllegalArgumentException if the path is not absolute
		 */
		@Override
		public boolean matches(Path path) {
			
			if(!path.isAbsolute()) {
				throw new IllegalArgumentException("Provided path must be absolute!");
			}
			
			return matches(path.getRoot(), path);
		}
		
		@Override
		public String toString() {
			return regexPattern.toString();
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(toString());
		}
		
		@Override
		public boolean equals(Object o) {
			if(o instanceof RegexBackedPattern) {
				return o.toString().equals(toString());
			}
			return false;
		}
		
	}
	
	/**
	 * Compiles an rsync-style pattern into a {@link RSyncPattern} instance.
	 * <ul>
	 *   <li>Blank or null strings produce an {@link Empty} pattern.</li>
	 *   <li>Lines starting with {@code #} or {@code ;} produce a {@link Comment} pattern.</li>
	 *   <li>All others produce a {@link RegexBackedPattern}.</li>
	 * </ul>
	 *
	 * @param rsyncPattern the rsync-style pattern string
	 * @return a compiled {@link RSyncPattern}
	 * @throws PatternSyntaxException if the pattern is invalid or unsupported
	 */
	public static RSyncPattern compile(String rsyncPattern) {
		if(rsyncPattern == null || StringHelper.isBlank(rsyncPattern)) {
			return new Empty(rsyncPattern);
		}
		
		String trimmed = rsyncPattern.trim();
		if(trimmed.startsWith("#") || trimmed.startsWith(";")) {
			return new Comment(rsyncPattern);
		}
		
		return new RegexBackedPattern(rsyncPattern);
	}
	
	/*
	 * Eyes up buddy. There's nothing you want to see below.
	 */
	
	/**
	 * Converts an rsync-style pattern into an equivalent Java regular expression.
	 * <p>
	 * This method wraps the lower-level {@link #toRegexImpl(String)} translator with
	 * rsync-specific semantics such as:
	 * <ul>
	 *   <li>Anchoring when the pattern starts with a slash ({@code /})</li>
	 *   <li>Matching only the tail of the path if the pattern is not anchored</li>
	 *   <li>Recursive matching when {@code **} or nested directories appear</li>
	 *   <li>End-of-string enforcement to prevent partial matches</li>
	 * </ul>
	 *
	 * <p>
	 * Include operations (patterns beginning with {@code "+ "}) are explicitly unsupported
	 * and result in a {@link PatternSyntaxException}.
	 *
	 * @param rsyncPattern the rsync-style pattern string
	 * @return a regex string equivalent to the provided rsync pattern
	 * @throws PatternSyntaxException if the pattern is invalid or unsupported
	 */
	protected static String toRegex(String rsyncPattern) {
		if(rsyncPattern == null || StringHelper.isBlank(rsyncPattern)) return "";
		
		StringBuilder ret = new StringBuilder();
		
		if(rsyncPattern.startsWith("- ")) {
			rsyncPattern = rsyncPattern.substring(2);
		}
		if(rsyncPattern.startsWith("+ ")) {
			throw new PatternSyntaxException("Include operations unsupported (cannot start with + and space)", rsyncPattern, 0);
		}
		
		boolean anchored = false;
		
		/**
		 * if the rsync pattern starts with a /, then the matching is anchored to the start of the path string
		 */
		if(rsyncPattern.charAt(0) == '/') {
			ret.append("^");
			rsyncPattern = rsyncPattern.substring(1);
			anchored = true;
		}
		else {
			ret.append("(.*/)?"); //otherwise, we only match against the tail
		}
		
		if(rsyncPattern.length() > 1) {
			/**
			 * if the pattern contains a / anywhere except the last character, or contains a ** anywhere, then the pattern is
			 * matched against the full pathname, including any leading directories within the transfer.
			 */
			if(rsyncPattern.substring(0, rsyncPattern.length() - 1).contains("/") || rsyncPattern.contains("**")) { 
				ret.append("(^|.*/)");  // matches from root or any subdir
			}
		}
		
		ret.append(toRegexImpl(rsyncPattern));
		
		if(anchored) {
			ret.append("($|/.*)");
		}
		
		if(rsyncPattern.endsWith("/")) {
			if(!rsyncPattern.endsWith("//")) {
				if(!anchored) {
					ret.append("(.*)");
				}
				ret.append("?"); //$ is already added later
			}
		}
		
		ret.append("$"); //ensure the end of string is met so we don't match things we should not.
		
		return ret.toString();
	}
	
	/*
	 * Well, if you insist...
	 * 
	 * Edit this only if you enjoy hours of suffering and regex/rsync/posix induced nightmares.
	 */
	
	/**
	 * Performs the low-level translation of an rsync-style pattern into a regex fragment.
	 * <p>
	 * This method handles the actual character-by-character conversion of rsync meta
	 * characters into their Java regex equivalents, including:
	 * <ul>
	 *   <li>{@code *} → {@code [^/]*}</li>
	 *   <li>{@code **} → {@code .*}</li>
	 *   <li>{@code ?} → {@code .}</li>
	 *   <li>Proper escaping of the following regex-reserved characters:</li>
	 *   <ul>
	 *   	<li>{@code . ^ $ + { } | ( )}
	 *   </ul>
	 *   <li>Not escaping characters that have the same meaning across both patterns
	 *   <li>Support for POSIX character classes such as {@code [[:alpha:]]}</li>
	 *   <li>Preservation of literal backslashes when wildcards are absent</li>
	 * </ul>
	 *
	 * <p>
	 * 
	 * The translation is designed to match rsync’s documented wildcard rules as closely
	 * as possible, including handling of escaped characters.
	 *
	 * @param rsyncPattern the rsync-style pattern string
	 * @return a Java regex fragment suitable for concatenation
	 * @throws PatternSyntaxException if the pattern is invalid or improperly escaped
	 */
	protected static String toRegexImpl(String rsyncPattern) {
		// 19 hours were spent below
		if(rsyncPattern == null || StringHelper.isBlank(rsyncPattern)) return "";
		
		boolean hasWildcards = rsyncPattern.contains("*") || rsyncPattern.contains("?") || rsyncPattern.contains("[");
		
		String glob = rsyncPattern;
		
		StringBuilder ret = new StringBuilder();
		
		for(int i = 0; i < glob.length(); i++) {

			char c = glob.charAt(i);
			
			switch(c) {
				case '*':
					{
						int j = i + 1;
						if(j < glob.length()) {
							char next = glob.charAt(j);
							if(next == '*') {
								ret.append(".*"); //double asterisks in rsync are the same as .* in regex
								i = j;
								continue;
							}
						}
						ret.append("[^/]*"); //a single asterisk matches every character EXCEPT /
						continue;
					}
				case '\\':
					ret.append('\\'); //
					if(hasWildcards) {
						if(i < glob.length()) {
							i++;
							char next = glob.charAt(i);
							switch(next) { //make sure we preserve escape sequences that exist in both regex and rsync
								case '*':
								case '?':
								case '[':
								case '-':
								case ']':
								case '#': //comments
								case ';': //comments
								case '\\':
								case '/': //windows
								ret.append(next);
								continue;
							}
							
							ret.append('\\'); //Okay, we have encountered a backslash that doesn't escape anything. It could mess up the regex. We must make it literal.
							ret.append(next);
							continue;
						}
					}
					else {
						ret.append('\\'); //if no wildcards, then all backslashes are treated as literals per the rsync man page
					}
					continue;
				case '?':
					ret.append('.'); //a question mark in rsync behaves the same as a single . in regex
					continue;
				case '[':
					String sub = rsyncPattern.substring(i);
					if(sub.startsWith("[[:")) {
						int end = rsyncPattern.indexOf(":]]");
						if(end > 0) {
							String posixClass = rsyncPattern.substring(i, end + 3);
							String regex = posixToRegex(posixClass);
							ret.append(regex);

							
							i = end + 2;
							break;
						}
					}
					ret.append(c);
					break;
					
					
				//case ']': a special case, closing brackets must already be escaped in the rsync pattern, otherwise the rsync itself is invalid anyway. No special handling needed.
					
				case '.': //these characters have a special meaning in regex, but not in rsync
				case '^': //so if they appear in rsync we need to escape them when converting to regex
				case '$':
				case '+':
				case '{':
				case '}':
				case '|':
				case '(':
				case ')':
					ret.append('\\');
					ret.append(c);
					continue;
				default:
					ret.append(c);
			}
		}
		
		return ret.toString();
	}
	
	//Huge thanks to: https://www.regular-expressions.info/posixbrackets.html - you have literally saved me tens of hours
	private static final Map<String, String> POSIX_TO_REGEX;
	static {
		LinkedHashMap<String, String> entries = new LinkedHashMap<>();
		{
			entries.put("alnum",  "[a-zA-Z0-9]");
			entries.put("alpha",  "[a-zA-Z]");
			entries.put("ascii",  "[\\x00-\\x7F]"); // [\x00-\x7F]
			entries.put("blank",  "[ \\t]"); // [ \t]
			entries.put("cntrl",  "[\\x00-\\x1F\\x7F]"); // [\x00-\x1F\x7F]
			entries.put("digit",  "[0-9]");
			entries.put("graph",  "[\\x21-\\x7E]"); // [\x21-\x7E]
			entries.put("lower",  "[a-z]");
			entries.put("print",  "[\\x20-\\x7E]"); // [\x20-\x7E]
			entries.put("punct",  "[!\\\"\\#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]"); // [!\"\#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~] --WARNING THE WEBSITE IS WRONG, YOU NEED TO ESCAPE THE QUOTATION MARK
			entries.put("space",  "[ \\t\\r\\n\\v\\f]"); // [ \t\r\n\v\f]
			entries.put("upper",  "[A-Z]");
			entries.put("word",   "[A-Za-z0-9_]");
			entries.put("xdigit", "[A-Fa-f0-9]");
		}
		POSIX_TO_REGEX = Collections.unmodifiableMap(entries);
	}
	
	/**
	 * Converts a POSIX character class (e.g. {@code [[:alpha:]]}) into its Java regex equivalent.
	 *
	 * @param posixClass the full POSIX bracket expression
	 * @return a regex-compatible string fragment
	 * @throws IllegalArgumentException if the class name is unknown or malformed
	 */
	private static String posixToRegex(String posixClass) {
		if (posixClass == null || !posixClass.startsWith("[[:") || !posixClass.endsWith(":]]")) {
			throw new IllegalArgumentException("Invalid POSIX class format: " + posixClass);
		}
		

		String key = posixClass.substring(3, posixClass.length() - 3); // extract CLASS
		String replacement = POSIX_TO_REGEX.get(key.toLowerCase());
		
		if(replacement == null) {
			throw new IllegalArgumentException("Unknown POSIX class:" + key);
		}
		
		return replacement;
	}	
	
	/**
	 * An abstract {@link RSyncPattern} implementation that never matches any path.
	 * <p>
	 * Used as a base for {@link Empty} and {@link Comment} pattern types.
	 */
	public static abstract class FalsePattern extends RSyncPattern {

		public FalsePattern(String rsyncPattern) {
			super(rsyncPattern);
		}
		
		@Override
		public final boolean matches(Path path) {
			return false;
		}
		
	}
	
	/**
	 * Represents a blank or empty rsync pattern that never matches any path.
	 */
	public static class Empty extends FalsePattern  {
		public Empty(String rsyncPattern) {
			super(rsyncPattern);
		}
		
		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Empty;
		}
	}
	
	/**
	 * Represents a comment line in an rsync exclude/include file.
	 * <p>
	 * Comment patterns never match any path.
	 */
	public static class Comment extends FalsePattern {

		public Comment(String rsyncPattern) {
			super(rsyncPattern);
		}
		
		@Override
		public boolean equals(Object o) {
			if(o instanceof Comment) {
				return o.toString().equals(toString());
			}
			
			return false;
		}
		
	}
	
}
