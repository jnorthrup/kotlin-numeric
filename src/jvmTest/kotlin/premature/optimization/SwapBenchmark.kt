@file:OptIn(ExperimentalTime::class)

package premature.optimization

import org.junit.jupiter.api.Disabled
import java.nio.IntBuffer
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * "Bubble" swap elevator testing `if (last != first) throw Error("swap elevator fail")`
 */
class SwapBenchmarkTest {

    @Test
    @Disabled
    fun testSwapping() {
        for (rep in 1..10) {
            println(
                "\n$rep+++++++++++++++++++++++\n$rep+++++++++++++++++++++++\n$rep+++++++++++++++++++++++\n" +
                    "$rep+++++++++++++++++++++++\n$rep+++++++++++++++++++++++\n"
            )

            var digits = 6
            while (digits < 10) {
                val size = "1E$digits".toDouble().toInt()
                val current = ThreadLocalRandom.current()
                val x = IntArray(size) { current.nextInt() }
                val entries: List<Pair<Swapper, Long>> = Swapper.values().map { swapper ->
                    val first = x[0]
                    val second = x[1]
                    val begin = TimeSource.Monotonic.markNow()
                    swapper.swap(x)
                    val l = begin.elapsedNow().inWholeNanoseconds
                    val last = x[size - 1]
                    assertEquals(last, first, "swap elevator failed in $swapper")
                    assertEquals(second, x[0], "swap results corrupted in $swapper")
                    swapper to l
                }
                println("---- for $size")
                entries.sortedBy { it.second }.forEach { (key, value) ->
                    println(
                        "$key: $size:  ${value}\t${
                        (
                            size.toDouble() /
                                value * 1000
                            ).toString().take(7)
                        }iter/ms\t" +
                            "${value.toDouble() / size.toDouble()}ns/ea"
                    )
                }
                digits += 1
            }
        }
    }

    internal enum class Swapper {

        /**
         * creates a long, and pushes to it and swaps and pulls from it.
         *
         * shows an overhead only, over the same swap code without the bitops
         */
        r64shift {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val t = x[i].toLong() and 0xffff_ffffL shl 32 or ((x[j].toLong() and 0xffff_ffffL))
                    x[i] = (t and 0xffff_ffffL).toInt()
                    x[j] = (t ushr 32).toInt()
                }
            }
        },

        /**
         * xor swap
         */
        xor_swap {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    x[i] = x[i] xor x[j]
                    x[j] = x[j] xor x[i]
                    x[i] = x[i] xor x[j]
                }
            }
        },

        /**
         * subtraction swap
         */
        sub_swap {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    x[i] = x[i] + x[j]
                    x[j] = x[i] - x[j]
                    x[i] = x[i] - x[j]
                }
            }
        },

        /**
         * creates a tmp, swaps two array items
         */
        tmp1swap {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val t = x[i]
                    x[i] = x[j]
                    x[j] = t
                }
            }
        },

        /**
         * creates 2 tmps, swaps two array items
         */
        tmp2swap {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val x1 = x[j]
                    val t = x[i]
                    x[i] = x1
                    x[j] = t
                }
            }
        },

        /**
         * we get some kind of optimization from creating two temps and swapping them and writing them, on my (skylake)
         * cpu slightly tweaked by the read and write order from the array.
         */
        xor_tmps {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    var x1 = x[i]
                    var x2 = x[j]
                    x1 = x1 xor x2
                    x2 = x2 xor x1
                    x1 = x1 xor x2
                    x[i] = x1
                    x[j] = x2
                }
            }
        },
        xor_vals {
            override fun swap(x: IntArray) {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val x1 = x[i]
                    val x2 = x[j]
                    val y1 = x1 xor x2
                    val y2 = x2 xor y1
                    val z1 = y1 xor y2
                    x[i] = z1
                    x[j] = y2
                }
            }
        },

        /**
         * heapbytebuffer random access get and put.  roughly the same as tmp swap
         */
        hib_racc {
            override fun swap(x: IntArray) {
                val wrap = IntBuffer.wrap(x)
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val xi = wrap[i]
                    wrap.put(i, wrap[j])
                    wrap.put(j, xi)
                }
            }
        },

        /**
         * heapbytebuffer using mark and rewind naively
         */
        hib_mark {
            override fun swap(x: IntArray) {
                val wrap = IntBuffer.wrap(x).mark() as IntBuffer
                while (wrap.remaining() > 1) {
                    val i = (wrap.mark() as IntBuffer).get()
                    val j = wrap.get()
                    wrap.reset()
                    wrap.put(j)
                    (wrap.mark() as IntBuffer).put(i).reset()
                }
            }
        },

        /**
         * heap bytebuffer calling position instead of mark
         */
        hib_cpos {
            override fun swap(x: IntArray) {
                val wrap: IntBuffer = IntBuffer.wrap(x)
                while (wrap.remaining() > 1) {
                    val mark = wrap.position()
                    val i = wrap.get()
                    val j = wrap.get()
                    (wrap.position(mark) as IntBuffer).put(j)
                    wrap.put(i).position(mark + 1)
                }
            }
        },

        /**
         * 4 forward moving get/put heapbytebuffers particular to this benchmark
         */
        hib_4way {
            override fun swap(x: IntArray) {
                val write2 = IntBuffer.wrap(x)
                val write1 = write2.slice()
                val lead = write2.slice()
                val trail = write2.slice()
                lead.position(1)
                write2.position(1)

                var i: Int
                var j: Int
                while (write2.remaining() > 0/*hasRemaining()*/) {
                    i = lead.get()
                    j = trail.get()
                    write1.put(i)
                    write2.put(j)
                }
            }
        };

        abstract fun swap(x: IntArray)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SwapBenchmarkTest().testSwapping()
        }
    }
}
