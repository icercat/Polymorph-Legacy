package dev.frostey.recipeconflicts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

/** Pure Java candidate filtering. Never holds an ItemStack, IRecipe or World. */
final class SnapshotMatcher implements Runnable {
    static final long EMPTY = Long.MIN_VALUE;

    static long token(int item, int damage) { return ((long) item << 32) | (damage & 0xffffffffL); }

    static final class Rule {
        final int count;
        private final Set<Long> exact;
        private final Set<Integer> wildcard;

        Rule(int count, Set<Long> tokens) {
            this.count = count;
            Set<Long> values = new HashSet<Long>();
            Set<Integer> wild = new HashSet<Integer>();
            for (Long value : tokens) {
                if ((int) value.longValue() == 32767) wild.add(Integer.valueOf((int) (value.longValue() >>> 32)));
                else values.add(value);
            }
            exact = Collections.unmodifiableSet(values);
            wildcard = Collections.unmodifiableSet(wild);
        }

        boolean possible(long[] grid) {
            int occupied = 0;
            for (long value : grid) {
                if (value == EMPTY) continue;
                occupied++;
                if (!exact.contains(Long.valueOf(value))
                    && !wildcard.contains(Integer.valueOf((int) (value >>> 32)))) return false;
            }
            return occupied == count;
        }
    }

    private final Rule[] rules;
    private final long[][] grids;
    private final ArrayBlockingQueue<int[]> ready = new ArrayBlockingQueue<int[]>(32);
    volatile boolean done;
    volatile Throwable failure;
    volatile int matrices;
    volatile long checks;

    SnapshotMatcher(Rule[] rules, List<long[]> grids) {
        this.rules = rules.clone();
        this.grids = new long[grids.size()][];
        for (int i = 0; i < this.grids.length; i++) this.grids[i] = grids.get(i).clone();
    }

    int[] poll() { return ready.poll(); }

    @Override
    public void run() {
        try {
            for (long[] grid : grids) {
                List<Integer> candidates = new ArrayList<Integer>();
                for (int i = 0; i < rules.length; i++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    // Null means custom/unknown recipe: it must receive a real server-thread matches() call.
                    if (rules[i] == null || rules[i].possible(grid)) candidates.add(Integer.valueOf(i));
                    checks++;
                }
                int[] result = new int[candidates.size()];
                for (int i = 0; i < result.length; i++) result[i] = candidates.get(i).intValue();
                ready.put(result); // Bounded output; cancellation interrupts this wait too.
                matrices++;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            failure = error;
        } finally {
            done = true;
        }
    }
}
