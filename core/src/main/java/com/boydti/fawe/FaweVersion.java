package com.boydti.fawe;

public class FaweVersion {
    public final String hash;
    public final int major, minor, patch;

    public FaweVersion(final String version) {
        final String[] split = version.substring(version.indexOf('=') + 1).split("\\.");

        if (split.length == 1) {
            hash = !split[0].equals("unknown") ? split[0] : null;
            major = minor = patch = 0;
            return;
        }

        this.hash = null;
        this.major = Integer.parseInt(split[0]);
        this.minor = Integer.parseInt(split[1]);
        this.patch = Integer.parseInt(split[2]);
    }

    @Override
    public String toString() {
        return hash == null ? major + "." + minor + "." + patch : hash;
    }

    public boolean isSnapshot() {
        return hash != null;
    }

    public boolean isNewer(FaweVersion other) {
        return this.major > other.major
               || (this.major == other.major && this.minor > other.minor)
               || (this.major == other.major && this.minor == other.minor && this.patch > other.patch);
    }
}