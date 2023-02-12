package moe.irochi.Queue.commands;

import com.google.common.primitives.Ints;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import moe.irochi.Queue.Queue;
import moe.irochi.Queue.QueuePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public class PauseCommand extends BaseCommand implements SimpleCommand {

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!source.hasPermission("queue.pause")) {
            source.sendMessage(Component.text("이 명령어를 실행할 권한이 없습니다.", NamedTextColor.RED));
            return;
        }

        if (invocation.arguments().length == 0) {
            source.sendMessage(Component.text("잘못된 사용법입니다! 사용법: /pausequeue [queue] [seconds].", NamedTextColor.RED));
            return;
        }

        Queue queue = QueuePlugin.instance().queue(invocation.arguments()[0]);
        if (queue == null) {
            source.sendMessage(Component.text(invocation.arguments()[0] + " 은(는) 올바른 대기열이 아닙니다.", NamedTextColor.RED));
            return;
        }

        Instant unpauseTime = Instant.MAX;
        boolean usingSeconds = false;
        Integer seconds = 0;

        if (invocation.arguments().length > 1) {
            seconds = Ints.tryParse(invocation.arguments()[1]);

            usingSeconds = seconds != null;
            if (seconds != null)
                unpauseTime = Instant.now().plusSeconds(seconds);
        }

        if (queue.paused())
            queue.pause(false);
        else
            queue.pause(true, unpauseTime);

        String message = String.format("%s 서버의 대기열을 %s 했습니다.", invocation.arguments()[0], queue.paused() ? "활성화" : "비활성화");
        if (usingSeconds && queue.paused())
            message += seconds + "초 동안).";

        source.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }
}
