package io.github.sudoitir.artemisstudio.kernel.core;

import org.springframework.context.SmartLifecycle;

/**
 * One resource released at one {@link ShutdownPhases phase} when the context stops. A module
 * declares its steps as beans; the phases, not bean destruction order, decide the sequence.
 *
 * <p>A stop is not always the end: a context can be stopped and started again (the test
 * framework pauses idle cached contexts this way), so a step that released something reopens
 * it on start.
 */
public final class ShutdownStep implements SmartLifecycle {

    private final String name;
    private final int phase;
    private final Runnable release;
    private final Runnable resume;
    private volatile boolean running;
    private volatile boolean stopped;

    /** A step whose resource needs nothing to be used again after a restart. */
    public ShutdownStep(String name, int phase, Runnable release) {
        this(name, phase, release, () -> {});
    }

    public ShutdownStep(String name, int phase, Runnable release, Runnable resume) {
        this.name = name;
        this.phase = phase;
        this.release = release;
        this.resume = resume;
    }

    public String name() {
        return name;
    }

    @Override
    public void start() {
        if (stopped) {
            resume.run();
        }
        running = true;
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            stopped = true;
            release.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return phase;
    }
}
