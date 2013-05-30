/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.actors.behaviors;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ActorBuilder;
import co.paralleluniverse.actors.ExitMessage;
import co.paralleluniverse.actors.LifecycleMessage;
import co.paralleluniverse.actors.LocalActor;
import co.paralleluniverse.actors.ShutdownMessage;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jsr166e.ConcurrentHashMapV8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pron
 */
public class Supervisor extends LocalActor<Object, Void> {
    private static final Logger LOG = LoggerFactory.getLogger(Supervisor.class);
    private final RestartStrategy restartStrategy;
    private final List<ActorEntry> children = new ArrayList<ActorEntry>();
    private final ConcurrentMap<Object, ActorEntry> childrenByName = new ConcurrentHashMapV8<Object, ActorEntry>();

    public Supervisor(Strand strand, String name, int mailboxSize, RestartStrategy restartStrategy, List<ActorInfo> childSpec) {
        super(strand, name, mailboxSize);
        this.restartStrategy = restartStrategy;
        for (ActorInfo cs : childSpec)
            addChild1(cs, null);
    }

    public Supervisor(String name, int mailboxSize, RestartStrategy restartStrategy, List<ActorInfo> childSpec) {
        this(null, name, mailboxSize, restartStrategy, childSpec);
    }

    public Supervisor(String name, int mailboxSize, RestartStrategy restartStrategy, ActorInfo... childSpec) {
        this(name, mailboxSize, restartStrategy, Arrays.asList(childSpec));
    }

    public Supervisor(String name, RestartStrategy restartStrategy, List<ActorInfo> childSpec) {
        this(null, name, -1, restartStrategy, childSpec);
    }

    public Supervisor(String name, RestartStrategy restartStrategy, ActorInfo... childSpec) {
        this(name, -1, restartStrategy, Arrays.asList(childSpec));
    }

    public Supervisor(RestartStrategy restartStrategy, List<ActorInfo> childSpec) {
        this(null, null, -1, restartStrategy, childSpec);
    }

    public Supervisor(RestartStrategy restartStrategy, ActorInfo... childSpec) {
        this(restartStrategy, Arrays.asList(childSpec));
    }

    public <Message, V> LocalActor<Message, V> getChild(Object name) {
        final ActorEntry child = findEntryByName(name);
        if (child == null)
            return null;
        return (LocalActor<Message, V>) child.actor;
    }

    public void shutdown() {
        send(new ShutdownMessage(null));
    }

    @Override
    protected final Void doRun() throws InterruptedException, SuspendExecution {
        for (ActorEntry child : children)
            start(child);

        try {
            for (;;) {
                final Object m1 = receive(); // we care only about lifecycle messages
                if (m1 instanceof ShutdownMessage)
                    break;
                else if (m1 instanceof GenRequestMessage) {
                    final GenRequestMessage req = (GenRequestMessage) m1;
                    try {
                        if (m1 instanceof AddChildMessage) {
                            final AddChildMessage m = (AddChildMessage) m1;
                            RequestReplyHelper.reply(m, addChild(m.info, m.actor));
                        }
                    } catch (Exception e) {
                        req.getFrom().send(new GenErrorResponseMessage(req.getId(), e));
                    }
                }
            }
        } catch (InterruptedException e) {
        } finally {
            shutdownChildren();

            childrenByName.clear();
            children.clear();
        }

        return null;
    }

    private ActorEntry addChild1(ActorInfo info, LocalActor actor) {
        if (actor == null && info.builder instanceof LocalActor)
            actor = (LocalActor) info.builder;
        if (actor != null && findEntry(actor) != null)
            throw new SupervisorException("Supervisor " + this + " already supervises actor " + actor);
        Object name = info.getName();
        if (name == null && actor != null)
            name = actor.getName();
        if (findEntryByName(name) != null)
            throw new SupervisorException("Supervisor " + this + " already supervises an actor by the name " + name);
        final ActorEntry child = new ActorEntry(info, actor);
        children.add(child);
        if (name != null)
            childrenByName.put(name, child);
        return child;
    }

    public Actor addChild(ActorInfo info, LocalActor actor) throws SuspendExecution, InterruptedException {
        if (isInActor()) {
            final ActorEntry child = addChild1(info, actor);

            if (actor == null)
                start(child);
            else {
                child.actor = actor;
                child.watch = watch(actor);
            }
            return actor;
        } else {
            final GenResponseMessage res = RequestReplyHelper.call(this, new AddChildMessage(currentActor(), randtag(), info, actor));
            return ((GenValueResponseMessage<Actor>) res).getValue();
        }
    }

    public boolean removeChild(Object name, boolean terminate) throws SuspendExecution, InterruptedException {
        if (isInActor()) {
            final ActorEntry child = findEntryByName(name);
            if (child == null)
                return false;

            if (child.actor != null) {
                unwatch(child);

                if (terminate)
                    shutdownChild(child, false);
                else
                    unwatch(child);
            }

            childrenByName.remove(name);
            children.remove(child);

            return true;
        } else {
            final GenResponseMessage res = RequestReplyHelper.call(this, new RemoveChildMessage(currentActor(), randtag(), name, terminate));
            return ((GenValueResponseMessage<Boolean>) res).getValue();
        }
    }

