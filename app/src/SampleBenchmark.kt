package benchmark

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
open class SampleBenchmark {

    @Benchmark
    fun returnString(): String {
        return "Amper!"
    }
}
