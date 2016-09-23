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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class EasyRegister {
	private Plugin pl;
	private Map<String, Map<Base, Method>> Commands = new HashMap<>();
	private static Method commandMapMethod = null;

	public EasyRegister(Plugin plugin) throws ReflectiveOperationException, IOException {
		this.pl = plugin;
		loadClasses(new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().getFile()), true);
	}

	public void loadClasses(File jarFile, boolean isPluginJar) throws IOException, ReflectiveOperationException {
		JarFile jar = null;
		try {
			jar = new JarFile(jarFile);
			ClassLoader loader = this.getClass().getClassLoader();
			if (!isPluginJar) loader = URLClassLoader.newInstance(new URL[]{jarFile.toURI().toURL()}, loader);
			for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
				String className = e.nextElement().getName();
				if (!className.endsWith(".class")) continue;
				Class<?> clazz = loader.loadClass(className.replace('/', '.').substring(0, className.length() - 6));
				PluginManager pluginManager = this.getPlugin().getServer().getPluginManager();
				if (hasListener(clazz))
					pluginManager.registerEvents((Listener) this.getInstance(clazz), this.getPlugin());
				for (Method method : clazz.getMethods()) {
					EasyRegister.AddCommand addCommand = hasCommand(method);
					if (addCommand != null) this.putMap(new Base(addCommand), method);
				}
			}
		} finally {
			if (jar != null) jar.close();
		}
	}

	public boolean run(CommandSender sender, Command command, String[] args) throws ReflectiveOperationException {
		if (Commands.containsKey(command.getName().toLowerCase())) {
			if (args.length == 0) args = new String[]{""};
			Map<Base, Method> map = Commands.get(command.getName().toLowerCase());
			for (Map.Entry<Base, Method> e : map.entrySet()) {
				String sub = args[0];
				if (e.getKey().getName().equalsIgnoreCase(command.getName()) &&
						(e.getKey().getSubCommand().equals(sub) || e.getKey().getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(sub)))) {
					if (sender.hasPermission(e.getKey().getPermission())) {
						return (boolean) e.getValue().invoke(this.getInstance(e.getValue().getDeclaringClass()),
								sender, command, args);
					} else {
						sender.sendMessage(e.getKey().getPermissionMessage());
						return true;
					}
				}
			}
			for (Map.Entry<Base, Method> e : map.entrySet()) {
				if (e.getKey().getSubCommand().equals("")) {
					if (sender.hasPermission(e.getKey().getPermission())) {
						return (boolean) e.getValue().invoke(this.getInstance(e.getValue().getDeclaringClass()),
								sender, command, args);
					} else {
						sender.sendMessage(e.getKey().getPermissionMessage());
						return true;
					}
				}
			}
		}
		return false;
	}

	public void registerCommand(String command, String description, String usageMessage, String permission,
								String permissionMessage) throws ReflectiveOperationException {
		this.registerCommand(command, description, usageMessage, permission, permissionMessage, new String[0]);
	}

	public void registerCommand(String command, String description, String usageMessage, String permission,
								String permissionMessage, String... aliases) throws ReflectiveOperationException {
		Base base = new Base(command, description, usageMessage, Arrays.asList(aliases), permission,
				permissionMessage);
		this.getCommandMap().register(this.getPlugin().getName(), base.toPluginCommand(this));
	}

	public void sendHelpMessage(CommandSender sender, String command) {
		if (Commands.containsKey(command.toLowerCase())) {
			StringBuilder sb = new StringBuilder();
			sb.append("-----Help-----\n");
			for (Map.Entry<Base, Method> e : Commands.get(command.toLowerCase()).entrySet()) {
				Base base = e.getKey();
				String sub = base.getSubCommand();
				if (!(sub.isEmpty() || sub.equalsIgnoreCase("help"))) {
					sb.append(sub);
					if (base.getAliases().size() >= 1) {
						sb.append('(');
						for (String alias : base.getAliases()) {
							sb.append(alias);
							sb.append(',');
						}
						sb.setLength(sb.length() - 1);
						sb.append(')');
					}
					sb.append(" : ");
					sb.append(base.getUsage());
					sb.append("\n");
				}
			}
			sender.sendMessage(sb.toString());
		}
	}

	public Plugin getPlugin() {
		return this.pl;
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

	private void putMap(Base base, Method method) {
		if (Commands.containsKey(base.getName())) {
			Commands.get(base.getName()).put(base, method);
		} else {
			Map<Base, Method> map = new HashMap<>();
			map.put(base, method);
			Commands.put(base.getName(), map);
		}
	}

	private static AddCommand hasCommand(Method method) {
		Class clazz[] = method.getParameterTypes();
		if (clazz.length == 3 && clazz[0] == CommandSender.class && clazz[1] == Command.class
				&& clazz[2] == String[].class && method.getReturnType()
				.equals(boolean.class)) {
			return (AddCommand) method.getAnnotation(AddCommand.class);
		}
		return null;
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

	private static boolean hasListener(Class clazz) {
		AddListener listener = (AddListener) clazz.getAnnotation(AddListener.class);
		if (listener == null || !listener.value()) return false;
		return Arrays.stream(clazz.getInterfaces()).anyMatch(c -> c.equals(Listener.class));
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface AddListener {
		boolean value() default true;
	}

	private SimpleCommandMap getCommandMap() throws ReflectiveOperationException {
		Server server = this.getPlugin().getServer();
		if (commandMapMethod == null) {
			commandMapMethod = server.getClass().getDeclaredMethod("getCommandMap");
			commandMapMethod.setAccessible(true);
		}
		return (SimpleCommandMap) commandMapMethod.invoke(server);
	}

	static class Base extends Command {
		private static Constructor<?> pluginCommand;
		private String subCommand;

		public Base(AddCommand a) {
			this(a.Command(), a.Description(), a.Usage(), Arrays.asList(a.Aliases())
					, a.Permission(), a.PermissionMessage(), a.subCommand());
		}

		public Base(String command, String description, String usageMessage, List<String> aliases,
					String Permission, String PermissionMessage) {
			this(command, description, usageMessage, aliases, Permission, PermissionMessage, "");
		}

		public Base(String command, String description, String usageMessage, List<String> aliases,
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

		public PluginCommand toPluginCommand(EasyRegister manager) throws ReflectiveOperationException {
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
		public String getName() {
			return super.getName().toLowerCase();
		}

		@Override
		public boolean execute(CommandSender sender, String arg1, String[] args) {
			return true;
		}

	}
}

