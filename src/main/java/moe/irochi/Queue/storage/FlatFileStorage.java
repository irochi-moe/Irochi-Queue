package moe.irochi.Queue.storage;

import moe.irochi.Queue.QueuePlugin;
import moe.irochi.Queue.QueuedPlayer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class FlatFileStorage extends Storage {
    private final QueuePlugin plugin;
    private final Path dataFolderPath; // Path to velocity /plugins/queue/data

    public FlatFileStorage(QueuePlugin plugin, Path dataFolderPath) {
        this.plugin = plugin;
        this.dataFolderPath = dataFolderPath;

        if (!Files.isDirectory(dataFolderPath)) {
            try {
                Files.createDirectory(dataFolderPath);
            } catch (IOException e) {
                plugin.logger().error("Queue/data 디렉토리를 생성하지 못했습니다.", e);
            }
        }

        plugin.logger().info("flatfile 저장소를 사용합니다.");
    }

    @Override
    public void loadPlayer(@NotNull QueuedPlayer player) {
        CompletableFuture.runAsync(() -> {
            try {
                Path dataFile = dataFolderPath.resolve(player.uuid() + ".txt");

                if (!Files.exists(dataFile))
                    return;

                Properties properties = new Properties();
                try (InputStream is = Files.newInputStream(dataFile)) {
                    properties.load(is);
                    player.setAutoQueueDisabled(Boolean.parseBoolean(properties.getProperty("autoQueueDisabled", "false")));
                    player.setLastJoinedServer(properties.getProperty("lastJoinedServer"));
                }
            } catch (IOException ignored) {}
        });
    }

    @Override
    public void savePlayer(@NotNull QueuedPlayer player) {
        CompletableFuture.runAsync(() -> {
            try {
                Path dataFile = dataFolderPath.resolve(player.uuid() + ".txt");

                Properties properties = new Properties();
                if (player.getLastJoinedServer().isPresent())
                    properties.setProperty("lastJoinedServer", player.getLastJoinedServer().get());

                properties.setProperty("autoQueueDisabled", String.valueOf(player.isAutoQueueDisabled()));

                try (OutputStream os = Files.newOutputStream(dataFile)) {
                    properties.store(os, null);
                }
            } catch (IOException e) {
                plugin.logger().error(player.uuid() + " 의 데이터를 저장하는 도중 오류가 발생했습니다", e);
            }
        });
    }
}
