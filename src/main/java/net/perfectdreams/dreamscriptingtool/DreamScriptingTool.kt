package net.perfectdreams.dreamscriptingtool

import net.perfectdreams.dreamcore.utils.KotlinPlugin
import net.perfectdreams.dreamcore.utils.commands.AbstractCommand
import net.perfectdreams.dreamcore.utils.registerEvents
import net.perfectdreams.dreamcore.utils.withoutPermission
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.HandlerList
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Level

class DreamScriptingTool : KotlinPlugin() {
	lateinit var scriptsFolder: File
	lateinit var precompiledFolder: File
	lateinit var compiledFolder: File
	lateinit var kotlinBinaryFolder: File

	val loadedScripts = mutableMapOf<String, DreamScript>()

	override fun softEnable() {
		super.softEnable()
		System.setProperty("idea.use.native.fs.for.win", "false")
		dataFolder.mkdirs()
		scriptsFolder = File(dataFolder, "scripts")
		scriptsFolder.mkdirs()
		precompiledFolder = File(dataFolder, "precompiled")
		precompiledFolder.mkdirs()
		compiledFolder = File(dataFolder, "compiled")
		compiledFolder.mkdirs()
		kotlinBinaryFolder = File(dataFolder, "kotlinc\\bin")

		val scriptCommand = object: AbstractCommand("dreamscript") {
			override fun run(p0: CommandSender, p1: Command, p2: String, p3: Array<String>) {
				if (!p0.hasPermission("dreamscriptingtool.use")) {
					p0.sendMessage(withoutPermission)
					return
				}

				val arg0 = p3.getOrNull(0)

				if (arg0 == null) {
					p0.sendMessage("§6/dreamscript load script")
					p0.sendMessage("§6/dreamscript unload script")
					p0.sendMessage("§6/dreamscript reload script")
				} else {
					val arg1 = p3.getOrNull(1)!!.toLowerCase()
					if (arg0 == "load") {
						if (loadedScripts.containsKey(arg1)) {
							p0.sendMessage("§cScript §b${arg1}§a já está carregado! Que tal usar §6/script reload ${arg1}§c?")
							return
						}
						loadScript(File(scriptsFolder, "$arg1.kt"))
						p0.sendMessage("§aScript §b${arg1}§a carregado com sucesso!")
						return
					}
					if (arg0 == "unload") {
						unloadScript(arg1!!)
						p0.sendMessage("§aScript §b${arg1}§a descarregado com sucesso!")
						return
					}
					if (arg0 == "reload") {
						unloadScript(arg1!!)
						p0.sendMessage("§aScript §b${arg1}§a descarregado com sucesso!")
						loadScript(File(scriptsFolder, "$arg1.kt"))
						p0.sendMessage("§aScript §b${arg1}§a carregado com sucesso!")
						return
					}
				}
			}
		}

		registerCommand(scriptCommand)

		loadScripts()
	}

	override fun softDisable() {
		super.softDisable()
	}

	fun loadScripts() {
		scriptsFolder.listFiles().filter { it.extension == "kt" }.forEach {
			loadScript(it)
		}
	}

	fun loadScript(file: File) {
		logger.log(Level.INFO, "Carregando ${file.name}...")
		val template = File(dataFolder, "template.kt").readText()
		var scriptCode = template.replace("{{ code }}", file.readText())

		val useJars = file.readLines()[0].replaceFirst("// ", "").split(";")

		val packageName = file.nameWithoutExtension.replace("_", "").replace(" ", "")
		scriptCode = "package net.perfectdreams.dreamscriptingtool.script.$packageName\n\n" + scriptCode
		val className = file.nameWithoutExtension.replace("_", "").replace(" ", "").capitalize()
		scriptCode = scriptCode.replace("{{ className }}", className)

		// Para facilitar scripting, nós vamos colocar alguns "short hands"
		// Por exemplo:
		// #onEnable
		// #event:PlayerMoveEvent
		// #event:BlockBreakEvent
		scriptCode = scriptCode.replace("#onEnable", "override fun onEnable()")
		var eventIdx = 0
		scriptCode = scriptCode.replace(
				Regex("#([A-z]+):([A-z]+)")
		) {
			var text = """@EventHandler
						|fun event${eventIdx}(event: ${it.groups[2]!!.value})
					""".trimMargin()
			eventIdx++
			text
		}

		try {
			val precompiledCodeFile = File(precompiledFolder, file.name)
			precompiledCodeFile.writeText(scriptCode) // Salvar o nosso script (antes de compilar) em algum lugar

			logger.log(Level.INFO, "Pre-Compiled Script salvado!")

			val compilerPath = File(kotlinBinaryFolder, "kotlinc.bat")

			val args = mutableListOf(
					precompiledCodeFile.absoluteFile.toString(),
					"-cp"
			)

			val classpath = getAllJarsInClasspath(File("."), useJars).map { it.absoluteFile.toString() }.joinToString(";")

			logger.log(Level.INFO, "Use JARs: ${useJars.joinToString(", ")}")
			logger.log(Level.INFO, "Class path: ${classpath}")

			args.add(classpath)

			args.add("-d")
			args.add(compiledFolder.absoluteFile.toString())

			logger.log(Level.INFO, "Argumentos: ${args.joinToString(",")}")
			logger.log(Level.INFO, "${classpath.count { it == ';' }} dependências")

			val kotlinCompilerBuilder = ProcessBuilder(compilerPath.toString(), *args.toTypedArray())
			kotlinCompilerBuilder.redirectErrorStream(true)
			val kotlinCompiler = kotlinCompilerBuilder.start()
			kotlinCompiler.waitFor()

			val output = kotlinCompiler.inputStream.reader().readLines()
			println(output.joinToString("\n"))

			val cl = URLClassLoader(
					arrayOf(compiledFolder.toURI().toURL()),
					this::class.java.classLoader
			)

			val clazz = cl.loadClass("net.perfectdreams.dreamscriptingtool.script.${packageName}.${className}")
			val instance = clazz.newInstance()

			logger.log(Level.INFO, "Here is ${instance}'s class")
			logger.log(Level.INFO, instance::class.java.name)

			logger.log(Level.INFO, instance::class.java.isAssignableFrom(DreamScript::class.java).toString())

			if (instance is DreamScript) {
				logger.log(Level.INFO, "Triggering onEnable...")
				instance.onEnable()
				logger.log(Level.INFO, "Registering events...")
				registerEvents(instance)

				loadedScripts[file.nameWithoutExtension.toLowerCase()] = instance

				logger.log(Level.INFO, file.name + " carregado com sucesso! ${loadedScripts.keys.joinToString()}")
			}
		} catch (e: Exception) {
			logger.log(Level.SEVERE, "Erro ao carregar ${file.name}!", e)
		}
	}

	fun unloadScript(name: String) {
		if (loadedScripts[name] == null)
			throw ScriptNotFoundException()

		val script = loadedScripts[name]!!

		script.commandList.forEach { it.unregister() }
		HandlerList.getHandlerLists().forEach{
			it.unregister(script)
		}

		loadedScripts.remove(name)
	}

	fun getAllJarsInClasspath(f: File, names: List<String>): List<File> {
		val list = mutableListOf<File>()

		fun getAllJarsInFolder(folder: File) {
			folder.listFiles().forEach {
				if (it.isDirectory) {
					getAllJarsInFolder(it)
				} else if (names.contains(it.name)) {
					list.add(it)
				}
			}
		}

		getAllJarsInFolder(f)
		return list
	}
}