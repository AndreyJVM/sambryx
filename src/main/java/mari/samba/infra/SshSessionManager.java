package mari.samba.infra;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SshSessionManager implements CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(SshSessionManager.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SambaProperties properties;

    public SshSessionManager(@Autowired(required = false) SambaProperties properties) {
        this.properties = properties;
    }

    private Duration getConnectTimeout() {
        return properties != null && properties.ssh() != null
                ? properties.ssh().connectTimeout()
                : Duration.ofMillis(7000);
    }

    private Duration getCommandTimeout() {
        return properties != null && properties.ssh() != null
                ? properties.ssh().commandTimeout()
                : Duration.ofSeconds(30);
    }

    public void createSession(String sessionId, ConnectionRequestDto request) throws Exception {
        JSch jsch = new JSch();
        String knownHosts = properties != null && properties.ssh() != null
                ? properties.ssh().knownHosts()
                : "/etc/sambryx/known_hosts";
        VerifiedHostKeys hostKeys = VerifiedHostKeys.configure(jsch, knownHosts);
        Session session = null;
        boolean published = false;
        try {
            if (request.isKeyAuth()) {
                byte[] key = request.getPrivateKey().getBytes(StandardCharsets.UTF_8);
                byte[] passphrase = request.getPassphrase() != null
                                && !request.getPassphrase().isBlank()
                        ? request.getPassphrase().getBytes(StandardCharsets.UTF_8)
                        : null;
                jsch.addIdentity("login-key", key, null, passphrase);
            }
            session = jsch.getSession(request.getUsername(), request.getHost(), request.getPort());
            session.setConfig("StrictHostKeyChecking", "yes");
            if (!request.isKeyAuth()) {
                session.setPassword(request.getPassword());
            }
            session.connect((int) getConnectTimeout().toMillis());
            sessions.put(sessionId, session);
            published = true;
        } catch (JSchException | RuntimeException e) {
            if (hostKeys.rejection() != null) {
                throw hostKeys.rejection();
            }
            throw new SambaCommandException("SSH connection failed. Check the connection details and credentials.");
        } finally {
            if (!published && session != null) {
                session.disconnect();
            }
        }
    }

    public Session getSession(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.isConnected()) {
            if (sessionId != null) {
                sessions.remove(sessionId);
            }
            throw new SshSessionExpiredException("SSH session not found or disconnected. Please log in again.");
        }
        return session;
    }

    public void disconnect(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH session disconnected.");
        }
    }

    public void cleanupInactiveSessions() {
        sessions.entrySet().removeIf(entry -> !entry.getValue().isConnected());
    }

    @Override
    public String execute(String sessionId, String command) {
        return execute(sessionId, command, null);
    }

    @Override
    public String execute(String sessionId, String command, String inputData) {
        CommandResult result = executeCommand(sessionId, command, inputData);
        if (!result.isSuccess()) {
            throw new SambaCommandException("SSH command failed with code " + result.exitCode() + ".");
        }
        return result.stdout();
    }

    @Override
    public CommandResult executeCommand(String sessionId, String command) {
        return executeCommand(sessionId, command, null);
    }

    @Override
    public CommandResult executeCommand(String sessionId, String command, String inputData) {
        Session session = getSession(sessionId);
        ChannelExec channel = null;

        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

            try (InputStream stdout = channel.getInputStream();
                    InputStream stderr = channel.getErrStream()) {

                channel.connect((int) getConnectTimeout().toMillis());

                if (inputData != null) {
                    try (OutputStream out = channel.getOutputStream()) {
                        out.write(inputData.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }

                long deadline = System.currentTimeMillis() + getCommandTimeout().toMillis();
                byte[] buffer = new byte[1024];

                while (!channel.isClosed()) {
                    drainStreams(stdout, stderr, stdoutStream, stderrStream, buffer);
                    if (System.currentTimeMillis() > deadline) {
                        channel.disconnect();
                        throw new SambaCommandException("SSH command timed out after "
                                + getCommandTimeout().toMillis() + " ms.");
                    }
                    Thread.sleep(50);
                }

                drainStreams(stdout, stderr, stdoutStream, stderrStream, buffer);
            }

            int exitStatus = channel.getExitStatus();
            String stdoutOutput = stdoutStream.toString(StandardCharsets.UTF_8);
            String stderrOutput = stderrStream.toString(StandardCharsets.UTF_8);

            if (exitStatus != 0) {
                log.warn("SSH command failed with code {}", exitStatus);
            }

            return new CommandResult(exitStatus, stdoutOutput, stderrOutput);

        } catch (SambaCommandException e) {
            throw e;
        } catch (Exception e) {
            // Commands, remote output and exception messages can contain credentials.
            log.error("SSH command execution failed ({})", e.getClass().getSimpleName());
            throw new SambaCommandException("SSH command execution failed.");
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    private void drainStreams(
            InputStream stdout,
            InputStream stderr,
            ByteArrayOutputStream stdoutStream,
            ByteArrayOutputStream stderrStream,
            byte[] buffer)
            throws Exception {
        while (stdout.available() > 0) {
            int read = stdout.read(buffer, 0, buffer.length);
            if (read < 0) {
                break;
            }
            stdoutStream.write(buffer, 0, read);
        }
        while (stderr.available() > 0) {
            int read = stderr.read(buffer, 0, buffer.length);
            if (read < 0) {
                break;
            }
            stderrStream.write(buffer, 0, read);
        }
    }
}
