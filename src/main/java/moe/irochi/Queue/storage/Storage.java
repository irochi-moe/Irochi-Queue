package moe.irochi.Queue.storage;

import moe.irochi.Queue.QueuedPlayer;
import org.jetbrains.annotations.NotNull;

public abstract class Storage {
    abstract public void loadPlayer(@NotNull QueuedPlayer player);

    abstract public void savePlayer(@NotNull QueuedPlayer player);

    public void enable() throws Exception {}

    public void disable() {}
}
