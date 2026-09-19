package io.github.skiesworld.qqbot.media;

/** Rich-media kinds accepted by the file upload endpoints. */
public enum FileType {

    IMAGE(1, 20L * 1024 * 1024, 200L * 1024 * 1024),
    VIDEO(2, 30L * 1024 * 1024, 200L * 1024 * 1024),
    VOICE(3, 20L * 1024 * 1024, 200L * 1024 * 1024),
    FILE(4, 200L * 1024 * 1024, 200L * 1024 * 1024);

    private final int value;
    private final long softLimitBytes;
    private final long hardLimitBytes;

    FileType(int value, long softLimitBytes, long hardLimitBytes) {
        this.value = value;
        this.softLimitBytes = softLimitBytes;
        this.hardLimitBytes = hardLimitBytes;
    }

    /** Wire value of {@code file_type}. Above the soft limit the platform downgrades to {@link #FILE}. */
    public int value() {
        return value;
    }

    public long softLimitBytes() {
        return softLimitBytes;
    }

    public long hardLimitBytes() {
        return hardLimitBytes;
    }

    public static FileType fromValue(int value) {
        for (FileType t : values()) {
            if (t.value == value) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown file_type " + value);
    }
}
