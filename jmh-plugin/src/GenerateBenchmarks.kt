import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Runs the JMH bytecode generator on compiled classes and compiles the generated harness sources.
 *
 * Returns a [GeneratedBenchmarkOutput] containing paths to the generated classes and resources
 * that must be included in the fat JAR.
 */
fun generateBenchmarkHarness(
    compiledClassesDir: Path,
    workDir: Path,
    generatorClasspath: List<Path>,
    compilationClasspath: List<Path>,
): GeneratedBenchmarkOutput {
    val generatedSourcesDir = workDir.resolve("generated-sources").apply { createDirectories() }
    val generatedResourcesDir = workDir.resolve("generated-resources").apply { createDirectories() }
    val generatedClassesDir = workDir.resolve("generated-classes").apply { createDirectories() }

    // Step 1: Run JmhBytecodeGenerator via reflection
    runBytecodeGenerator(
        compiledClassesDir = compiledClassesDir,
        generatedSourcesDir = generatedSourcesDir,
        generatedResourcesDir = generatedResourcesDir,
        generatorClasspath = generatorClasspath,
    )

    // Step 2: Compile generated Java sources
    val generatedJavaFiles = generatedSourcesDir.toFile()
        .walk()
        .filter { it.extension == "java" }
        .toList()

    if (generatedJavaFiles.isEmpty()) {
        println("WARNING: JMH bytecode generator produced no sources. Are there @Benchmark methods?")
        return GeneratedBenchmarkOutput(generatedClassesDir, generatedResourcesDir)
    }

    println("Compiling ${generatedJavaFiles.size} generated JMH harness source(s)...")
    compileGeneratedSources(
        javaFiles = generatedJavaFiles.map { it.toPath() },
        outputDir = generatedClassesDir,
        classpath = compilationClasspath + listOf(compiledClassesDir),
    )

    return GeneratedBenchmarkOutput(generatedClassesDir, generatedResourcesDir)
}

data class GeneratedBenchmarkOutput(
    val classesDir: Path,
    val resourcesDir: Path,
)

private fun runBytecodeGenerator(
    compiledClassesDir: Path,
    generatedSourcesDir: Path,
    generatedResourcesDir: Path,
    generatorClasspath: List<Path>,
) {
    // Build URLClassLoader with generator jars + compiled classes (generator needs to load them)
    val urls = (generatorClasspath + listOf(compiledClassesDir)).map { it.toUri().toURL() }.toTypedArray()
    val classLoader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

    val previousContextCL = Thread.currentThread().contextClassLoader
    try {
        Thread.currentThread().contextClassLoader = classLoader

        val generatorClass = classLoader.loadClass("org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator")
        val mainMethod = generatorClass.getMethod("main", Array<String>::class.java)

        val args = arrayOf(
            compiledClassesDir.toString(),
            generatedSourcesDir.toString(),
            generatedResourcesDir.toString(),
            "asm",
        )

        println("Running JMH bytecode generator on $compiledClassesDir...")
        mainMethod.invoke(null, args)
    } finally {
        Thread.currentThread().contextClassLoader = previousContextCL
        classLoader.close()
    }
}

private fun compileGeneratedSources(
    javaFiles: List<Path>,
    outputDir: Path,
    classpath: List<Path>,
) {
    val javacPath = findJavac()
    val classpathString = classpath.joinToString(java.io.File.pathSeparator)

    outputDir.createDirectories()

    val args = buildList {
        add(javacPath.toString())
        add("--release")
        add("8")
        add("-d")
        add(outputDir.toString())
        add("-classpath")
        add(classpathString)
        addAll(javaFiles.map { it.toString() })
    }

    val process = ProcessBuilder(args)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("javac failed (exit code $exitCode):\n$output")
    }
}

private fun findJavac(): Path {
    val javaHome = Path(System.getProperty("java.home"))

    // Direct: java.home/bin/javac (if running on a JDK)
    javaHome.resolve("bin/javac").takeIf { it.exists() }?.let { return it }

    // JRE inside JDK: java.home/../bin/javac
    javaHome.parent?.resolve("bin/javac")?.takeIf { it.exists() }?.let { return it }

    // Amper cache: look for a JDK/JBR sibling in the same cache directory
    // e.g. java.home = .../Amper/zulu-jre/... -> look for .../Amper/jbr-*/...
    val amperCache = generateSequence(javaHome) { it.parent }
        .firstOrNull { it.name == "Amper" }
    if (amperCache != null) {
        amperCache.listDirectoryEntries("jbr-*").firstNotNullOfOrNull { jbr ->
            jbr.toFile().walk().filter { it.name == "javac" && it.canExecute() }
                .firstOrNull()?.toPath()
        }?.let { return it }
    }

    // Fallback: javac on PATH
    val which = ProcessBuilder("which", "javac").start()
    val result = which.inputStream.bufferedReader().readText().trim()
    if (which.waitFor() == 0 && result.isNotEmpty()) {
        return Path(result)
    }

    error("Cannot find javac. Ensure a JDK is available.")
}
