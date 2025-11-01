package com.wildermods.jrsync;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;

@FunctionalInterface
public interface FileFilter extends PathMatcher, java.io.FileFilter {
	
	public default boolean matches(String file) {
		return matches(Paths.get(file));
	}
	
	public default boolean matches(File file) {
		return matches(file.toPath());
	}
	
	@Override
	public default boolean accept(File file) {
		return matches(file);
	}
	
	@Override
	public boolean matches(Path path);
	
}
