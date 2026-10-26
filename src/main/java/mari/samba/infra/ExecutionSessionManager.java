package mari.samba.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.core.SshSessionExpiredException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class ExecutionSessionManager implements CommandExecutor {
    private final Map<String, State> sessions = new ConcurrentHashMap<>();
    private final ThreadLocal<State> admitted = new ThreadLocal<>();
    private final SshSessionManager sshExecutor;
    private final Clock clock;

    public ExecutionSessionManager(SshSessionManager sshExecutor) {
        this(sshExecutor, Clock.systemUTC());
    }

    @Autowired
    public ExecutionSessionManager(SshSessionManager sshExecutor, Clock clock) {
        this.sshExecutor = sshExecutor;
        this.clock = clock;
    }

    public void createSession(String sessionId, ConnectionRequestDto request) throws Exception {
        disconnect(sessionId);
        sshExecutor.createSession(sessionId, request);
        sessions.put(sessionId, new State(sessionId));
    }

    public void disconnect(String sessionId) {
        disconnect(sessionId, clock.instant());
    }

    public void disconnect(String sessionId, Instant revokedAt) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) return;
        synchronized (state) {
            Instant limit = revokedAt.plus(Duration.ofMinutes(5));
            if (state.closeAt == null || limit.isBefore(state.closeAt)) state.closeAt = limit;
            if (state.active == 0 || !clock.instant().isBefore(state.closeAt)) close(state);
        }
    }

    public void cleanupDrainingSessions() {
        for (State state : sessions.values()) {
            synchronized (state) {
                if (state.closeAt != null && !clock.instant().isBefore(state.closeAt)) close(state);
            }
        }
    }

    /** The calling thread owns this synchronous scope, including continuation after revocation. */
    public Operation beginOperation(String sessionId) {
        State previous = admitted.get();
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) throw expired();
        synchronized (state) {
            if (state.closeAt != null && !clock.instant().isBefore(state.closeAt)) close(state);
            if (state.closed || (state.closeAt != null && previous != state)) throw expired();
            state.active++;
        }
        admitted.set(state);
        return new Operation(state, previous);
    }

    @Override
    public String execute(String sessionId, String command) {
        try (var ignored = beginOperation(sessionId)) {
            return sshExecutor.execute(sessionId, command);
        }
    }

    @Override
    public String execute(String sessionId, String command, String inputData) {
        try (var ignored = beginOperation(sessionId)) {
            return sshExecutor.execute(sessionId, command, inputData);
        }
    }

    @Override
    public CommandResult executeCommand(String sessionId, String command, String inputData) {
        try (var ignored = beginOperation(sessionId)) {
            return sshExecutor.executeCommand(sessionId, command, inputData);
        }
    }

    private void close(State state) {
        if (state.closed) return;
        state.closed = true;
        sessions.remove(state.id, state);
        sshExecutor.disconnect(state.id);
    }

    private static SshSessionExpiredException expired() {
        return new SshSessionExpiredException("Execution session not found or expired. Please log in again.");
    }

    public final class Operation implements AutoCloseable {
        private final State state;
        private final State previous;
        private boolean released;

        private Operation(State state, State previous) {
            this.state = state;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            if (previous == null) admitted.remove();
            else admitted.set(previous);
            synchronized (state) {
                state.active--;
                if (state.closeAt != null && state.active == 0) ExecutionSessionManager.this.close(state);
            }
        }
    }

    private static final class State {
        final String id;
        int active;
        Instant closeAt;
        boolean closed;

        State(String id) {
            this.id = id;
        }
    }
}
