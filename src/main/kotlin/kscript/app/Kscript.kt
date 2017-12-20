package kscript.app

import kscript.app.ShellUtils.requireInPath
import org.docopt.DocOptWrapper
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URL
import java.net.UnknownHostException
import kotlin.system.exitProcess


/**
 * A kscript - Scripting enhancements for Kotlin
 *
 * For details and license see https://github.com/holgerbrandl/kscript
 *
 * @author Holger Brandl
 */

const val KSCRIPT_VERSION = "2.3.0"

val selfName = System.getenv("CUSTOM_KSCRIPT_NAME") ?: "kscript"

val USAGE = """
$selfName - Enhanced scripting support for Kotlin on *nix-based systems.

Usage:
 $selfName [options] <script> [<script_args>]...
 $selfName --clear-cache
 $selfName --self-update

The <script> can be a  script file (*kts), a script URL, - for stdin, a *.kt source file with a main method, or some kotlin code.

Use '--clear-cache' to wipe cached script jars and urls
Use '--self-update' to update kscript to the latest version

Options:
 -i --interactive        Create interactive shell with dependencies as declared in script
 -t --text               Enable stdin support API for more streamlined text processing
 --idea                  Open script in temporary Intellij session
 -s --silent             Suppress status logging to stderr

Copyright : 2017 Holger Brandl
License   : MIT
Version   : v$KSCRIPT_VERSION
Website   : https://github.com/holgerbrandl/kscript
""".trim()

val HOME_PATH = System.getenv("HOME") // *nix
	?: System.getenv("HOMEPATH") // windows
	?: throw IllegalStateException("Can't figure out home path")

val KSCRIPT_CACHE_DIR = File(HOME_PATH, ".kscript")
val SCRIPT_TEMP_DIR = createTempDir()

