package premature.optimization;

import java.nio.IntBuffer;
import java.util.*;


public class SwapBenchmark {
    static Random random = new Random();

    public static void main(String[] args) {
        for (var rep = 1; rep < 11; rep++) {
            Comparator<Map.Entry> entryComparator = (entry, t1) -> (int) ((long) entry.getValue() - (long) t1.getValue());
            for (var digits = 6; digits < 10; digits += 1) {
                ArrayList<Map.Entry<Swapper, Long>> entries = new ArrayList<>();
                var size = (int) Double.parseDouble("1E" + digits);
                var x = new int[size];
                for (var i = 0; i < x.length; i++) x[i] = random.nextInt();
                for (var swapper : Swapper.values()) {
                    final var first = x[0];
                    var begin = System.currentTimeMillis();
                    swapper.swap(x);
                    var last = x[size - 1];
                    if (last != first) throw new Error("swap elevator fail");
                    var l = System.currentTimeMillis() - begin;
                    entries.add(new AbstractMap.SimpleEntry<>(swapper, l));

                }

                entries.sort(entryComparator);
                System.err.println("---- for " + size);
                for (Map.Entry<Swapper, Long> entry : entries) {
                    Swapper key = entry.getKey();
                    Long value = entry.getValue();
                    System.err.println(key + ": " + size + ":  " + value + " @" + (double) size / value + "/ms");
                }
                System.err.println();
            }
        }
    }


    enum Swapper {
        /**
         * xor swap
         */
        xor_swap() {
            void swap(int[] x) {
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
                    x[i] ^= x[j];
                    x[j] ^= x[i];
                    x[i] ^= x[j];
                }
            }
        },
        /**
         * creates a long, and pushes to it
         */
        r64shift {
            @Override
            void swap(int[] x) {
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
                    var t = ((long) x[i] & 0xffff_ffffL) << 32 | (x[j] & 0xffff_ffffL);
                    x[i] = (int) (t & 0xffff_ffffL);
                    x[j] = (int) (t >>> 32);
                    assert x[i] == x[j];
                    assert x[j] == x[i];
                }
            }
        },
        /**
         * creates a tmp, swaps two array items
         */
        tmp1swap {
            @Override
            void swap(int[] x) {
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
                    final var t = x[i];
                    x[i] = x[j];
                    x[j] = t;
                }
            }
        },
        /**
         * creates 2 tmps, swaps two array items
         */
        tmp2swap {
            @Override
            void swap(int[] x) {
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
                    final var x1 = x[j];
                    final var t = x[i];
                    x[i] = x1;
                    x[j] = t;
                }
            }
        },
        /**
         * we get some kind of optimization from creating two temps and swapping them and writing them, ony my cpu slightly tweaked by the read and write order from the array.
         */
        xor_tmps {
            @Override
            void swap(int[] x) {
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
//                    int v2, x2 =v2= x[j];
//                    int v1, x1 =v1= x[i];
                    var x2 = x[j];
                    var x1 = x[i];
                    x1 ^= x2;
                    x2 ^= x1;
                    x1 ^= x2;
                    x[i] = x1;
                    x[j] = x2;
//                    assert v1 == x[j];
//                    assert v2 == x[i];
                }
            }
        },
        /**
         * heapbytebuffer random access get and put.  roughly the same as tmp swap
         */
        hbb_racc {
            void swap(int[] x) {
                var wrap = IntBuffer.wrap(x);
                for (var i = 0; i < x.length - 1; i++) {
                    final var j = i + 1;
                    var xi = wrap.get(i);
                    wrap.put(i, wrap.get(j));
                    wrap.put(j, xi);
                }
            }
        },
        /**
         * heapbytebuffer using mark and rewind naively
         */
        hbb_mark {
            void swap(int[] x) {
//                int l1 = x[0];
//                int li = x[x.length - 2];
//                int lj = x[x.length - 1];
                var wrap = IntBuffer.wrap(x).mark();
                while (wrap.remaining() > 1) {
                    var i = wrap.mark().get();
                    var j = wrap.get();
                    wrap.reset();
                    wrap.put(j);
                    wrap.mark().put(i).reset();
                }
            }
        },
        /**
         * heap bytebuffer calling position instead of mark
         */
        hbb_cpos {
            void swap(int[] x) {
//                int l1 = x[0];
//                int li = x[x.length - 2];
//                int lj = x[x.length - 1];
                var wrap = IntBuffer.wrap(x);
                while (wrap.remaining() > 1) {
                    var mark = wrap.position();
                    var i = wrap.get();
                    var j = wrap.get();
                    wrap.position(mark).put(j);
                    wrap.put(i).position(mark + 1);
                }
            }
        },
        /**
         * 4 forward moving get/put heapbytebuffers particular to this benchmark
         */
        hbb_4way {
            void swap(int[] x) {

//                int l1 = x[0];
//                int li = x[x.length - 2];
//                int lj = x[x.length - 1];
                var write2 = IntBuffer.wrap(x);
                var write1 = write2.slice();

                var lead = write2.slice();
                var trail = write2.slice();

                lead.position(1);
                write2.position(1);
                var i = 0;
                var j = 0;
                while (write2.remaining() > 0) {
                    i = lead.get();
                    j = trail.get();
                    write1.put(i);
                    write2.put(j);
                }
//                int x1 = x[x.length - 1];
//                System.err.println(Arrays.asList(i,j, x1));
            }
        };

        abstract void swap(int[] x);
    }
}
