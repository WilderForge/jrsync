package com.wildermods.jrsync;

public enum OS {
	LINUX,
	MAC,
	WINDOWS;
	
	public static OS getOS() {
		String osName = System.getProperty("os.name").toLowerCase();

		if (osName.contains("win")) {
			return OS.WINDOWS;
		} else if (osName.contains("mac")) {
			return OS.MAC;
		} else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
			return OS.LINUX;
		} else {
			throw new UnsupportedOperationException("Unsupported operating system: " + osName);
		}
	}
}
