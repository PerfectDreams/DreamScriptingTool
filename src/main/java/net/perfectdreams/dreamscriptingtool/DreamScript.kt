package net.perfectdreams.dreamscriptingtool

import net.perfectdreams.dreamcore.utils.commands.AbstractCommand
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener

abstract class DreamScript : Listener {
	val commandList = mutableListOf<AbstractCommand>()

	fun registerCommand(command: AbstractCommand) {
		command.register()
		commandList.add(command)
	}

	fun command(label: String, command: (CommandSender, Array<String>) -> Unit) {
		val abstractCommand = object: AbstractCommand(label) {
			override fun run(p0: CommandSender, p1: Command, p2: String, p3: Array<String>) {
				command.invoke(p0, p3)
			}
		}
		registerCommand(abstractCommand)
	}

	open fun onEnable() {

	}
}