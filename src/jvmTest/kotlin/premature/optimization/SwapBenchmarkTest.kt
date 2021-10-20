@file:OptIn(ExperimentalTime::class, ExperimentalStdlibApi::class)

package premature.optimization

import org.junit.jupiter.api.Disabled
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.nio.IntBuffer
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
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
                    val ovhead=swapper.swap(x)
                    val l = (begin.elapsedNow()-ovhead).inWholeNanoseconds
                    val last = x[size - 1]
                    assertEquals(last, first, "swap elevator failed in $swapper")
                    assertEquals(second, x[0], "swap results corrupted in $swapper")
                    swapper to l
                }
                println("---- for $size")
                entries.sortedBy { it.second }.forEach { (key, value) ->
                    println("$key: $size:  ${value}\t${
                        (size.toDouble() / value * 1000).toString().take(7)
                    }iter/ms\t${value.toDouble() / size.toDouble()}ns/ea")
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
            override fun swap(x: IntArray): Duration {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val t = x[i].toLong() and 0xffff_ffffL shl 32 or ((x[j].toLong() and 0xffff_ffffL))
                    x[i] = (t and 0xffff_ffffL).toInt()
                    x[j] = (t ushr 32).toInt()
                }
                return Duration.ZERO
            }
        },

        /**
         * xor swap
         */
        xor_swap {
            override fun swap(x: IntArray): Duration {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    x[i] = x[i] xor x[j]
                    x[j] = x[j] xor x[i]
                    x[i] = x[i] xor x[j]
                }
                return Duration.ZERO
            }
        },

        /**
         * subtraction swap
         */
        sub_swap {
            override fun swap(x: IntArray): Duration {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    x[i] = x[i] + x[j]
                    x[j] = x[i] - x[j]
                    x[i] = x[i] - x[j]
                }
                return Duration.ZERO
            }
        },

        /**
         * creates a tmp, swaps two array items
         */
        tmp1swap {
            override fun swap(x: IntArray): Duration {
                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val t = x[i]
                    x[i] = x[j]
                    x[j] = t
                }
                return Duration.ZERO
            }
        },

        /**
         * creates 2 tmps, swaps two array items
         */
        tmp2swap {
            override fun swap(x: IntArray): Duration {

                for (i in 0 until x.size - 1) {
                    val j = i + 1
                    val x1 = x[j]
                    val t = x[i]
                    x[i] = x1
                    x[j] = t
                }
                return Duration.ZERO

            }
        },

        /**
         * we get some kind of optimization from creating two temps and swapping them and writing them, on my (skylake)
         * cpu slightly tweaked by the read and write order from the array.
         */
        xor_tmps {
            override fun swap(x: IntArray): Duration {
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
                return Duration.ZERO
            }
        },
        xor_vals {
            override fun swap(x: IntArray): Duration {
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
                return Duration.ZERO
            }
        },

        /**
         * heapbytebuffer random access get and put.  roughly the same as tmp swap
         */
        hib_racc {
            override fun swap(x: IntArray): Duration {
                val markNow = TimeSource.Monotonic.markNow()
                val wrap: IntBuffer = IntBuffer.wrap(x)
                return markNow.elapsedNow().also {
                    for (i in 0 until x.size - 1) {
                        val j = i + 1
                        val xi = wrap[i]
                        wrap.put(i, wrap[j])
                        wrap.put(j, xi)
                    }
                }
            }
        },

        /**
        digging around for sun.misc.unsafe options
         */
        longr32 {
            override fun swap(x: IntArray): Duration {
                val markNow = TimeSource.Monotonic.markNow()
                val intArrSize = x.size * Int.SIZE_BYTES
                val theUnsafe: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
                theUnsafe.isAccessible = true
                val unsafe: Unsafe = theUnsafe.get(null) as Unsafe
                val ptr: Long = unsafe.allocateMemory(intArrSize.toLong())

                for (i in 0 until intArrSize) unsafe.putInt(ptr + i * 4, x[i])

                val setupCost = markNow.elapsedNow()


                /*
                 * perform the most unfair possible long swap in place
                 */
                for (i in 0 until intArrSize - 1) {
                    unsafe.putLong(ptr + i * 4, unsafe.getLong(ptr + i * 4).rotateLeft(32))
                }


                /*
                 * copy back the ints to the array
                 */
                val markNow1 = TimeSource.Monotonic.markNow()
                for (i in 0 until intArrSize) {
                    x[i] = unsafe.getInt(ptr + i * 4)
                }
                return (markNow1.elapsedNow() + setupCost)

            }
        },

        /**
         * heapbytebuffer using mark and rewind naively
         */
        hib_mark {
            override fun swap(x: IntArray): Duration {
                val markNow = TimeSource.Monotonic.markNow()
                val wrap: IntBuffer = IntBuffer.wrap(x)
                return markNow.elapsedNow().also {
                    while (wrap.remaining() > 1) {
                        val i = (wrap.mark() as IntBuffer).get()
                        val j = wrap.get()
                        wrap.reset()
                        wrap.put(j)
                        (wrap.mark() as IntBuffer).put(i).reset()
                    }
                }
            }
        },

        /**
         * heap bytebuffer calling position instead of mark
         */
        hib_cpos {
            override fun swap(x: IntArray): Duration {
                val markNow = TimeSource.Monotonic.markNow()
                val wrap: IntBuffer = IntBuffer.wrap(x)
                val ret = markNow.elapsedNow()

                while (wrap.remaining() > 1) {
                    val mark = wrap.position()
                    val i = wrap.get()
                    val j = wrap.get()
                    (wrap.position(mark) as IntBuffer).put(j)
                    wrap.put(i).position(mark + 1)
                }
                return ret
            }
        },

        /**
         * 4 forward moving get/put heapbytebuffers particular to this benchmark
         */
        hib_4way {
            override fun swap(x: IntArray): Duration {
                val markNow = TimeSource.Monotonic.markNow()
                val write2 = IntBuffer.wrap(x)
                val write1 = write2.slice()
                val lead = write2.slice()
                val trail = write2.slice()
                val ret = markNow.elapsedNow()

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
                return ret
            }
        };


        /**
         * we will add setup/teardown expenses as a return value.
         */
        abstract fun swap(x: IntArray): Duration
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SwapBenchmarkTest().testSwapping()
        }
    }
}

