package com.boydti.fawe;

public class FaweVersion {
    public final int hash, major, minor, patch;

    public FaweVersion(final String version) {
        String[] split = version.split("\\.");

        if (split.length == 1) {
            hash = !split[0].equals("unknown") ? Integer.valueOf(split[0], 16) : 0;
            major = minor = patch = 0;
            return;
        }

        this.hash = 0;
        this.major = Integer.parseInt(split[0]);
        this.minor = Integer.parseInt(split[1]);
        this.patch = Integer.parseInt(split[2]);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    public boolean isSnapshot() {
        return hash == 0;
    }

    public boolean isNewer(FaweVersion other) {
        return this.major > other.major
               || (this.major == other.major && this.minor > other.minor)
               || (this.major == other.major && this.minor == other.minor && this.patch > other.patch);
    }
}