    @Override
    protected void handleLifecycleMessage(LifecycleMessage m) {
        boolean handled = false;
        try {
            if (m instanceof ExitMessage) {
                final ExitMessage death = (ExitMessage) m;
                if (death.getWatch() != null && death.actor instanceof LocalActor) {
                    final LocalActor actor = (LocalActor) death.actor;
                    final ActorEntry child = findEntry(actor);

                    if (child != null) {
                        LOG.info(this + " detected child death: " + child + ". (casue: {})", death.cause);
                        if (!restartStrategy.onChildDeath(this, child, death.cause)) {
                            LOG.info("Supervisor {} giving up.", this);
                            getStrand().interrupt();
                        }
                        handled = true;
                    }
                }
            } else if (m instanceof ShutdownMessage) {
                LOG.info("Supervisor {} shutting down.", this);
                shutdownChildren();
                handled = true;
                getStrand().interrupt();
            }
            if (!handled)
                super.handleLifecycleMessage(m);
        } catch (InterruptedException e) {
            getStrand().interrupt();
        }
    }

    private boolean tryRestart(ActorEntry child, Throwable cause, long now) throws InterruptedException {
        LOG.info("Supervisor trying to restart child {}. (casue: {})", child, cause);
        verifyInActor();
        switch (child.info.mode) {
            case TRANSIENT:
                if (cause == null)
                    return true;
            // fall through
            case PERMANENT:
                final Actor actor = child.actor;
                shutdownChild(child, true);
                child.restartHistory.addRestart(now);
                if (child.restartHistory.numRestarts(now - child.info.unit.toMillis(child.info.duration)) > child.info.maxRestarts) {
                    LOG.warn(this + ": too many restarts for child {}. Giving up.", actor);
                    return false;
                }
                start(child);
                return true;
            case TEMPORARY:
                return true;
            default:
                throw new AssertionError();
        }
    }

    private LocalActor start(ActorEntry child) {
        final LocalActor old = child.actor;
        if (old != null && !old.isDone())
            throw new IllegalStateException("Actor " + child.actor + " cannot be restarted because it is not dead");

        final LocalActor actor = child.info.builder.build();
        if (actor.getName() == null && child.info.name != null)
            actor.setName(child.info.name);

        LOG.info("{} starting child {}", this, actor);

        if (old != null && actor.getMonitor() == null && old.getMonitor() != null)
            actor.setMonitor(old.getMonitor());
        if (actor.getMonitor() != null)
            actor.getMonitor().addRestart();

        Strand strand = createStrandForActor(child.actor != null ? child.actor.getStrand() : null, actor);

        child.actor = actor;
        child.watch = watch(actor);
        strand.start();
        return actor;
    }

    private void shutdownChild(ActorEntry child, boolean beforeRestart) throws InterruptedException {
        if (child.actor != null) {
            LOG.info("{} shutting down child {}", this, child.actor);
            unwatch(child);
            ((Actor) child.actor).send(new ShutdownMessage(this));
            try {
                joinChild(child);
            } finally {
                if (!beforeRestart) {
                    child.actor.stopMonitor();
                    child.actor = null;
                }
            }
        }
    }

    private void shutdownChildren() throws InterruptedException {
        LOG.info("{} shutting down all children.", this);
        for (ActorEntry child : children) {
            if (child.actor != null) {
                unwatch(child);
                ((Actor) child.actor).send(new ShutdownMessage(this));
            }
        }

        for (ActorEntry child : children) {
            if (child.actor != null) {
                try {
                    joinChild(child);
                } finally {
                    child.actor = null;
                }
            }
        }
    }

    private boolean joinChild(ActorEntry child) throws InterruptedException {
        if (child.actor != null) {
            try {
                child.actor.join(child.info.shutdownDeadline, TimeUnit.MILLISECONDS);
                return true;
            } catch (ExecutionException ex) {
                LOG.info(this + ": child {} died with exception {}", child.actor, ex.getCause());
                return true;
            } catch (TimeoutException ex) {
                LOG.warn(this + ": child {} shutdown timeout", child.actor);
                // is this the best we can do?
                child.actor.getStrand().interrupt();
                return false;
            }
        } else
            return true;
    }

    private void unwatch(ActorEntry child) {
        if (child.actor != null && child.watch != null) {
            unwatch(child.actor, child.watch);
            child.watch = null;
        }
    }

    private Strand createStrandForActor(Strand oldStrand, LocalActor actor) {
        final Strand strand;
        if (oldStrand != null)
            strand = Strand.clone(oldStrand, actor);
        else
            strand = new Fiber(actor);
        actor.setStrand(strand);
        return strand;
    }