object Test {
    private const val N = 128 * 1024 * 1024

    @Throws(NoSuchFieldException::class,
        SecurityException::class,
        IllegalArgumentException::class,
        IllegalAccessException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        run {
            val theUnsafe: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe: Unsafe = theUnsafe.get(null) as Unsafe
            val astart = System.nanoTime()
            val ptr: Long = unsafe.allocateMemory(N.toLong())
            val aend = System.nanoTime()
            System.out.printf("Unsafe alloc took %10s nano seconds%n", aend - astart)
            val wstart = System.nanoTime()
            for (i in 0 until N) {
                unsafe.putByte(ptr + i, 1.toByte())
            }
            val wend = System.nanoTime()
            System.out.printf("Unsafe write took %10s nano seconds%n", wend - wstart)
            val rstart = System.nanoTime()
            var s = 0
            for (i in 0 until N) {
                s += unsafe.getByte(ptr + i)
            }
            val rend = System.nanoTime()
            System.out.printf("Unsafe  read took %10s nano seconds%n", rend - rstart)
            unsafe.freeMemory(ptr)
            println(s)
        }
        run {
            val astart = System.nanoTime()
            val array = ByteArray(N)
            val aend = System.nanoTime()
            System.out.printf("Array  alloc took %10s nano seconds%n", aend - astart)
            val wstart = System.nanoTime()
            for (i in 0 until N) {
                array[i] = 1.toByte()
            }
            val wend = System.nanoTime()
            System.out.printf("Array  write took %10s nano seconds%n", wend - wstart)
            val rstart = System.nanoTime()
            var s = 0
            for (i in 0 until N) {
                s += array[i].toInt()
            }
            val rend = System.nanoTime()
            System.out.printf("Array   read took %10s nano seconds%n", rend - rstart)
            println(s)
        }

//		Unsafe alloc took      16142 nano seconds
//		Unsafe write took  120145208 nano seconds
//		Unsafe  read took  114300970 nano seconds
//		134217728
//		Array  alloc took   42636826 nano seconds
//		Array  write took   20694570 nano seconds
//		Array   read took   63825587 nano seconds
//		134217728
    }
}