package io.github.skiesworld.qqbot.command;

/**
 * Who may run a command, read from the group {@code member_role} the platform reports.
 *
 * <p>Ranking is what turns the three documented strings into permission bits: {@code owner} satisfies an
 * {@code admin} requirement, {@code member} does not. Outside a group the field is absent, {@link #fromWire}
 * returns null and {@link #allows} lets anything through — a private chat has no roles to check, so gating on
 * one there would silently disable the command rather than protect it.
 */
public enum Role {

    ANY(0),
    MEMBER(1),
    ADMIN(2),
    OWNER(3);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    /**
     * Whether a caller whose reported role is {@code actual} may run something requiring this role. A null
     * {@code actual} means the scene reported no role at all.
     */
    public boolean allows(Role actual) {
        return this == ANY || actual == null || actual.rank >= this.rank;
    }

    /** {@code member} / {@code admin} / {@code owner}; null when absent or unrecognised. */
    public static Role fromWire(String memberRole) {
        if (memberRole == null) {
            return null;
        }
        return switch (memberRole.toLowerCase(java.util.Locale.ROOT)) {
            case "owner" -> OWNER;
            case "admin" -> ADMIN;
            case "member" -> MEMBER;
            default -> null;
        };
    }
}
