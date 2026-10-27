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

## Image and target systems

Sambryx ships one standard image based on Alpine with JRE 21. Debian and Ubuntu are
target server/host families; they do not have separate Sambryx images. The repository
sandbox uses an Ubuntu 22.04 Samba node, and Production Compose Smoke uses Ubuntu 24.04.
These container checks do not establish compatibility with every Debian/Ubuntu version
or native host installation; Debian is not separately tested in the current CI.

The experimental Astra image has been removed from current builds and publication.
Previously published `astra-*` tags are retained without further updates by this workflow.
Astra Linux 1.8 compatibility remains unverified and is tracked in
[issue #10](https://github.com/AndreyJVM/sambryx/issues/10) pending target-system access
and Docker + SSH validation. The standard image keeps its existing JVM configuration.

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
