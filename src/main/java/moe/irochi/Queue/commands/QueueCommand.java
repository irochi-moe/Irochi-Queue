package moe.irochi.Queue.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import moe.irochi.Queue.Queue;
import moe.irochi.Queue.QueuePlugin;
import moe.irochi.Queue.QueuedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class QueueCommand extends BaseCommand implements SimpleCommand {

    private static final List<String> tabCompletes = Arrays.asList("reload", "skip", "auto", "position", "remove");

    private final QueuePlugin plugin;

    public QueueCommand(@NotNull QueuePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        final String[] args = invocation.arguments();
        switch (args.length) {
            case 0:
            case 1:
                return filterByPermission(invocation.source(), tabCompletes, "queue.", args.length > 0 ? args[0] : null);
            case 2: {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "skip":
                    case "forget":
                    case "remove":
                        if (hasPrefixedPermission(invocation.source(), "queue.", args[0]))
                            return filterByStart(plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
                        break;
                }
            }
        }

        return Collections.emptyList();
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || (invocation.arguments().length > 0 && invocation.arguments()[0].equalsIgnoreCase("position"))) {
            if (!(invocation.source() instanceof Player player) || !QueuePlugin.instance().queued(player).isInQueue()) {
                invocation.source().sendMessage(Component.text("대기열에 참가하고 있지 않습니다.", NamedTextColor.RED));
                return;
            }

            QueuedPlayer queuedPlayer = QueuePlugin.instance().queued(player);
            player.sendMessage(Component.text(queuedPlayer.queue().getServerFormatted() + "의 현재 대기열: ", NamedTextColor.YELLOW).append(Component.text(queuedPlayer.position() + 1, NamedTextColor.GREEN).append(Component.text(" / ", NamedTextColor.YELLOW).append(Component.text(queuedPlayer.queue().getSubQueue(queuedPlayer).players().size(), NamedTextColor.GREEN)))));

            if (queuedPlayer.queue().paused())
                player.sendMessage(Component.text("현재 참가중인 대기열이 일시정지되어 있습니다.", NamedTextColor.GRAY));

            return;
        }

        if (!hasPrefixedPermission(invocation.source(), "queue.", args[0])) {
            invocation.source().sendMessage(Component.text("이 명령어를 실행할 권한이 없습니다.", NamedTextColor.RED));
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "skip" -> parseQueueSkip(invocation);
            case "reload" -> parseQueueReload(invocation);
            case "auto" -> parseQueueAutoQueue(invocation);
            case "forget" -> parseQueueForget(invocation);
            case "remove" -> parseQueueRemove(invocation);
            default -> invocation.source().sendMessage(Component.text(invocation.arguments()[0] + " 은(는) 올바른 명령어가 아닙니다.", NamedTextColor.RED));
        }
    }

    private void parseQueueSkip(Invocation invocation) {
        if (!invocation.source().hasPermission("queue.skip"))
            return;

        if (invocation.arguments().length < 2) {
            invocation.source().sendMessage(Component.text("인수가 부족합니다! 사용법: /queue skip [player].", NamedTextColor.RED));
            return;
        }

        Optional<Player> optPlayer = plugin.proxy().getPlayer(invocation.arguments()[1]);
        if (optPlayer.isEmpty()) {
            invocation.source().sendMessage(Component.text(invocation.arguments()[1] + " 은(는) 현재 오프라인이거나 존재하지 않습니다.", NamedTextColor.RED));
            return;
        }

        Player player = optPlayer.get();
        QueuedPlayer queuedPlayer = plugin.queued(player);
        if (!queuedPlayer.isInQueue()) {
            invocation.source().sendMessage(Component.text(player.getUsername() + " 은(는) 대기열에 참가하고 있지 않습니다.", NamedTextColor.RED));
            return;
        }

        Queue queue = queuedPlayer.queue();
        if (queue.paused()) {
            invocation.source().sendMessage(Component.text("대기열 " + player.getUsername() + " 이(가) 현재 일시정지되어 있습니다.", NamedTextColor.RED));
            return;
        }

        queue.remove(queuedPlayer);
        queuedPlayer.queue(null);

        player.createConnectionRequest(queue.getServer()).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                player.sendMessage(Component.text(queue.getServerFormatted() + " 에 들어왔습니다", NamedTextColor.GREEN));
                invocation.source().sendMessage(Component.text(player.getUsername() + " 이(가) " + queue.getServerFormatted() + "(으)로 보내졌습니다.", NamedTextColor.GREEN));
            }
        });
    }

    private void parseQueueReload(Invocation invocation) {
        if (!plugin.reload())
            invocation.source().sendMessage(Component.text("설정을 리로드할 수 없습니다. 콘솔에서 자세한 내용을 확인하세요", NamedTextColor.RED));
        else
            invocation.source().sendMessage(Component.text("설정을 성공적으로 리로드했습니다.", NamedTextColor.GREEN));
    }

    private void parseQueueAutoQueue(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("이 명령어는 콘솔에서 사용할 수 없습니다.", NamedTextColor.RED));
            return;
        }

        QueuedPlayer queuedPlayer = plugin.queued(player);

        queuedPlayer.setAutoQueueDisabled(!queuedPlayer.isAutoQueueDisabled());
        if (queuedPlayer.isAutoQueueDisabled())
            player.sendMessage(Component.text("접속한 후에 자동으로 대기열에 참가하지 않습니다.", NamedTextColor.GREEN));
        else
            player.sendMessage(Component.text("접속한 후 자동으로 마지막에 들어갔던 서버의 대기열에 참가합니다.", NamedTextColor.GREEN));

        // Remove the auto queue task for this player if they've got any
        if (queuedPlayer.isAutoQueueDisabled())
            plugin.removeAutoQueue(player);
    }

    private void parseQueueForget(Invocation invocation) {
        if (!invocation.source().hasPermission("queue.forget"))
            return;

        if (invocation.arguments().length < 2) {
            invocation.source().sendMessage(Component.text("잘못된 사용법입니다! 사용법: /queue forget <player>", NamedTextColor.RED));
            return;
        }

        Optional<Player> optPlayer = plugin.proxy().getPlayer(invocation.arguments()[1]);
        if (optPlayer.isEmpty()) {
            invocation.source().sendMessage(Component.text(invocation.arguments()[1] + " 은(는) 현재 오프라인이거나 존재하지 않습니다.", NamedTextColor.RED));
            return;
        }

        Player player = optPlayer.get();
        for (Queue queue : plugin.queues().values()) {
            queue.forget(player.getUniqueId());
        }

        invocation.source().sendMessage(Component.text(player.getUsername() + "'s position has been forgotten in all queues.", NamedTextColor.GREEN));
    }

    private void parseQueueRemove(Invocation invocation) {
        if (!invocation.source().hasPermission("queue.remove"))
            return;

        if (invocation.arguments().length < 2) {
            invocation.source().sendMessage(Component.text("잘못된 사용법입니다! 사용법: /queue remove <player>"));
            return;
        }

        Optional<Player> optPlayer = plugin.proxy().getPlayer(invocation.arguments()[1]);
        if (optPlayer.isEmpty()) {
            invocation.source().sendMessage(Component.text(invocation.arguments()[1] + " 은(는) 현재 오프라인이거나 존재하지 않습니다.", NamedTextColor.RED));
            return;
        }

        QueuedPlayer player = plugin.queued(optPlayer.get());

        if (player.isInQueue()) {
            Queue queue = player.queue();
            queue.remove(player);
            invocation.source().sendMessage(Component.text(player.name() + " 을/를 " + queue.getServerFormatted() + "서버의 대기열에서 제외시켰습니다.", NamedTextColor.GREEN));
        } else
            invocation.source().sendMessage(Component.text(player.name() + " 은(는) 대기열에 참가하고 있지 않습니다.", NamedTextColor.RED));
    }
}
