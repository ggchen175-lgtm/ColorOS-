package cn.zhe.chaojibeauty;

/** Vertical touch samples for the phone CoverSheet release rule in 24A437. */
final class ShadePullRelease {
    private static final int WINDOW_MS = 42;
    private final long[] times = new long[64];
    private final float[] points = new float[times.length];
    private int first, count;

    void begin(long time, float y) {
        first = count = 0;
        sample(time, y);
    }

    void sample(long time, float y) {
        if (count > 0 && time <= times[(first + count - 1) % times.length]) return;
        // Retain one point before the window to interpolate its starting position.
        while (count >= 2 && (count == times.length || times[(first + 1) % times.length] <= time - WINDOW_MS)) {
            first = (first + 1) % times.length;
            count--;
        }
        int next = (first + count++) % times.length;
        times[next] = time;
        points[next] = y;
    }

    float velocity() {
        if (count < 2) return 0f;
        int last = (first + count - 1) % times.length;
        long start = Math.max(times[first], times[last] - WINDOW_MS);
        int next = (first + 1) % times.length;
        float mix = (start - times[first]) / (float)(times[next] - times[first]);
        float y = points[first] + mix * (points[next] - points[first]);
        return (points[last] - y) * 1000f / (times[last] - start);
    }

    static boolean opens(float distance, float height, float velocity, float density) {
        return height > 0 && (distance > height - 62f * density ||
                (velocity >= -306f * density && distance + .15f * velocity > .60f * height));
    }
}
