package moe.irochi.Queue;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import moe.irochi.Queue.commands.BaseCommand;
import moe.irochi.Queue.commands.JoinCommand;
import moe.irochi.Queue.commands.LeaveCommand;
import moe.irochi.Queue.commands.PauseCommand;
import moe.irochi.Queue.commands.QueueCommand;
import moe.irochi.Queue.config.QueueConfig;
import moe.irochi.Queue.storage.FlatFileStorage;
import moe.irochi.Queue.storage.SQLStorage;
import moe.irochi.Queue.storage.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "queue", name = "Queue", version = "0.1.2", authors = {"Warriorrr"})
public class QueuePlugin {

    private static QueuePlugin instance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path pluginFolderPath;
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();
    private final Map<UUID, QueuedPlayer> queuedPlayers = new HashMap<>();
    private QueueConfig config;
    private boolean debug = false;
    private Storage storage;
    private final Map<UUID, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();

    @Inject
    public QueuePlugin(ProxyServer proxy, CommandManager commandManager, Logger logger, @DataDirectory Path pluginFolderPath) {
        QueuePlugin.instance = this;
        this.proxy = proxy;
        this.logger = logger;
        this.pluginFolderPath = pluginFolderPath;

        commandManager.register("joinqueue", new JoinCommand(this));
        commandManager.register("leavequeue", new LeaveCommand());
        commandManager.register("pausequeue", new PauseCommand());
        commandManager.register("queue", new QueueCommand(this));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.config = new QueueConfig(this, pluginFolderPath);
        this.config.load();

        for (RegisteredServer server : proxy.getAllServers()) {
            queues.put(server.getServerInfo().getName().toLowerCase(Locale.ROOT), new Queue(server, this));
        }

        this.storage = config.getStorageType().equalsIgnoreCase("sql")
                ? new SQLStorage(this)
                : new FlatFileStorage(this, pluginFolderPath.resolve("data"));

        try {
            this.storage.enable();
        } catch (Exception e) {
            logger.error("저장소를 활성화하는 도중 예외가 발생했습니다, flatfile 저장소로 대체합니다.", e);
            this.storage = new FlatFileStorage(this, pluginFolderPath.resolve("data"));
        }

        // Load any paused queues from the paused-queues.json file.
        loadPausedQueues();

        proxy.getScheduler().buildTask(this, () -> {
            for (Queue queue : queues().values())
                queue.sendNext();
        }).repeat(500, TimeUnit.MILLISECONDS).schedule();

        proxy.getScheduler().buildTask(this, () -> {
            for (Queue queue : queues.values())
                queue.refreshMaxPlayers();
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.storage != null) {
            try {
                this.storage.disable();
            } catch (Exception e) {
                logger.error("저장소를 비활성화하는 도중 예외가 발생했습니다", e);
            }
        }

        savePausedQueues();
    }

    public boolean reload() {
        if (!this.config.reload())
            return false;

        // Disable storage if it isn't null
        if (this.storage != null) {
            try {
                this.storage.disable();
            } catch (Exception e) {
                logger.error("저장소를 비활성화하는 도중 예외가 발생했습니다.", e);
            }
        }

        this.storage = config.getStorageType().equalsIgnoreCase("sql")
                ? new SQLStorage(this)
                : new FlatFileStorage(this, pluginFolderPath.resolve("data"));

        try {
            this.storage.enable();
        } catch (Exception e) {
            logger.error("저장소를 활성화하는 도중 예외가 발생했습니다", e);
            this.storage = new FlatFileStorage(this, pluginFolderPath.resolve("data"));
        }

        return true;
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        // Load saved data for this player async upon login.
        queued(event.getPlayer()).loadData();
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();

        QueuedPlayer player = queuedPlayers.get(uuid);
        if (player != null) {
            if (player.isInQueue())
                player.queue().remove(player);

            event.getPlayer().getCurrentServer().ifPresent(server -> {
                // Set the player's last joined server if it isn't an auto queue server
                if (!config.autoQueueSettings().autoQueueServers().contains(server.getServerInfo().getName().toLowerCase(Locale.ROOT)))
                    player.setLastJoinedServer(server.getServerInfo().getName());
            });

            this.storage.savePlayer(player);
        }

        queuedPlayers.remove(uuid);
        removeAutoQueue(event.getPlayer());
    }

    @Subscribe
    public void onServerConnect(ServerConnectedEvent event) {
        QueuedPlayer player = queued(event.getPlayer());

        // Remove the player from their queue if their queue is for the server they just joined.
        if (player.isInQueue() && player.queue().getServer().getServerInfo().getName().equalsIgnoreCase(event.getServer().getServerInfo().getName()))
            player.queue().remove(player);

        processAutoQueue(event, player);
    }

    public void processAutoQueue(ServerConnectedEvent event, QueuedPlayer player) {
        final UUID uuid = event.getPlayer().getUniqueId();

        if (player.isAutoQueueDisabled()) {
            player.sendMessage(Component.text("자동 대기열이 현재 비활성화되어 있습니다, /joinqueue " + player.getLastJoinedServer().orElse(config.autoQueueSettings().defaultTarget()) + " 명령어를 입력해 직접 참가하거나 /queue auto 명령어를 사용해 자동 대기열을 다시 활성화시킬 수 있습니다.", NamedTextColor.GRAY));
            return;
        }

        if (
                scheduledTasks.containsKey(uuid) // There's already a scheduled auto queue task for this player
                || event.getPlayer().getPermissionValue("queue.autoqueue") == Tristate.FALSE // The player has the auto queue permission explicitly set to false
                || !config.autoQueueSettings().autoQueueServers().contains(event.getServer().getServerInfo().getName().toLowerCase(Locale.ROOT)) // The player isn't on one of the auto queue servers.
        )
            return;

        scheduledTasks.put(uuid, proxy().getScheduler().buildTask(this, () -> {
            scheduledTasks.remove(uuid);

            String target = player.getLastJoinedServer().orElse(config.autoQueueSettings().defaultTarget());
            final String currentServerName = event.getPlayer().getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("unknown");

            // Validate that the target is known to the proxy, it isn't an auto queue server, and the player has permissions to join it, otherwise just return the default target.
            target = proxy.getServer(target).map(server -> server.getServerInfo().getName())
                .filter(name -> !config.autoQueueSettings().autoQueueServers().contains(name.toLowerCase(Locale.ROOT)))
                .filter(name -> BaseCommand.hasPrefixedPermission(event.getPlayer(), "queue.join.", name))
                .orElse(config.autoQueueSettings().defaultTarget());

            // Prevent the player from being auto queued to the server they are already on
            if (target.equalsIgnoreCase(currentServerName))
                return;

            // Simply return if the player doesn't have permissions to join the default target.
            if (!BaseCommand.hasPrefixedPermission(event.getPlayer(), "queue.join.", target))
                return;

            Queue queue = queue(target);
            if (queue != null) {
                debug(event.getPlayer().getUsername() + " 이(가) " + target + " 의 대기열에 자동으로 참가했습니다.");
                event.getPlayer().sendMessage(Component.text(queue.getServerFormatted() + " 의 대기열에 자동으로 참가했습니다.", NamedTextColor.GREEN));
                queue.enqueue(player);
            }
        }).delay(config.autoQueueSettings().delay(), TimeUnit.SECONDS).schedule());
    }

    public void removeAutoQueue(Player player) {
        ScheduledTask task = scheduledTasks.remove(player.getUniqueId());
        if (task != null)
            task.cancel();
    }

    public Map<String, Queue> queues() {
        return queues;
    }

    public static QueuePlugin instance() {
        return instance;
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    @Nullable
    public Queue queue(String serverName) {
        Queue queue = queues.get(serverName.toLowerCase(Locale.ROOT));

        if (queue != null)
            return queue;

        // A queue with this name doesn't exist yet, create a new one if a server exists with its name
        Optional<RegisteredServer> registeredServer = proxy.getServer(serverName);
        if (registeredServer.isEmpty())
            return null;

        queue = new Queue(registeredServer.get(), this);
        queues.put(serverName.toLowerCase(Locale.ROOT), queue);

        return queue;
    }

    public QueuedPlayer queued(Player player) {
        return queuedPlayers.computeIfAbsent(player.getUniqueId(), k -> new QueuedPlayer(player));
    }

    public Collection<QueuedPlayer> queuedPlayers() {
        return queuedPlayers.values();
    }

    public static void debug(Object message) {
        if (instance != null && instance.debug)
            instance.logger.info(String.valueOf(message));
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Logger logger() {
        return this.logger;
    }

    public QueueConfig config() {
        return this.config;
    }

    public Storage storage() {
        return storage;
    }

    public void loadPausedQueues() {
        Path pausedQueuesPath = pluginFolderPath.resolve("paused-queues.json");

        if (Files.exists(pausedQueuesPath)) {
            @SuppressWarnings("UnstableApiUsage")
            Type type = new TypeToken<Map<String, Long>>() {}.getType();

            try {
                Map<String, Long> pausedQueues = new Gson().fromJson(Files.readString(pausedQueuesPath), type);
                for (Map.Entry<String, Long> entry : pausedQueues.entrySet()) {
                    Queue queue = queue(entry.getKey());
                    if (queue == null)
                        continue;

                    Instant instant = Instant.ofEpochSecond(entry.getValue());
                    if (Instant.now().isAfter(instant))
                        continue;

                    queue.pause(true, Instant.ofEpochSecond(entry.getValue()));
                    logger.info(entry.getKey() + " 의 대기열을 다시 일시정지시킵니다.");
                }

                try {
                    Files.deleteIfExists(pausedQueuesPath);
                } catch (IOException e) {
                    logger.error("paused-queues.json 파일을 삭제하지 못했습니다", e);
                }
            } catch (Exception ignored) {}
        }
    }

    public void savePausedQueues() {
        Map<String, Long> pausedQueues = new HashMap<>();
        for (Map.Entry<String, Queue> entry : this.queues().entrySet()) {
            if (entry.getValue().paused())
                pausedQueues.put(entry.getKey(), entry.getValue().unpauseTime().getEpochSecond());
        }

        if (pausedQueues.size() > 0) {
            Path pausedQueuesPath = pluginFolderPath.resolve("paused-queues.json");

            try {
                if (!Files.exists(pausedQueuesPath))
                    Files.createFile(pausedQueuesPath);

                Files.writeString(pausedQueuesPath, new Gson().toJson(pausedQueues));

                logger.info("일시정지된 대기열 " + pausedQueues.size() + " 을(를) paused-queues.json 에 성공적으로 저장했습니다");
            } catch (Exception e) {
                logger.error("일시정지된 대기열 " + pausedQueues.size() + " 을(를) 저장할 수 없습니다.", e);
            }
        }
    }
}
