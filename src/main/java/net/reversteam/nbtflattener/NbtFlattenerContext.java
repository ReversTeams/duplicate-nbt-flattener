package net.reversteam.nbtflattener;

public class NbtFlattenerContext {
	public static final int EXPANDED_DEPTH = 4096;

	private static final ThreadLocal<Boolean> BYPASS_DEPTH_LIMIT = ThreadLocal.withInitial(() -> false);

    public static void setBypass(boolean value) {
        BYPASS_DEPTH_LIMIT.set(value);
    }

    public static boolean shouldBypass() {
        return BYPASS_DEPTH_LIMIT.get();
    }
}
