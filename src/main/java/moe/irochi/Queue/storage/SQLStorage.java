package moe.irochi.Queue.storage;

import moe.irochi.Queue.QueuePlugin;
import moe.irochi.Queue.QueuedPlayer;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public class SQLStorage extends Storage {
    private final QueuePlugin plugin;
    private String connectionUrl;
    private String username;
    private String password;
    private boolean enabled = false;

    public SQLStorage(@NotNull QueuePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() throws Exception {
        if (enabled)
            return;

        plugin.logger().info("SQL 저장소를 활성화합니다.");
        enabled = true;

        this.connectionUrl = "jdbc:mysql://" + plugin.config().getDatabaseHost() + ":" + plugin.config().getDatabasePort() + "/" + plugin.config().getDatabaseName() + plugin.config().getDatabaseFlags();
        this.username = plugin.config().getDatabaseUsername();
        this.password = plugin.config().getDatabasePassword();

        try {
            DriverManager.registerDriver((Driver) Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            plugin.logger().error("while registering sql driver", e);
        }

        try (Connection ignored = getConnection()) {
            plugin.logger().info("데이터베이스에 성공적으로 연결했습니다.");
        } catch (SQLException e) {
            enabled = false;
            throw e;
        }

        // create default table
        try (Connection connection = getConnection()) {
            connection.createStatement().execute("create table if not exists queue_players (`uuid` varchar(36) not null, primary key (`uuid`))");

            for (String column : SQLSchema.getPlayerColumns()) {
                connection.createStatement().execute("alter table queue_players add column if not exists " + column);
            }
        } catch (SQLException e) {
            plugin.logger().error("queue_players table 을 시작하는데 예외가 발생했습니다", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(this.connectionUrl, this.username, this.password);
    }

    @Override
    public void loadPlayer(@NotNull QueuedPlayer player) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement ps = connection.prepareStatement("SELECT * FROM queue_players WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, player.uuid().toString());

                try (ResultSet resultSet = ps.executeQuery()) {
                    if (resultSet.next()) {
                        player.setLastJoinedServer(resultSet.getString("lastJoinedServer"));
                        player.setAutoQueueDisabled(resultSet.getBoolean("autoQueueDisabled"));
                    }
                }
            } catch (SQLException e) {
                plugin.logger().error(player.name() + "의 데이터를 저장하는 도중 오류가 발생했습니다", e);
            }
        });
    }

    @Override
    public void savePlayer(@NotNull QueuedPlayer player) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                PreparedStatement ps = connection.prepareStatement("replace into queue_players (uuid, autoQueueDisabled, lastJoinedServer) values (?, ?, ?)")) {
                ps.setString(1, player.uuid().toString());
                ps.setBoolean(2, player.isAutoQueueDisabled());
                ps.setString(3, player.getLastJoinedServer().orElse(null));

                ps.execute();
            } catch (SQLException e) {
                plugin.logger().error(player.name() + " 의 데이터를 저장하는 도중 오류가 발생했습니다", e);
            }
        });
    }
}
