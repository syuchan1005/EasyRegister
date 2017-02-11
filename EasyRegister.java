/*
 * Copyright (c) 2016- syu_chan_1005
 * Released under the MIT license
 * http://opensource.org/licenses/mit-license.php
 */

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/*
TODO: TabCompleteでの拡張記法の追加
TODO: NamedParamへの対応. ex.[player<OnlinePlayer>] -> {"player", OnlinePlayer(インスタンス)} => interfaceを用意して拡張性？
TODO: CommandSenderのフィルタリング
 */

public class EasyRegister {
	private Plugin plugin;
	private Map<String, List<BaseCommand>> commandMap = new HashMap<>();
	private static Method commandMapMethod = null;

	public EasyRegister(Plugin plugin) {
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
					getAddCommandArray(method).forEach(a -> this.putMap(new BaseCommand(a, this), method));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private boolean run(CommandSender sender, Command command, String[] args) throws ReflectiveOperationException {
		if (commandMap.containsKey(command.getName().toLowerCase())) {
			List<BaseCommand> baseList = commandMap.get(command.getName().toLowerCase());
			if (args.length == 0) args = new String[]{""};
			for (BaseCommand baseCommand : baseList) {
				if (baseCommand.isThisCommand(command.getName(), args[0].toLowerCase())) {
					if (sender.hasPermission(baseCommand.getPermission())) {
						Method method = baseCommand.getCommandExecuteMethod();
						return (boolean) method.invoke(this.getInstance(method.getDeclaringClass()), sender, command, args);
					} else {
						sender.sendMessage(baseCommand.getPermissionMessage());
						return true;
					}
				}
			}
		}
		return false;
	}

	private List<String> tab(CommandSender sender, Command command, String cmd, String[] args) {
		if (commandMap.containsKey(command.getName().toLowerCase())) {
			List<BaseCommand> baseList = commandMap.get(command.getName().toLowerCase());
			if (args.length == 0) args = new String[]{""};
			for (BaseCommand baseCommand : baseList) {
				if (baseCommand.isThisCommand(command.getName(), args[0].toLowerCase())) {
					return baseCommand.getParamSet().onTabComplete(sender, baseCommand, cmd, args);
				}
			}
		}
		return Collections.emptyList();
	}

	public void registerYmlCommand(String command) {
		PluginCommand pluginCommand = Bukkit.getPluginCommand(command);
		pluginCommand.setExecutor((sender, cmd, c, args) -> {
			try {
				return run(sender, cmd, args);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
			return false;
		});
		pluginCommand.setTabCompleter(this::tab);
	}

	public void registerNewCommand(String command, String description, String usageMessage, String permission, String permissionMessage) throws ReflectiveOperationException {
		registerNewCommand(command, description, usageMessage, permission, permissionMessage, new String[0]);
	}

	public void registerNewCommand(String command, String description, String usageMessage, String permission, String permissionMessage, String... aliases) throws ReflectiveOperationException {
		this.getSimpleCommandMap().register(this.getPlugin().getName(), new BaseCommand(command, description, usageMessage, Arrays.asList(aliases), permission, permissionMessage, this));
	}

	public void sendHelpMessage(CommandSender sender, String command) {
		if (commandMap.containsKey(command.toLowerCase())) {
			StringBuilder sb = new StringBuilder().append("-----Help-----\n");
			for (BaseCommand baseCommand : commandMap.get(command.toLowerCase())) {
				sb.append(baseCommand.createHelpMessage());
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
		if (!commandMap.containsKey(base.getName())) commandMap.put(base.getName(), new ArrayList<>());
		base.setCommandExecuteMethod(method);
		commandMap.get(base.getName()).add(base);
	}

	private static final Class[] commandArgs = new Class[]{CommandSender.class, Command.class, String[].class};

	private static List<AddCommand> getAddCommandArray(Method method) {
		Class clazz[] = method.getParameterTypes();
		List<AddCommand> addCommands = new ArrayList<>(1);
		if (Arrays.equals(method.getParameterTypes(), commandArgs) && method.getReturnType().equals(boolean.class)) {
			AddCommandHolder annotation = method.getAnnotation(AddCommandHolder.class);
			if (annotation != null) {
				Collections.addAll(addCommands, annotation.value());
			} else {
				addCommands.add(method.getAnnotation(AddCommand.class));
			}
		}
		addCommands.removeIf(Objects::isNull);
		return addCommands;
	}

	private static boolean hasListener(Class clazz) {
		AddListener listener = (AddListener) clazz.getAnnotation(AddListener.class);
		return !(listener == null || !listener.value()) && Arrays.asList(clazz.getInterfaces()).contains(Listener.class);
	}

	private SimpleCommandMap getSimpleCommandMap() throws ReflectiveOperationException {
		Server server = this.getPlugin().getServer();
		if (commandMapMethod == null) {
			commandMapMethod = server.getClass().getDeclaredMethod("getCommandMap");
			commandMapMethod.setAccessible(true);
		}
		return (SimpleCommandMap) commandMapMethod.invoke(server);
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface AddCommandHolder {
		AddCommand[] value();
	}

	@Repeatable(AddCommandHolder.class)
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

		String Pattern() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface AddListener {
		boolean value() default true;
	}

	private static class BaseCommand extends Command implements PluginIdentifiableCommand {
		private EasyRegister manager;
		private String subCommand;
		private Method commandExecuteMethod;
		private ParamSet paramSet;

		BaseCommand(AddCommand a, EasyRegister manager) {
			this(a.Command(), a.Description(), a.Usage(), Arrays.asList(a.Aliases())
					, a.Permission(), a.PermissionMessage(), a.subCommand(), a.Pattern(), manager);
		}

		BaseCommand(String command, String description, String usageMessage, List<String> aliases,
					String Permission, String PermissionMessage, EasyRegister manager) {
			this(command, description, usageMessage, aliases, Permission, PermissionMessage, "", "", manager);
		}

		BaseCommand(String command, String description, String usageMessage, List<String> aliases,
					String Permission, String PermissionMessage, String SubCommand, String Pattern, EasyRegister manager) {
			super(command, description, usageMessage, aliases);
			this.manager = manager;
			this.setPermission(Permission);
			this.setPermissionMessage(PermissionMessage);
			this.setSubCommand(SubCommand);
			this.paramSet = new ParamSet(Pattern);
		}

		Method getCommandExecuteMethod() {
			return commandExecuteMethod;
		}

		void setCommandExecuteMethod(Method commandExecuteMethod) {
			this.commandExecuteMethod = commandExecuteMethod;
		}

		public String getSubCommand() {
			return subCommand;
		}

		public void setSubCommand(String subCommand) {
			this.subCommand = subCommand;
		}

		public ParamSet getParamSet() {
			return paramSet;
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

		@Override
		public boolean execute(CommandSender sender, String command, String[] args) {
			try {
				return manager.run(sender, this, args);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
				return true;
			}
		}

		@Override
		public List<String> tabComplete(CommandSender sender, String command, String[] args) throws IllegalArgumentException {
			return manager.tab(sender, this, command, args);
		}

		@Override
		public Plugin getPlugin() {
			return manager.getPlugin();
		}
	}

	public static class ParamSet implements Cloneable {
		private static List<ParamExecutor> paramExecutorList = new ArrayList<>();
		private String[] paramTags = new String[0];
		private Map<String, ParamExecutor> paramPatterns = new HashMap<>();

		static {
			addParamExecutor(new MaterialExecutor());
			addParamExecutor(new OnlinePlayerExecutor());
			addParamExecutor(new OfflinePlayerExecutor());
			addParamExecutor(new EntityTypeExecutor());
		}

		static void addParamExecutor(ParamExecutor executor) {
			paramExecutorList.add(executor);
		}

		private static ParamExecutor getParamExecutor(String tag) {
			for (ParamExecutor paramExecutor : paramExecutorList) {
				if (paramExecutor.getExecTag().equals(tag)) {
					return paramExecutor;
				}
			}
			return null;
		}

		ParamSet(String pattern) {
			if (pattern.isEmpty()) return;
			paramTags = pattern.split(" ");
			Arrays.stream(paramTags)
					.map(str -> str.substring(0, str.length() - 1))
					.map(str -> str.split("<"))
					.forEach(args -> paramPatterns.put(args[0], getParamExecutor(args[1])));
		}

		List<String> onTabComplete(CommandSender sender, BaseCommand command, String cmd, String[] args) {
			if (!command.getSubCommand().isEmpty()) {
				args = Arrays.copyOfRange(args, 1, args.length);
			}
			int length = args.length - 1;
			if (length >= 0 && paramTags.length > length) {
				return paramPatterns.get(paramTags[length].split("<")[0])
						.onTabComplete(sender, command, cmd, args[length], paramTags[length]);
			}
			return Collections.emptyList();
		}
	}

	public interface ParamExecutor<T> {
		String getExecTag();

		T execTag(String arg);

		List<String> onTabComplete(CommandSender sender, Command command, String cmd, String arg, String tag);
	}

	static class MaterialExecutor implements ParamExecutor<Material> {
		@Override
		public String getExecTag() {
			return "Material";
		}

		@Override
		public Material execTag(String arg) {
			return Material.valueOf(arg);
		}

		@Override
		public List<String> onTabComplete(CommandSender sender, Command command, String cmd, String arg, String tag) {
			return Arrays.stream(Material.values())
					.map(Material::name)
					.filter(str -> str.startsWith(arg))
					.collect(Collectors.toList());
		}
	}

	static class OnlinePlayerExecutor implements ParamExecutor<Player> {
		@Override
		public String getExecTag() {
			return "OnlinePlayer";
		}

		@Override
		public Player execTag(String arg) {
			return Bukkit.getPlayer(arg);
		}

		@Override
		public List<String> onTabComplete(CommandSender sender, Command command, String cmd, String arg, String tag) {
			return Bukkit.getOnlinePlayers().stream()
					.map(Player::getName)
					.filter(player -> player.startsWith(arg))
					.collect(Collectors.toList());
		}
	}

	static class OfflinePlayerExecutor implements ParamExecutor<OfflinePlayer> {
		@Override
		public String getExecTag() {
			return "OfflinePlayer";
		}

		@Override
		public OfflinePlayer execTag(String arg) {
			return Bukkit.getOfflinePlayer(arg);
		}

		@Override
		public List<String> onTabComplete(CommandSender sender, Command command, String cmd, String arg, String tag) {
			return Arrays.stream(Bukkit.getOfflinePlayers())
					.map(OfflinePlayer::getName)
					.filter(player -> player.startsWith(arg))
					.collect(Collectors.toList());
		}
	}

	static class EntityTypeExecutor implements ParamExecutor<EntityType> {
		@Override
		public String getExecTag() {
			return "EntityType";
		}

		@Override
		public EntityType execTag(String arg) {
			return EntityType.valueOf(arg);
		}

		@Override
		public List<String> onTabComplete(CommandSender sender, Command command, String cmd, String arg, String tag) {
			return Arrays.stream(EntityType.values())
					.map(EntityType::name)
					.filter(str -> str.startsWith(arg))
					.collect(Collectors.toList());
		}
	}
}
