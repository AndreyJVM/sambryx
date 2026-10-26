package mari.samba.session;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.ExecutionSessionManager;
import org.springframework.scheduling.annotation.Scheduled;

/** Owns administrator deadlines independently of servlet request access timestamps. */
public class AdministratorSessions {
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);
    private final Map<String, State> sessions = new ConcurrentHashMap<>();
    private final ExecutionSessionManager execution;
    private final Clock clock;

    public AdministratorSessions(ExecutionSessionManager execution, Clock clock) {
        this.execution = execution;
        this.clock = clock;
    }

    public synchronized void forget(String sessionId) {
        sessions.remove(sessionId);
    }

    public synchronized void register(HttpSession session) {
        if (session.getAttribute("sambaUser") == null) {
            throw new IllegalStateException("Cannot register an unauthenticated administrator session.");
        }
        sessions.put(session.getId(), new State(session, clock.instant().plus(IDLE_TIMEOUT)));
    }

    public synchronized Snapshot status(HttpSession session, boolean activity) {
        State state = session == null ? null : sessions.get(session.getId());
        Instant now = clock.instant();
        if (state == null || !now.isBefore(state.deadline)) {
            if (state != null) revoke(state);
            throw new SshSessionExpiredException("Administrator session expired. Please log in again.");
        }
        if (activity) state.deadline = now.plus(IDLE_TIMEOUT);
        return new Snapshot(now.toEpochMilli(), state.deadline.toEpochMilli());
    }

    public synchronized ExecutionSessionManager.Operation admit(HttpSession session, boolean activity) {
        status(session, activity);
        return execution.beginOperation(session.getId());
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public synchronized void cleanup() {
        Instant now = clock.instant();
        for (State state : sessions.values()) {
            try {
                if (!now.isBefore(state.deadline) || state.session.getAttribute("sambaUser") == null) revoke(state);
            } catch (IllegalStateException ignored) {
                sessions.remove(state.id, state);
                execution.disconnect(state.id);
            }
        }
        execution.cleanupDrainingSessions();
    }

    private void revoke(State state) {
        sessions.remove(state.id, state);
        Instant now = clock.instant();
        execution.disconnect(state.id, now.isBefore(state.deadline) ? now : state.deadline);
        try {
            state.session.invalidate();
        } catch (IllegalStateException ignored) {
            // Another request may already have invalidated the servlet session.
        }
    }

    public record Snapshot(long serverTime, long deadline) {}

    private static final class State {
        final HttpSession session;
        final String id;
        Instant deadline;

        State(HttpSession session, Instant deadline) {
            this.session = session;
            this.id = session.getId();
            this.deadline = deadline;
        }
    }
}
