package benchmark

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
open class ArrayBenchmark {

    @Volatile
    var sz = 1024

    object filler

    @Benchmark
    fun initPrimitive(): IntArray {
        return IntArray(sz) { 42 }
    }

    @Benchmark
    fun fillPrimitive(): IntArray {
        val arr = IntArray(sz)
        arr.fill(42)
        return IntArray(sz).also { it.fill(42) }
    }

    @Benchmark
    fun initAny(): Array<Any?> {
        return Array(sz) { filler }
    }

    @Benchmark
    fun fillAny(): Array<Any?> {
        val arr = arrayOfNulls<Any?>(sz)
        arr.fill(filler)
        return arr
    }
}
