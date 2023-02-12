package moe.irochi.Queue.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import moe.irochi.Queue.QueuePlugin;
import moe.irochi.Queue.QueuedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collections;
import java.util.List;

public class LeaveCommand extends BaseCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("이 명령어는 콘솔에서 사용할 수 없습니다.", NamedTextColor.RED));
            return;
        }

        QueuedPlayer queuedPlayer = QueuePlugin.instance().queued(player);

        if (!queuedPlayer.isInQueue()) {
            player.sendMessage(Component.text("대기열에 참가하고 있지 않습니다.", NamedTextColor.RED));
            return;
        }

        queuedPlayer.queue().remove(queuedPlayer);
        queuedPlayer.queue(null);
        player.sendMessage(Component.text("대기열에서 나갔습니다.", NamedTextColor.GREEN));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }
}
