/*
 *     KookBC -- The Kook Bot Client & JKook API standard implementation for Java.
 *     Copyright (C) 2022 - 2023 KookBC contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package snw.kookbc.impl;

import snw.jkook.Core;
import snw.jkook.command.CommandExecutor;
import snw.jkook.command.ConsoleCommandSender;
import snw.jkook.command.JKookCommand;
import snw.jkook.config.ConfigurationSection;
import snw.jkook.entity.User;
import snw.jkook.message.component.MarkdownComponent;
import snw.jkook.plugin.Plugin;
import snw.jkook.plugin.PluginDescription;
import snw.jkook.plugin.UnknownDependencyException;
import snw.jkook.util.Validate;
import snw.kookbc.SharedConstants;
import snw.kookbc.impl.command.CommandManagerImpl;
import snw.kookbc.impl.command.WrappedCommand;
import snw.kookbc.impl.console.Console;
import snw.kookbc.impl.entity.builder.EntityBuilder;
import snw.kookbc.impl.entity.builder.EntityUpdater;
import snw.kookbc.impl.entity.builder.MessageBuilder;
import snw.kookbc.impl.network.Connector;
import snw.kookbc.impl.network.HttpAPIRoute;
import snw.kookbc.impl.network.NetworkClient;
import snw.kookbc.impl.network.Session;
import snw.kookbc.impl.plugin.InternalPlugin;
import snw.kookbc.impl.plugin.PluginMixinConfigManager;
import snw.kookbc.impl.scheduler.SchedulerImpl;
import snw.kookbc.impl.storage.EntityStorage;
import snw.kookbc.impl.tasks.BotMarketPingThread;
import snw.kookbc.impl.tasks.UpdateChecker;
import snw.kookbc.util.Util;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

// The client representation.
public class KBCClient {
    private volatile boolean running = true;
    private final CoreImpl core;
    private final NetworkClient networkClient;
    private final EntityStorage storage;
    private final EntityBuilder entityBuilder;
    private final MessageBuilder msgBuilder;
    private final EntityUpdater entityUpdater;
    private final ConfigurationSection config;
    private final File pluginsFolder;
    private final Session session = new Session(null);
    private final InternalPlugin internalPlugin;
    protected final ExecutorService eventExecutor;
    protected Connector connector;
    protected List<Plugin> plugins;
    protected PluginMixinConfigManager pluginMixinConfigManager;

    public KBCClient(CoreImpl core, ConfigurationSection config, File pluginsFolder, String token) {
        if (pluginsFolder != null) {
            Validate.isTrue(pluginsFolder.isDirectory(), "The provided pluginsFolder object is not a directory.");
        }
        this.core = core;
        this.config = config;
        this.pluginsFolder = pluginsFolder;
        try {
            if (Util.isStartByLaunch()) {
                this.pluginMixinConfigManager = new PluginMixinConfigManager();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.core.init(this, new HttpAPIImpl(this));
        this.networkClient = new NetworkClient(this, token);
        this.storage = new EntityStorage(this);
        this.entityBuilder = new EntityBuilder(this);
        this.msgBuilder = new MessageBuilder(this);
        this.entityUpdater = new EntityUpdater(this);
        this.internalPlugin = new InternalPlugin(this);
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Event Executor"));
    }

    // The result of this method can prevent the users to execute the console command,
    //  so that some possible problems won't be caused.
    // (e.g. Kook user stopped the client)
    private CommandExecutor wrapConsoleCmd(Consumer<Object[]> reallyThingToRun) {
        return (sender, arguments, message) -> {
            if (sender instanceof User) {
                if (getConfig().getBoolean("ignore-remote-call-invisible-internal-command", true)) {
                    return;
                }
                if (message != null) {
                    message.sendToSource(new MarkdownComponent("你不能这样做，因为你正在尝试执行仅后台可用的命令。"));
                }
            } else {
                reallyThingToRun.accept(arguments);
            }
        };
    }

    public ConfigurationSection getConfig() {
        return config;
    }

    public Core getCore() {
        return core;
    }

    public File getPluginsFolder() {
        return pluginsFolder;
    }

    public boolean isRunning() {
        return running;
    }

    // Note for hardcore developers:
    // You can also put this client into your project as a module to communicate with Kook
    // Call this to start KookBC, then you can use JKook API.
    // WARN: Set the JKook Core by constructing CoreImpl and call getCore().setCore() using it first,
    // or you will get NullPointerException.
    public void start() {
        core.getLogger().debug("Fetching Bot user object");
        User botUser = getEntityBuilder().buildUser(
                getNetworkClient().get(HttpAPIRoute.USER_ME.toFullURL()));
        getStorage().addUser(botUser);
        core.setUser(botUser);
        registerInternal();
        enablePlugins(plugins);
        getCore().getLogger().debug("Loading all the plugins from plugins folder");
        getCore().getLogger().debug("Starting Network");
        startNetwork();
        finishStart();
        getCore().getLogger().info("Done! Type \"help\" for help.");

        if (getConfig().getBoolean("check-update", true)) {
            new UpdateChecker(this).start(); // check update. Added since 2022/7/24
        }
    }

    protected List<Plugin> loadAllPlugins() {
        if (pluginsFolder == null) {
            return Collections.emptyList(); // If you just want to use JKook API?
        }
        if (plugins != null) {
            return plugins;
        }
        List<Plugin> plugins = new LinkedList<>(Arrays.asList(getCore().getPluginManager().loadPlugins(getPluginsFolder())));
        //noinspection ComparatorMethodParameterNotUsed
        plugins.sort(
                (o1, o2) ->
                        (o1.getDescription().getDepend().contains(o2.getDescription().getName())
                                ||
                                o1.getDescription().getSoftDepend().contains(o2.getDescription().getName()))
                                ? 1 : -1
        );

        // we must call onLoad() first.
        for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext(); ) {
            Plugin plugin = iterator.next();

            // onLoad
            PluginDescription description = plugin.getDescription();
            plugin.getLogger().info("Loading {} version {}", description.getName(), description.getVersion());
            try {
                plugin.onLoad();
            } catch (Throwable e) {
                plugin.getLogger().error("Unable to load this plugin", e);
                iterator.remove();
            }
            // end onLoad
        }
        return this.plugins = plugins;
    }

    protected final void enablePlugins(List<Plugin> plugins) {
        for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext(); ) {
            Plugin plugin = iterator.next();

            try {
                plugin.reloadConfig(); // ensure the default configuration will be loaded
            } catch (Exception e) {
                plugin.getLogger().error("Unable to load configuration", e);
            }

            // onEnable
            try {
                getCore().getPluginManager().enablePlugin(plugin);
            } catch (UnknownDependencyException e) {
                getCore().getLogger().error("Unable to enable plugin {} because unknown dependency detected.", plugin.getDescription().getName(), e);
                iterator.remove();
                continue;
            }
            if (!plugin.isEnabled()) {
                iterator.remove();
            } else {
                // Add the plugin into the known list to ensure the dependency system will work correctly
                getCore().getPluginManager().addPlugin(plugin);
            }
            // end onEnable
        }
    }

    protected void startNetwork() {
        connector = new Connector(this);
        connector.start();
    }

    protected void finishStart() {
        // region BotMarket support part - 2022/7/28
        String rawBotMarketUUID = getConfig().getString("botmarket-uuid");
        if (rawBotMarketUUID != null) {
            if (!rawBotMarketUUID.isEmpty()) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    UUID.fromString(rawBotMarketUUID);
                    new BotMarketPingThread(this, rawBotMarketUUID).start();
                } catch (IllegalArgumentException e) {
                    getCore().getLogger().warn("Invalid UUID of BotMarket. We won't schedule the PING task for BotMarket.");
                }
            }
        }
        // endregion
    }

    // If you need console (normally you won't need it), call this
    // Note that this method won't return until the client stopped,
    // so call it in a single thread.
    public void loop() {
        getCore().getLogger().debug("Starting console");
        try {
            new Console(this).start();
        } catch (Exception e) {
            getCore().getLogger().error("Unexpected situation happened during the main loop.", e);
        }
        getCore().getLogger().debug("REPL end");
    }

    // Shutdown this client, and loop() method will return after this method completes.
    public void shutdown() {
        getCore().getLogger().debug("Client shutdown request received");
        if (!isRunning()) {
            getCore().getLogger().debug("The client has already stopped");
            return;
        }
        running = false; // make sure the client will shut down if Bot wish the client stop.

        getCore().getLogger().info("Stopping client");
        getCore().getPluginManager().clearPlugins();

        shutdownNetwork();
        eventExecutor.shutdown();
        getCore().getLogger().info("Stopping core");
        getCore().getLogger().info("Stopping scheduler (If the application got into infinite loop, please kill this process!)");
        ((SchedulerImpl) getCore().getScheduler()).shutdown();
        getCore().getLogger().info("Client stopped");
    }

    protected void shutdownNetwork() {
        if (connector != null) {
            connector.shutdown();
        }
    }

    public InternalPlugin getInternalPlugin() {
        return internalPlugin;
    }

    public EntityStorage getStorage() {
        return storage;
    }

    public EntityBuilder getEntityBuilder() {
        return entityBuilder;
    }

    public MessageBuilder getMessageBuilder() {
        return msgBuilder;
    }

    public EntityUpdater getEntityUpdater() {
        return entityUpdater;
    }

    public Connector getConnector() {
        return connector;
    }

    public NetworkClient getNetworkClient() {
        return networkClient;
    }

    public Session getSession() {
        return session;
    }

    public ExecutorService getEventExecutor() {
        return eventExecutor;
    }

    public PluginMixinConfigManager getPluginMixinConfigManager() {
        return pluginMixinConfigManager;
    }

    protected void registerInternal() {
        registerStopCommand();
        registerHelpCommand();
        registerPluginsCommand();
    }

    protected void registerStopCommand() {
        new JKookCommand("stop")
                .setDescription("停止 " + SharedConstants.IMPL_NAME + " 实例。")
                .setExecutor(wrapConsoleCmd((args) -> shutdown()))
                .register(getInternalPlugin());
    }

    protected void registerPluginsCommand() {
        new JKookCommand("plugins")
                .setDescription("获取已安装到此 " + SharedConstants.IMPL_NAME + " 实例的插件列表。")
                .setExecutor(
                        (sender, arguments, message) -> {
                            if (sender instanceof User && message == null) {
                                // executed by CommandManager#executeCommand?
                                return;
                            }
                            String result = String.format(
                                    "已安装并正在运行的插件 (%s): %s",
                                    getCore().getPluginManager().getPlugins().length,
                                    String.join(", ",
                                            Arrays.stream(getCore().getPluginManager().getPlugins())
                                                    .map(IT -> IT.getDescription().getName())
                                                    .collect(Collectors.toSet())));
                            if (sender instanceof User) {
                                message.sendToSource(new MarkdownComponent(result));
                            } else {
                                getCore().getLogger().info(result);
                            }
                        })
                .register(getInternalPlugin());
    }

    protected void registerHelpCommand() {
        new JKookCommand("help")
                .setDescription("获取此帮助列表。")
                .setExecutor(
                        (commandSender, args, message) -> {
                            if (commandSender instanceof User && message == null) {
                                // executed by CommandManager#executeCommand?
                                return;
                            }
                            JKookCommand[] result;
                            if (args.length != 0) {
                                String helpWanted = (String) args[0];
                                WrappedCommand command = ((CommandManagerImpl) getCore().getCommandManager())
                                        .getCommand(helpWanted);
                                if (command == null) {
                                    if (commandSender instanceof User) {
                                        message.sendToSource(new MarkdownComponent("找不到命令。"));
                                    } else if (commandSender instanceof ConsoleCommandSender) {
                                        getCore().getLogger().info("Unknown command.");
                                    }
                                    return;
                                }
                                result = new JKookCommand[]{command.getCommand()};
                            } else {
                                result = ((CommandManagerImpl) getCore().getCommandManager()).getCommandSet()
                                        .toArray(new JKookCommand[0]);
                            }

                            List<String> helpList = getHelp(result);

                            if (commandSender instanceof ConsoleCommandSender) {
                                for (String s : helpList) {
                                    getCore().getLogger().info(s);
                                }
                            } else if (commandSender instanceof User) {
                                helpList.removeIf(IT -> IT.startsWith("(/)stop:"));

                                if (getConfig().getBoolean("allow-help-ad", true)) {
                                    helpList.add(
                                            String.format(
                                                    "由 [%s](%s) v%s 驱动 - %s API %s",
                                                    SharedConstants.IMPL_NAME,
                                                    SharedConstants.REPO_URL,
                                                    SharedConstants.IMPL_VERSION,
                                                    SharedConstants.SPEC_NAME,
                                                    getCore().getAPIVersion()
                                            )
                                    );
                                } else {
                                    helpList.remove(helpList.size() - 1);
                                }

                                String finalResult = String.join("\n", helpList.toArray(new String[0]));
                                message.sendToSource(new MarkdownComponent(finalResult));
                            }
                        })
                .register(getInternalPlugin());
    }

    public static List<String> getHelp(JKookCommand[] commands) {
        if (commands.length <= 0) { // I think this is impossible to happen!
            return Collections.singletonList("无法提供命令帮助。因为此 " + SharedConstants.IMPL_NAME + " 实例没有注册任何命令。");
        }
        List<String> result = new LinkedList<>();
        result.add("-------- 命令帮助 --------");
        if (commands.length > 1) {
            for (JKookCommand command : commands) {
                result.add(String.format("(%s)%s: %s", String.join(",", command.getPrefixes()), command.getRootName(),
                        (command.getDescription() == null) ? "此命令没有简介。" : command.getDescription()));
            }
            result.add(""); // the blank line as the separator
            result.add("注: 在每条命令帮助的开头，括号中用 \",\" 隔开的字符为此命令的前缀。");
            result.add("如 \"(/,.)blah\" 即 \"/blah\", \".blah\" 为同一条命令。");
        } else {
            JKookCommand command = commands[0];
            result.add(String.format("命令: %s", command.getRootName()));
            result.add(String.format("别称: %s", String.join(", ", command.getAliases())));
            result.add(String.format("可用前缀: %s", String.join(", ", command.getPrefixes())));
            result.add(
                    String.format("简介: %s",
                            (command.getDescription() == null)
                                    ? "此命令没有简介。"
                                    : command.getDescription()
                    )
            );
            if (command.getHelpContent() != null && !command.getHelpContent().isEmpty()) {
                result.add("详细帮助信息:");
                result.add(command.getHelpContent());
            }
        }
        result.add("-------------------------");
        return result;
    }
}
