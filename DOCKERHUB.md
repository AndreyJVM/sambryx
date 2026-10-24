# Sambryx

Sambryx is a lightweight and modern web interface for managing Samba file servers on Linux, built with Java 21, Spring Boot, and React. It operates using a Zero-Agent architecture, managing remote servers directly via SSH without requiring local agents or config mounts.

## SSH host identity verification

Before connecting, provision an OpenSSH known_hosts file with public host keys verified through a trusted administrative channel. Unknown, changed and revoked keys are rejected before SSH authentication. Mount the file read-only at /etc/sambryx/known_hosts; it must be readable by UID 10001. Do not blindly trust ssh-keyscan output. See the [host key guide](https://andreyjvm.github.io/sambryx/ssh-host-keys/).

## Quick Start

Run the container using Docker:

```bash
docker run -d \
  --name sambryx \
  --mount type=bind,src="$PWD/.local/ssh/known_hosts",dst=/etc/sambryx/known_hosts,readonly \
  -p 8080:8080 \
  --restart unless-stopped \
  andreyvorobevaqa/sambryx:latest
```

Open `http://localhost:8080/ui` in your browser. From the UI, you can connect to your Samba server using SSH credentials.

### Experimental Astra Linux Special Edition variant

The existing experimental image includes a JVM-specific MPROTECT relaxation. Compatibility
with Astra Linux SE 1.8 and Parsec has not been verified on a target system:

```bash
docker run -d \
  --name sambryx \
  --mount type=bind,src="$PWD/.local/ssh/known_hosts",dst=/etc/sambryx/known_hosts,readonly \
  -p 8080:8080 \
  --restart unless-stopped \
  andreyvorobevaqa/sambryx:astra-latest
```

The Astra image disables PaX MPROTECT only for the `java` executable inside the image. This permits the JVM JIT to start and reduces memory-execution protection for that Java process. The regular `latest` image keeps its existing runtime configuration. Versioned Astra images use the `astra-<version>` tag.

## Features
- Complete UI for managing Samba shares and permissions
- Manage global Samba configuration (`smb.conf`) remotely
- View and manage Samba users
- Zero-Agent architecture: connects to servers via SSH
- Secure REST API

## Runtime and Environment Variables
- The image packages the tested application JAR, including frontend assets.
- The application runs as UID/GID `10001:10001` and uses SSH credentials entered in the UI.
- `SERVER_PORT` (default: `8080`): The port the web application listens on.
- Docker is the supported deployment format. The Docker test sandbox is documented in the repository.

## Documentation and Source Code

For full documentation, setup guides, and local development sandbox instructions, please visit the [GitHub Repository](https://github.com/AndreyJVM/sambryx).