    private ActorEntry findEntry(LocalActor actor) {
        if (actor.getName() != null) {
            ActorEntry child = findEntryByName(actor.getName());
            if (child != null)
                return child;
        }
        for (ActorEntry child : children) {
            if (child.actor == actor)
                return child;
        }
        return null;
    }

    private ActorEntry findEntryByName(Object name) {
        return childrenByName.get(name);
    }

    private long now() {
        return System.currentTimeMillis() / 1000000;
    }

    public enum ActorMode {
        PERMANENT, TRANSIENT, TEMPORARY
    };

    public enum RestartStrategy {
        ONE_FOR_ONE {
            @Override
            boolean onChildDeath(Supervisor supervisor, ActorEntry child, Throwable cause) throws InterruptedException {
                return supervisor.tryRestart(child, cause, supervisor.now());
            }
        },
        ALL_FOR_ONE {
            @Override
            boolean onChildDeath(Supervisor supervisor, ActorEntry child, Throwable cause) throws InterruptedException {
                supervisor.shutdownChildren();
                for (ActorEntry c : supervisor.children) {
                    if (!supervisor.tryRestart(c, cause, supervisor.now()))
                        return false;
                }
                return true;
            }
        },
        REST_FOR_ONE {
            @Override
            boolean onChildDeath(Supervisor supervisor, ActorEntry child, Throwable cause) throws InterruptedException {
                boolean found = false;
                for (ActorEntry c : supervisor.children) {
                    if (c == child)
                        found = true;

                    if (found && !supervisor.tryRestart(c, cause, supervisor.now()))
                        return false;
                }
                return true;
            }
        };

        abstract boolean onChildDeath(Supervisor supervisor, ActorEntry child, Throwable cause) throws InterruptedException;
    }

    private static class AddChildMessage extends GenRequestMessage {
        final ActorInfo info;
        final LocalActor actor;

        public AddChildMessage(Actor from, Object id, ActorInfo info, LocalActor actor) {
            super(from, id);
            this.info = info;
            this.actor = actor;
        }

        public AddChildMessage(Actor from, Object id, ActorInfo info) {
            this(from, id, info, null);
        }
    }

    private static class RemoveChildMessage extends GenRequestMessage {
        final Object name;
        final boolean terminate;

        public RemoveChildMessage(Actor from, Object id, Object name, boolean terminate) {
            super(from, id);
            this.name = name;
            this.terminate = terminate;
        }
    }

    public static class ActorInfo {
        final Object name;
        final ActorBuilder<?, ?> builder;
        final ActorMode mode;
        final int maxRestarts;
        final long duration;
        final TimeUnit unit;
        final long shutdownDeadline;

        public ActorInfo(Object name, ActorBuilder<?, ?> builder, ActorMode mode, int maxRestarts, long duration, TimeUnit unit, long shutdownDeadline) {
            this.name = name;
            this.builder = builder;
            this.mode = mode;
            this.maxRestarts = maxRestarts;
            this.duration = duration;
            this.unit = unit;
            this.shutdownDeadline = shutdownDeadline;
        }

        public Object getName() {
            return name;
        }

        public ActorBuilder<?, ?> getBuilder() {
            return builder;
        }

        public ActorMode getMode() {
            return mode;
        }

        public int getMaxRestarts() {
            return maxRestarts;
        }

        public long getDuration() {
            return duration;
        }

        public TimeUnit getDurationUnit() {
            return unit;
        }

        public long getShutdownDeadline() {
            return shutdownDeadline;
        }

        @Override
        public String toString() {
            return "ActorInfo{" + "builder: " + builder + ", mode: " + mode + ", maxRestarts: " + maxRestarts + ", duration: " + duration + ", unit: " + unit + ", shutdownDeadline: " + shutdownDeadline + '}';
        }
    }

    private static class ActorEntry {
        final ActorInfo info;
        final RestartHistory restartHistory;
        Object watch;
        volatile LocalActor<?, ?> actor;

        public ActorEntry(ActorInfo info) {
            this(info, null);
        }

        public ActorEntry(ActorInfo info, LocalActor<?, ?> actor) {
            this.info = info;
            this.restartHistory = new RestartHistory(info.maxRestarts);

            this.actor = actor;
        }

        @Override
        public String toString() {
            return "ActorEntry{" + "info=" + info + " actor=" + actor + '}';
        }
    }

    private static class RestartHistory {
        private final long[] restarts;
        private int index;

        public RestartHistory(int windowSize) {
            this.restarts = new long[windowSize];
            this.index = 0;
        }

        public void addRestart(long now) {
            restarts[index] = now;
            index = mod(index + 1);
        }

        public int numRestarts(long since) {
            int count = 0;
            for (int i = mod(index - 1); i != index; i = mod(i - 1)) {
                if (restarts[i] < since) // || restarts[i] == 0L is implied
                    break;
                count++;
            }
            return count;
        }

        private int mod(int i) {
            // could be made fast by forcing restarts.length to be a power of two, but for now, we don't need this to be fast.
            if (i >= restarts.length)
                return i - restarts.length;
            if (i < 0)
                return i + restarts.length;
            return i;
        }
    }
}