fun main(args: Array<String>) {
    // skip org.docopt for version and help to allow for lazy version-check
    if (args.size == 1 && listOf("--help", "-h", "--version", "-v").contains(args[0])) {
        info(USAGE)
        versionCheck()
        quit(0)
    }

    // note: with current impt we still don't support `kscript -1` where "-1" is a valid kotlin expression
    val userArgs = args.dropWhile { it.startsWith("-") }.drop(1)
    val kscriptArgs = args.take(args.size - userArgs.size)

    val docopt = DocOptWrapper(kscriptArgs, USAGE)
    val loggingEnabled = !docopt.getBoolean("silent")


    // create cache dir if it does not yet exist
    if (!KSCRIPT_CACHE_DIR.isDirectory) {
        KSCRIPT_CACHE_DIR.mkdir()
    }

    // optionally clear up the jar cache
    if (docopt.getBoolean("clear-cache")) {
        info("Cleaning up cache...")
        KSCRIPT_CACHE_DIR.listFiles().forEach { it.delete() }
        //        evalBash("rm -f ${KSCRIPT_CACHE_DIR}/*")
        quit(0)
    }

    // optionally self-update kscript ot the newest version
    // (if not local copy is not being maintained by sdkman)
    if (docopt.getBoolean(("self-update"))) {
        if (true || evalBash("which kscript | grep .sdkman").stdout.isNotBlank()) {
            info("Installing latest version of kscript...")
            //            println("sdkman_auto_answer=true && sdk install kscript")

            // create update script
            val updateScript = File(KSCRIPT_CACHE_DIR, "self_update.sh").apply {
                writeText("""
                #!/usr/bin/env bash
                export SDKMAN_DIR="${"$"}{HOME}/.sdkman"
                source "${"$"}{SDKMAN_DIR}/bin/sdkman-init.sh"
                sdkman_auto_answer=true && sdk install kscript
                """.trimIndent())
                setExecutable(true)
            }

            println(updateScript.absolutePath)
        } else {
            info("Self-update is currently just supported via sdkman.")
            info("Please download a new release from https://github.com/holgerbrandl/kscript")
            // todo port sdkman-indpendent self-update
        }

        quit(0)
    }


    // Resolve the script resource argument into an actual file
    val scriptResource = docopt.getString("script")
    val scriptFile = prepareScript(scriptResource, enableSupportApi = docopt.getBoolean("text"))

    val script = Script(scriptFile)


    // Find all //DEPS directives and concatenate their values
    val dependencies = script.collectDependencies()
    val customRepos = script.collectRepos()


    //  Create temopary dev environment
    if (docopt.getBoolean("idea")) {
        println(launchIdeaWithKscriptlet(scriptFile, dependencies, customRepos))
        exitProcess(0)
    }


    val classpath = resolveDependencies(dependencies, customRepos, loggingEnabled)

    // Extract kotlin arguments
    val kotlinOpts = script.collectRuntimeOptions()


    //  Optionally enter interactive mode
    if (docopt.getBoolean("interactive")) {
        System.err.println("Creating REPL from ${scriptFile}")
        //        System.err.println("kotlinc ${kotlinOpts} -classpath '${classpath}'")

        println("kotlinc ${kotlinOpts} -classpath ${classpath}")
        exitProcess(0)
    }

    val scriptFileExt = scriptFile.extension
    val scriptCheckSum = md5(scriptFile)


    // Even if we just need and support the //ENTRY directive in case of kt-class
    // files, we extract it here to fail if it was used in kts files.
    val entryDirective = script.findEntryPoint()

    errorIf(entryDirective != null && scriptFileExt == "kts") {
        "@Entry directive is just supported for kt class files"
    }


    val jarFile = if (scriptFile.nameWithoutExtension.endsWith(scriptCheckSum)) {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + ".jar")
    } else {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + "." + scriptCheckSum + ".jar")
    }

    // Capitalize first letter and get rid of dashes (since this is what kotlin compiler is doing for the wrapper to create a valid java class name)
    val className = scriptFile.nameWithoutExtension
        .replace("[.-]".toRegex(), "_")
        .capitalize()


    // Define the entrypoint for the scriptlet jar
    val execClassName = if (scriptFileExt == "kts") {
        "Main_${className}"
    } else {
        // extract package from kt-file
        """${script.pckg ?: ""}${entryDirective ?: "${className}Kt"}"""
    }


    // infer KOTLIN_HOME if not set
    val KOTLIN_HOME = System.getenv("KOTLIN_HOME") ?: guessKotlinHome()

    errorIf(KOTLIN_HOME == null) {
        "KOTLIN_HOME is not set and could not be inferred from context"
    }


    // If scriplet jar ist not cached yet, build it
    if (!jarFile.isFile) {
        // disabled logging because it seems too much
        // if(loggingEnabled) System.err.print("[kscript] Building script jar...")

        // disabled because a user might have same-named scripts for different projects
        // // remove previous (now outdated) cache jars
        // KSCRIPT_CACHE_DIR.listFiles({
        //     file -> file.name.startsWith(scriptFile.nameWithoutExtension) && file.extension=="jar"
        // }).forEach { it.delete() }


        requireInPath("kotlinc")


        // create main-wrapper for kts scripts

        val wrapperSrcArg = if (scriptFileExt == "kts") {
            val mainKotlin = File(createTempDir("kscript"), execClassName + ".kt")

            val classReference = (script.pckg ?: "") + className

            mainKotlin.writeText("""
            class Main_${className}{
                companion object {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        val script = Main_${className}::class.java.classLoader.loadClass("${classReference}")
                        script.getDeclaredConstructor(Array<String>::class.java).newInstance(args);
                    }
                }
            }
            """.trimIndent())

            "'${mainKotlin.absolutePath}'"
        } else {
            ""
        }

        val scriptCompileResult = evalBash("kotlinc -classpath '$classpath' -d '${jarFile.absolutePath}' '${scriptFile.absolutePath}' ${wrapperSrcArg}")
        with(scriptCompileResult) {
            errorIf(exitCode != 0) { "compilation of '$scriptResource' failed\n$stderr" }
        }
    }


    // print the final command to be run by exec
    val joinedUserArgs = userArgs.joinToString(" ")

    println("kotlin ${kotlinOpts} -classpath ${jarFile}${CP_SEPARATOR_CHAR}${KOTLIN_HOME}${File.separatorChar}lib${File.separatorChar}kotlin-script-runtime.jar${CP_SEPARATOR_CHAR}${classpath} ${execClassName} ${joinedUserArgs} ")
}


