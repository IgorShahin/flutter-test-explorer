package dev.igorshahin.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Flutter 95 exposes additional test arguments as a String and splits that value on literal spaces.
 * Encode each logical token for that narrow native field, then restore the tokens on the public
 * GeneralCommandLine boundary immediately before ProcessBuilder starts. The official Flutter run
 * configuration, runner, console and rerun lifecycle remain unchanged.
 */
object FlutterArgumentTransport {
    private const val PREFIX = "--__flutter_test_explorer_argument_v1="
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeForNativeField(arguments: List<String>): String = arguments.joinToString(" ", transform = ::encode)

    fun decodeNativeField(arguments: String?): List<String> =
        expand(GlobalTestArguments.parseLegacy(arguments.orEmpty()))

    internal fun containsEncoded(arguments: List<String>): Boolean = arguments.any(::isEncoded)

    internal fun expand(arguments: List<String>): List<String> = arguments.map { decode(it) ?: it }

    internal fun encode(argument: String): String =
        PREFIX + encoder.encodeToString(argument.toByteArray(StandardCharsets.UTF_8))

    internal fun decode(argument: String): String? {
        if (!isEncoded(argument)) return null
        return runCatching {
            String(decoder.decode(argument.removePrefix(PREFIX)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun isEncoded(argument: String): Boolean = argument.startsWith(PREFIX)
}

/** Registered once at application level; inert for command lines without our encoded tokens. */
class FlutterTestArgumentCommandLineCustomizer : CommandLineEnvCustomizer {
    override fun customizeEnv(commandLine: GeneralCommandLine, environment: MutableMap<String, String>) {
        if (!FlutterArgumentTransport.containsEncoded(commandLine.parametersList.list)) return
        if (commandLine.getUserData(INSTALLED_KEY) == true) return
        check(!commandLine.isProcessCreatorSet) {
            "Cannot safely add Test Explorer arguments because another process customizer is already installed."
        }
        commandLine.putUserData(INSTALLED_KEY, true)
        commandLine.setProcessCreator { builder ->
            val expanded = FlutterArgumentTransport.expand(builder.command())
            builder.command(expanded)
            if (LOG.isDebugEnabled) {
                LOG.debug("Test Explorer Flutter process argv: ${expanded.joinToString(prefix = "[", postfix = "]")}")
            }
            builder.start()
        }
    }

    private companion object {
        val INSTALLED_KEY = Key.create<Boolean>("flutter.test.explorer.argument.transport.installed")
        val LOG = Logger.getInstance(FlutterTestArgumentCommandLineCustomizer::class.java)
    }
}
