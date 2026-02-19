import org.jetbrains.amper.plugins.*
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.util.jar.*
import kotlin.io.path.*

@TaskAction
@OptIn(ExperimentalPathApi::class)
fun createJmhJar(
    @Input moduleJar: CompilationArtifact, // Dep for
    @Input runtimeClasspath: Classpath, // to fat jar
    @Input jmhGeneratorClasspath: Classpath, // to generate bytecode
    @Output outputJar: Path, // benchmarks.jar to run
    @Input moduleRootDir: Path, // Hack that I like
    mainClass: String,
) {
    // benchmarks.jar
    outputJar.parent.createDirectories()

    // tmp basically
    val workDir = outputJar.parent.resolve("jmh-work").apply {
        deleteRecursively()
        createDirectories()
    }

    val compiledClassesDir = workDir.resolve("classes").apply { createDirectories() }
    // Deflate
    extractJar(moduleJar.artifact, compiledClassesDir)

    // Generate JMH entry point
    val generatorOutput = generateBenchmarkHarness(
        compiledClassesDir = compiledClassesDir,
        workDir = workDir,
        generatorClasspath = jmhGeneratorClasspath.resolvedFiles,
        compilationClasspath = jmhGeneratorClasspath.resolvedFiles + runtimeClasspath.resolvedFiles,
    )

    // Fat jar manifest
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
    }

    // Do not shade duplicates. TODO: Gradle has a strategy for this, figure the default
    val addedEntries = mutableSetOf<String>()
    // ServiceLoader services to merge
    val serviceFiles = mutableMapOf<String, MutableList<String>>()

    JarOutputStream(BufferedOutputStream(outputJar.outputStream()), manifest).use { jos ->
        val allJars = buildList {
            add(moduleJar.artifact)
            addAll(runtimeClasspath.resolvedFiles)
        }

        for (jar in allJars) {
            // TODO: no doc to resolvedFiles, have no idea what can be here
            if (!jar.exists() || !jar.toString().endsWith(".jar")) error("Invalid JAR file: $jar")

            JarFile(jar.toFile()).use { jf ->
                for (entry in jf.entries()) {
                    val name = entry.name
                    // Skip non-relocatable
                    if (shouldSkip(name)) continue

                    if (name.startsWith("META-INF/services/")) {
                        val lines = jf.getInputStream(entry).bufferedReader().readLines()
                        serviceFiles.getOrPut(name) { mutableListOf() }.addAll(lines)
                        continue
                    }

                    // Skip dupes
                    if (!addedEntries.add(name)) continue

                    jos.putNextEntry(JarEntry(name))
                    if (!entry.isDirectory) {
                        jf.getInputStream(entry).use { it.copyTo(jos) }
                    }
                    jos.closeEntry()
                }
            }
        }

        // Add generated classes (benchmark harnesses)
        addDirectoryToJar(jos, generatorOutput.classesDir, addedEntries)

        // Add generated resources (META-INF/BenchmarkList, META-INF/CompilerHints)
        addDirectoryToJar(jos, generatorOutput.resourcesDir, addedEntries)

        // Write merged service files
        for ((name, lines) in serviceFiles) {
            jos.putNextEntry(JarEntry(name))
            val content = lines.distinct().joinToString("\n", postfix = "\n")
            jos.write(content.toByteArray())
            jos.closeEntry()
        }
    }

    // Copy to project root
    val projectRoot = moduleRootDir.parent
    val finalJar = projectRoot.resolve("benchmarks.jar")
    outputJar.copyTo(finalJar, overwrite = true)

    println("Created JMH JAR: $finalJar (${finalJar.fileSize() / 1024} KB)")
    println("Run with: java -jar $finalJar")
}

private fun extractJar(jarPath: Path, targetDir: Path) {
    JarFile(jarPath.toFile()).use { jf ->
        for (entry in jf.entries()) {
            if (entry.isDirectory) continue
            val target = targetDir.resolve(entry.name)
            target.parent.createDirectories()
            jf.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

@OptIn(ExperimentalPathApi::class)
private fun addDirectoryToJar(jos: JarOutputStream, dir: Path, addedEntries: MutableSet<String>) {
    if (!dir.exists()) return
    dir.walk().filter { it.isRegularFile() }.forEach { file ->
        val name = dir.relativize(file).toString().replace('\\', '/')
        if (addedEntries.add(name)) {
            jos.putNextEntry(JarEntry(name))
            file.inputStream().use { it.copyTo(jos) }
            jos.closeEntry()
        }
    }
}

private fun shouldSkip(name: String): Boolean {
    if (name == "META-INF/MANIFEST.MF") return true
    if (name == "module-info.class") return true
    if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) return true
    return false
}
