import org.jetbrains.amper.plugins.Configurable

@Configurable
interface JmhSettings {
    val mainClass: String get() = "org.openjdk.jmh.Main"
}
