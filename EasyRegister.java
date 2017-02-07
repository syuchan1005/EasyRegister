/*
 * Copyright (c) 2016- syu_chan_1005
 * Released under the MIT license
 * http://opensource.org/licenses/mit-license.php
 */

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class EasyRegister {
	private final Plugin plugin;
	private final Map<String, Map<BaseCommand, Method>> commandMap = new HashMap<>();
	private static Method commandMapMethod = null;

	public EasyRegister(Plugin plugin) throws ReflectiveOperationException {
		this.plugin = plugin;
		ClassLoader loader = this.getClass().getClassLoader();
		PluginManager pluginManager = this.getPlugin().getServer().getPluginManager();
		try (JarFile jar = new JarFile(new File(this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation().getFile()))) {
			for (JarEntry e : Collections.list(jar.entries())) {
				String className = e.getName();
				if (!className.endsWith(".class")) continue;
				Class<?> clazz = loader.loadClass(className.replace('/', '.').substring(0, className.length() - 6));
				if (hasListener(clazz)) pluginManager.registerEvents((Listener) this.getInstance(clazz), this.plugin);
				for (Method method : clazz.getMethods()) {
					EasyRegister.AddCommand addCommand = hasCommand(method);
					if (addCommand != null) this.putMap(new BaseCommand(addCommand), method);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private boolean run(CommandSender sender, Command command, String[] args) throws ReflectiveOperationException {
		if (commandMap.containsKey(command.getName().toLowerCase())) {
			Map<BaseCommand, Method> map = commandMap.get(command.getName().toLowerCase());
			if (args.length != 0) args = new String[] {""};
			for (Map.Entry<BaseCommand, Method> e : map.entrySet()) {
				if (e.getKey().isThisCommand(command.getName(), args[0].toLowerCase())) {
					if (sender.hasPermission(e.getKey().getPermission())) {
						return (boolean) e.getValue().invoke(this.getInstance(e.getValue().getDeclaringClass()), sender, command, args);
					} else {
						sender.sendMessage(e.getKey().getPermissionMessage());
						return true;
					}
				}
			}
		}
		return false;
	}

	public void registerYmlCommand(String command) {
		Bukkit.getPluginCommand(command).setExecutor((sender, cmd, c, args) -> {
			try {
				return run(sender, cmd, args);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
			return false;
		});
	}

	public void registerNewCommand(String command, String description, String usageMessage, String permission, String permissionMessage) throws ReflectiveOperationException {
		registerNewCommand(command, description, usageMessage, permission, permissionMessage, new String[0]);
	}

	public void registerNewCommand(String command, String description, String usageMessage, String permission, String permissionMessage, String... aliases) throws ReflectiveOperationException {
		BaseCommand base = new BaseCommand(command, description, usageMessage, Arrays.asList(aliases), permission, permissionMessage);
		this.getSimpleCommandMap().register(this.getPlugin().getName(), base.toPluginCommand(this));
	}

	public void sendHelpMessage(CommandSender sender, String command) {
		if (commandMap.containsKey(command.toLowerCase())) {
			StringBuilder sb = new StringBuilder().append("-----Help-----\n");
			for (Map.Entry<BaseCommand, Method> e : commandMap.get(command.toLowerCase()).entrySet()) {
				sb.append(e.getKey().createHelpMessage());
				sb.append("\n");
			}
			sender.sendMessage(sb.toString());
		}
	}

	public Plugin getPlugin() {
		return this.plugin;
	}

	private Object getInstance(Class clazz) throws ReflectiveOperationException {
		if (clazz.isInstance(this.getPlugin())) return this.getPlugin();
		for (Constructor con : clazz.getDeclaredConstructors()) {
			Class[] classes = con.getParameterTypes();
			con.setAccessible(true);
			if (classes.length == 1 && classes[0].isInstance(this.getPlugin()))
				return con.newInstance(this.getPlugin());
		}
		return clazz.newInstance();
	}

	private void putMap(BaseCommand base, Method method) {
		if (!commandMap.containsKey(base.getName())) commandMap.put(base.getName(), new HashMap<>());
		commandMap.get(base.getName()).put(base, method);
	}

	private static AddCommand hasCommand(Method method) {
		Class clazz[] = method.getParameterTypes();
		if (clazz.length == 3 && clazz[0] == CommandSender.class && clazz[1] == Command.class
				&& clazz[2] == String[].class && method.getReturnType().equals(boolean.class)) {
			return method.getAnnotation(AddCommand.class);
		}
		return null;
	}

	private static boolean hasListener(Class clazz) {
		AddListener listener = (AddListener) clazz.getAnnotation(AddListener.class);
		return !(listener == null || !listener.value()) && Arrays.asList(clazz.getInterfaces()).contains(Listener.class);
	}

	private SimpleCommandMap getSimpleCommandMap() throws ReflectiveOperationException {
		Server server = this.getPlugin().getServer();
		if (commandMapMethod == null) {
			commandMapMethod = server.getClass().getDeclaredMethod("getSimpleCommandMap");
			commandMapMethod.setAccessible(true);
		}
		return (SimpleCommandMap) commandMapMethod.invoke(server);
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface AddCommand {
		String Command();

		String subCommand() default "";

		String[] Aliases() default {};

		String Description() default "Not added";

		String Permission() default "Not added";

		String PermissionMessage() default "You don't have permission.";

		String Usage() default "Not added";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface AddListener {
		boolean value() default true;
	}

	private static class BaseCommand extends Command {
		private static Constructor<?> pluginCommand;
		private String subCommand;

		BaseCommand(AddCommand a) {
			this(a.Command(), a.Description(), a.Usage(), Arrays.asList(a.Aliases())
					, a.Permission(), a.PermissionMessage(), a.subCommand());
		}

		BaseCommand(String command, String description, String usageMessage, List<String> aliases,
					String Permission, String PermissionMessage) {
			this(command, description, usageMessage, aliases, Permission, PermissionMessage, "");
		}

		BaseCommand(String command, String description, String usageMessage, List<String> aliases,
					String Permission, String PermissionMessage, String SubCommand) {
			super(command, description, usageMessage, aliases);
			this.setPermission(Permission);
			this.setPermissionMessage(PermissionMessage);
			this.setSubCommand(SubCommand);
		}

		public String getSubCommand() {
			return subCommand;
		}

		public void setSubCommand(String subCommand) {
			this.subCommand = subCommand;
		}

		boolean isThisCommand(String cmd, String sub) {
			return getName().equalsIgnoreCase(cmd) &&
					(subCommand.equals(sub) || getAliases().stream().anyMatch(sub::equalsIgnoreCase));
		}

		String createHelpMessage() {
			String str = subCommand;
			if (!getAliases().isEmpty()) {
				str += "(" + String.join(", ", getAliases()) + ")";
			}
			str += " : " + getUsage();
			return str;
		}

		PluginCommand toPluginCommand(EasyRegister manager) throws ReflectiveOperationException {
			if (pluginCommand == null) {
				pluginCommand = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
				pluginCommand.setAccessible(true);
			}
			PluginCommand cmd = ((PluginCommand) pluginCommand.newInstance(this.getName(), manager.getPlugin()));
			cmd.setDescription(this.getDescription()).setUsage(this.getUsage())
					.setAliases(this.getAliases()).setPermissionMessage(this.getPermissionMessage());
			cmd.setPermission(this.getPermission());
			cmd.setExecutor((sender, command, label, args) -> {
				try {
					return manager.run(sender, command, args);
				} catch (ReflectiveOperationException e) {
					e.printStackTrace();
					return true;
				}
			});
			return cmd;
		}

		@Override
		public boolean execute(CommandSender sender, String arg1, String[] args) {
			return true;
		}
	}
}
