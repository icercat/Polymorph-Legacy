package dev.frostey.recipeconflicts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class SnapshotMatcherTest {
    private static SnapshotMatcher.Rule rule(int count, Long... tokens) {
        return new SnapshotMatcher.Rule(count, new HashSet<Long>(Arrays.asList(tokens)));
    }

    public static void main(String[] args) throws Exception {
        long[] grid = new long[9];
        Arrays.fill(grid, SnapshotMatcher.EMPTY);
        grid[0] = SnapshotMatcher.token(5, 3);
        SnapshotMatcher.Rule exact = rule(1, SnapshotMatcher.token(5, 3));
        SnapshotMatcher.Rule wildcard = rule(1, SnapshotMatcher.token(5, 32767));
        SnapshotMatcher.Rule different = rule(1, SnapshotMatcher.token(5, 2));
        if (!exact.possible(grid) || !wildcard.possible(grid) || different.possible(grid)) throw new AssertionError("metadata/wildcard");
        if (rule(2, SnapshotMatcher.token(5, 3)).possible(grid)) throw new AssertionError("ingredient count");
        List<long[]> samples = new ArrayList<long[]>();
        samples.add(grid);
        SnapshotMatcher matcher = new SnapshotMatcher(new SnapshotMatcher.Rule[]{exact, wildcard, different, null}, samples);
        // Mutating the source after construction must not change the worker snapshot.
        grid[0] = SnapshotMatcher.token(99, 0);
        Thread worker = new Thread(matcher, "snapshot-test");
        worker.start(); worker.join(2000);
        if (worker.isAlive()) throw new AssertionError("worker did not finish");
        if (!Arrays.equals(matcher.poll(), new int[]{0, 1, 3})) throw new AssertionError("candidate or custom recipe lost");
        if (matcher.failure != null || !matcher.done) throw new AssertionError("worker failed");

        for (int i = 0; i < 100; i++) samples.add(grid);
        SnapshotMatcher blocked = new SnapshotMatcher(new SnapshotMatcher.Rule[]{null}, samples);
        Thread cancel = new Thread(blocked, "snapshot-cancel-test");
        cancel.start();
        long deadline = System.nanoTime() + 2000000000L;
        while (blocked.matrices < 32 && System.nanoTime() < deadline) Thread.yield();
        cancel.interrupt(); cancel.join(2000);
        if (cancel.isAlive() || !blocked.done) throw new AssertionError("bounded queue cancellation failed");
        System.out.println("PASS: snapshot isolation, metadata, wildcard, count, custom fallback, cancellation");
    }
}