/** Determine the latest version by checking github repo and print info if newer version is available. */
private fun versionCheck() {

    //    val latestVersion = fetchFromURL("https://git.io/v9R73")?.useLines {
    //    val kscriptRawReleaseURL= "https://git.io/v9R73"
    // todo use the actual kscript.app.Kscript.kt here to infer version
    val kscriptRawReleaseURL = "https://raw.githubusercontent.com/holgerbrandl/kscript/releases/kscript"

    val latestVersion = try {
        BufferedReader(InputStreamReader(URL(kscriptRawReleaseURL).openStream())).useLines {
            it.first { it.startsWith("KSCRIPT_VERSION") }.split("=")[1]
        }
    } catch (e: UnknownHostException) {
        return // skip version check here, since the use has no connection to the internet at the moment
    }

    fun padVersion(version: String) = java.lang.String.format("%03d%03d%03d", *version.split(".").map { Integer.valueOf(it) }.toTypedArray())

    if (padVersion(latestVersion) > padVersion(KSCRIPT_VERSION)) {
        info("""A new version (v${latestVersion}) of kscript is available. Use 'kscript --self-update' to update your local kscript installation""")
    }
}


fun prepareScript(scriptResource: String, enableSupportApi: Boolean): File {
    var scriptFile: File?

    // map script argument to script file
    scriptFile = with(File(scriptResource)) {
        if (!canRead()) {
            // not a file so let's keep the script-file undefined here
            null
        } else if (listOf("kts", "kt").contains(extension)) {
            // script input is a regular script or clas file
            this
        } else {
            // if we can "just" read from script resource create tmp file
            // i.e. script input is process substitution file handle
            // not FileInputStream(this).bufferedReader().use{ readText()} does not work nor does this.readText
            createTmpScript(FileInputStream(this).bufferedReader().readText())
        }
    }

    // support stdin
    if (scriptResource == "-" || scriptResource == "/dev/stdin") {
        val scriptText = generateSequence() { readLine() }.joinToString("\n").trim()
        scriptFile = createTmpScript(scriptText)
    }


    // Support URLs as script files
    if (scriptResource.startsWith("http://") || scriptResource.startsWith("https://")) {
        scriptFile = fetchFromURL(scriptResource)
    }


    // Support for support process substitution and direct script arguments
    if (scriptFile == null && !scriptResource.endsWith(".kts") && !scriptResource.endsWith(".kt")) {
        val scriptText = if (File(scriptResource).canRead()) {
            File(scriptResource).readText().trim()
        } else {
            // the last resort is to assume the input to be a kotlin program
            scriptResource.trim()
        }

        scriptFile = createTmpScript(scriptText)
    }

    // just proceed if the script file is a regular file at this point
    errorIf(scriptFile == null || !scriptFile.canRead()) {
        "Could not read script argument '$scriptResource'"
    }

    // note script file must be not null at this point

    // include preamble for custom interpreters (see https://github.com/holgerbrandl/kscript/issues/67)
    System.getenv("CUSTOM_KSCRIPT_PREAMBLE")?.let { interpPreamble ->
        scriptFile = Script(scriptFile!!).prependWith(interpPreamble).createTmpScript()
    }

    // prefix with text-processing preamble if kscript-support api is enabled
    if (enableSupportApi) {
        val textProcPreamble = """
            //DEPS com.github.holgerbrandl:kscript-support:1.2.4

            import kscript.text.*
            val lines = resolveArgFile(args)

            """.trimIndent()

        scriptFile = Script(scriptFile!!).prependWith(textProcPreamble).createTmpScript()
    }

    //    System.err.println("[kscript] temp script file is ${scriptFile}")
    //    System.err.println("[kscript] temp script file is \n${Script(scriptFile!!)}")

    // resolve all includes (see https://github.com/holgerbrandl/kscript/issues/34)
    scriptFile = resolveIncludes(scriptFile!!)

    return scriptFile!!
}
