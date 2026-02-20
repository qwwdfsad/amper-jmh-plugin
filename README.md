# JMH Plugin for Amper

An [Amper](https://amper.org) plugin that adds [JMH](https://github.com/openjdk/jmh) support. Write benchmarks with standard JMH annotations, build a self-contained benchmark JAR with a single command.

## Quick Start

### 1. Add the plugin to your project

```yaml
# project.yaml
modules:
  - app
  - jmh-plugin

plugins:
  - ./jmh-plugin
```

### 2. Enable the plugin and add JMH dependency in your module

```yaml
# app/module.yaml
product: jvm/app

dependencies:
  - org.openjdk.jmh:jmh-core:1.37

plugins:
  jmh-plugin: enabled
```

### 3. Write a benchmark

```kotlin
// app/src/MyBenchmark.kt
package benchmark

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
open class MyBenchmark {

    @Benchmark
    fun benchmarkSomething(): Int {
        return (1..100).sum()
    }
}
```

### 4. Build and run

```bash
./amper task :app:jmh-jar@jmh-plugin
java -jar benchmarks.jar
```
