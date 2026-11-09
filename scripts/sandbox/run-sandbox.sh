#!/usr/bin/env bash

# ==============================================================================
# Sambryx - Docker Compose Sandbox Runner with Clean Teardown
# ==============================================================================
# This script:
# 1. Offers deployment modes with or without tests
# 2. Builds the current frontend on the host
# 3. Builds the backend JAR using the local Maven cache
# 4. Packages the web application image using --target local
# 5. Starts the environment with docker compose up -d
# 6. Waits for service readiness
# 7. Provides an interactive command menu:
#    - b / build / fast: rebuild without resetting data
#    - bf / build-full: remove containers and volumes, then rebuild
#    - restart / logs / status / stop
# 8. On exit (Enter, stop, q or Ctrl+C), stops Compose,
#    removes containers, networks and volumes (samba-shares, samba-config),
#    and removes the local web image (sambryx-web:latest),
#    leaving a clean environment for the next run.
# ==============================================================================

set -eo pipefail

# Locate the project directory containing docker-compose.yml
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/docker-compose.yml" ]; then
    PROJECT_ROOT="$SCRIPT_DIR"
elif [ -f "$SCRIPT_DIR/../docker-compose.yml" ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
elif [ -f "$SCRIPT_DIR/../../docker-compose.yml" ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
else
    PROJECT_ROOT="$(pwd)"
    if [ ! -f "$PROJECT_ROOT/docker-compose.yml" ]; then
        echo "Error: docker-compose.yml was not found in $SCRIPT_DIR or $PROJECT_ROOT" >&2
        exit 1
    fi
fi

cd "$PROJECT_ROOT"

# Detect Java 21 on the host unless JAVA_HOME is already set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    elif [ -d "/usr/lib/jvm/java-1.21.0-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-1.21.0-openjdk-amd64"
    fi
fi

# Select docker compose or docker-compose
if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    echo "Error: docker compose or docker-compose was not found in PATH!" >&2
    exit 1
fi

# Keep generated sandbox credentials outside tracked files.
export SANDBOX_ADMIN_PASSWORD_FILE="${SANDBOX_ADMIN_PASSWORD_FILE:-$PROJECT_ROOT/.local/sandbox/admin-password}"
mkdir -p "$(dirname "$SANDBOX_ADMIN_PASSWORD_FILE")"
if [ -L "$SANDBOX_ADMIN_PASSWORD_FILE" ] || { [ -e "$SANDBOX_ADMIN_PASSWORD_FILE" ] && [ ! -f "$SANDBOX_ADMIN_PASSWORD_FILE" ]; }; then
    echo "Sandbox administrator password must be a regular file." >&2
    exit 1
fi
if [ ! -e "$SANDBOX_ADMIN_PASSWORD_FILE" ]; then
    (umask 077; set -C; openssl rand -hex 32 > "$SANDBOX_ADMIN_PASSWORD_FILE")
fi
chmod 0600 "$SANDBOX_ADMIN_PASSWORD_FILE"

# Trust is provisioned only from the operator-controlled disposable Docker node.
export SSH_KNOWN_HOSTS_FILE="$PROJECT_ROOT/.local/sandbox/known_hosts"
mkdir -p "$(dirname "$SSH_KNOWN_HOSTS_FILE")"
if [ -L "$SSH_KNOWN_HOSTS_FILE" ] || { [ -e "$SSH_KNOWN_HOSTS_FILE" ] && [ ! -f "$SSH_KNOWN_HOSTS_FILE" ]; }; then
    echo "Sandbox known_hosts must be a regular file, not a symlink or directory." >&2
    exit 1
fi
if [ ! -e "$SSH_KNOWN_HOSTS_FILE" ]; then
    install -m 0644 /dev/null "$SSH_KNOWN_HOSTS_FILE"
fi
seed_sandbox_host_keys() {
    $COMPOSE_CMD exec -T samba-node sh -c 'for key in /etc/ssh/ssh_host_*_key.pub; do if [ -f "$key" ]; then printf "samba-node "; cat "$key"; fi; done' > "$SSH_KNOWN_HOSTS_FILE"
    test -s "$SSH_KNOWN_HOSTS_FILE"
    chmod 0644 "$SSH_KNOWN_HOSTS_FILE"
}

check_node_save_permissions() {
    if ! $COMPOSE_CMD exec -T --user admin samba-node sh -c 'for tool in mktemp testparm ln; do sudo -n "$tool" --version >/dev/null 2>&1 || exit 1; done'; then
        echo "The running node has an incompatible sudo policy. Preserve its data and follow docs/server-setup.md before updating it." >&2
        return 1
    fi
}

# Check Docker daemon availability
if ! docker info >/dev/null 2>&1; then
    echo "Error: the Docker daemon is stopped or inaccessible to the current user." >&2
    echo "Make sure Docker is running (systemctl start docker / Docker Desktop)." >&2
    exit 1
fi

# ==============================================================================
# Select deployment mode
# ==============================================================================
echo "=========================================================="
echo "         Sambryx - Docker Sandbox"
echo "=========================================================="
echo ""
echo "Select deployment mode:"
echo ""
echo "  1) Full run (clean environment with tests)"
echo "     Full build with Maven tests."
echo "     Recommended for final verification."
echo ""
echo "  2) Quick start (clean environment without tests)"
echo "     Build without tests (-DskipTests)."
echo "     Faster for local development."
echo ""

while true; do
    read -r -p "Your choice [1/2] (default: 2): " DEPLOY_MODE
    DEPLOY_MODE="${DEPLOY_MODE:-2}"
    case "$DEPLOY_MODE" in
        1)
            SKIP_TESTS="false"
            echo ""
            echo ">>> Mode: FULL RUN (with tests)"
            break
            ;;
        2)
            SKIP_TESTS="true"
            echo ""
            echo ">>> Mode: QUICK START (without tests)"
            break
            ;;
        *)
            echo "Invalid choice. Enter 1 or 2."
            ;;
    esac
done

echo "Project directory: $PROJECT_ROOT"
echo ""

# Build the frontend on the host
build_frontend() {
    echo ">>> Building frontend on the host..."
    local ok=0
    if [ -x "$PROJECT_ROOT/frontend/node/npm" ]; then
        (cd "$PROJECT_ROOT/frontend" && ./node/npm run build) && ok=1
    elif command -v npm >/dev/null 2>&1; then
        (cd "$PROJECT_ROOT/frontend" && npm run build) && ok=1
    else
        echo ">>> Running Maven to build the frontend..."
        ./mvnw generate-resources -DskipTests && ok=1
    fi
    if [ "$ok" -ne 1 ]; then
        echo "Error: failed to build frontend!" >&2
        return 1
    fi
    return 0
}

# Build the backend JAR on the host using the local Maven cache
build_backend() {
    echo ">>> Building backend JAR on the host using the local Maven cache..."
    local ok=0
    if [ "$SKIP_TESTS" = "true" ]; then
        ./mvnw package -DskipTests -Dmaven.test.skip=true -Dfrontend.skip=true && ok=1
    else
        ./mvnw package -Dfrontend.skip=true && ok=1
    fi
    if [ "$ok" -ne 1 ]; then
        echo "Error: failed to build backend JAR!" >&2
        return 1
    fi
    return 0
}

# Build the web Docker image
build_docker_image() {
    echo ">>> Pulling the published sandbox node..."
    $COMPOSE_CMD pull samba-node || return 1
    echo ">>> Building web Docker image (copying JAR into Alpine JRE)..."
    local jars=("$PROJECT_ROOT"/target/*.jar)
    if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
        echo "Error: target/ must contain exactly one application JAR." >&2
        return 1
    fi
    if ! python3 "$PROJECT_ROOT/scripts/ci/checks/verify-artifacts.py" "${jars[0]}"; then
        echo "Error: the JAR does not contain the built frontend." >&2
        return 1
    fi
    local jar_file="target/$(basename "${jars[0]}")"
    if ! docker build --target local --build-arg "JAR_FILE=$jar_file" -t sambryx-web:latest .; then
        echo "Error: failed to build web Docker image!" >&2
        return 1
    fi
    return 0
}

# Wait for samba-node readiness
wait_for_node() {
    echo ">>> Waiting for samba-node readiness (healthcheck)..."
    local MAX_WAIT=60
    local WAIT_COUNT=0
    while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
        NODE_STATUS=$(docker inspect --format='{{json .State.Health.Status}}' samba-node-sandbox 2>/dev/null || echo "\"starting\"")
        if [ "$NODE_STATUS" = "\"healthy\"" ]; then
            echo ""
            echo ">>> samba-node-sandbox is running and ready!"
            return 0
        fi
        sleep 2
        WAIT_COUNT=$((WAIT_COUNT + 2))
        printf "."
    done
    echo ""
    echo "Warning: samba-node-sandbox did not become healthy within $MAX_WAIT seconds."
    return 0
}

# Display connection information
print_banner() {
    echo ""
    echo "=========================================================="
    echo "  Docker Sandbox started successfully!"
    echo "=========================================================="
    if [ "$SKIP_TESTS" = "true" ]; then
        echo "  Mode:           Quick start (tests skipped)"
    else
        echo "  Mode:           Full run (tests executed)"
    fi
    echo "  - Web UI:       http://localhost:8080/ui                "
    echo "  ------------------------------------------------------  "
    echo "  Web UI login settings:"
    echo "  - Host (IP):    samba-node                              "
    echo "  - SSH Port:     22                                      "
    echo "  - Username:     admin                                   "
    echo "  - Password file: $SANDBOX_ADMIN_PASSWORD_FILE"
    echo "  ------------------------------------------------------  "
    echo "  Direct access from the host (Windows/Mac/Linux):"
    echo "  - SSH:          ssh admin@localhost -p 2222             "
    echo "  - Samba (SMB):  \\\\localhost:1445\\public              "
    echo "=========================================================="
    echo ""
}

# Display available commands
print_help() {
    echo "Available commands:"
    echo "  build, b, fast        - Build web image and pull node; update web only after node permission checks"
    echo "  build-full, bf        - Remove containers and volumes, then rebuild"
    echo "  restart, r            - Restart containers without rebuilding"
    echo "  logs, l               - Follow logs (Ctrl+C to return)"
    echo "  status, s, ps         - Show container status"
    echo "  help, h               - Show this help"
    echo "  stop, q, exit, Enter  - Stop and remove containers, volumes, networks and the web image"
}

# Rebuild without resetting data
rebuild_fast() {
    echo ""
    echo "=========================================================="
    echo "  [QUICK REBUILD] Updating the application..."
    echo "=========================================================="
    if ! build_frontend; then
        echo ">>> Frontend build failed. Rebuild canceled."
        return 1
    fi
    if ! build_backend; then
        echo ">>> Backend build failed. Rebuild canceled."
        return 1
    fi
    if ! build_docker_image; then
        echo ">>> Docker image build failed. Container was not updated."
        return 1
    fi

    echo ">>> Checking the existing node before updating the web container..."
    check_node_save_permissions || return 1
    echo ">>> Recreating web container; the running node and volumes are preserved..."
    $COMPOSE_CMD up -d --no-deps web

    echo ""
    echo ">>> [DONE] Web container updated and started!"
    echo ">>> Web UI available at: http://localhost:8080/ui"
    echo ""
}

# Full rebuild after removing sandbox resources
rebuild_full() {
    echo ""
    echo "=========================================================="
    echo "  [FULL REBUILD] Removing resources and rebuilding"
    echo "=========================================================="
    echo ">>> Stopping services and removing containers, networks, volumes and images..."
    $COMPOSE_CMD down -v --rmi local --remove-orphans >/dev/null 2>&1 || true
    docker volume rm -f samba_sandbox_shares samba_sandbox_config >/dev/null 2>&1 || true
    docker rmi -f sambryx-web:latest sambryx_web:latest >/dev/null 2>&1 || true
    rm -rf "$PROJECT_ROOT/frontend/dist"

    if ! build_frontend; then
        echo ">>> Frontend build failed. Full rebuild canceled."
        return 1
    fi
    if ! build_backend; then
        echo ">>> Backend build failed. Full rebuild canceled."
        return 1
    fi
    if ! build_docker_image; then
        echo ">>> Docker image build failed!"
        return 1
    fi

    echo ">>> Starting all services ($COMPOSE_CMD up -d)..."
    $COMPOSE_CMD up -d

    wait_for_node
    check_node_save_permissions
    seed_sandbox_host_keys
    print_banner
}

CLEANED_UP=0

cleanup() {
    # Prevent duplicate cleanup
    if [ "$CLEANED_UP" -eq 1 ]; then
        return
    fi
    CLEANED_UP=1

    echo ""
    echo "=========================================================="
    echo "  [CLEANUP] Stopping containers and removing resources..."
    echo "=========================================================="

    # Stop services and remove containers, networks, volumes and the local web image
    $COMPOSE_CMD down -v --rmi local --remove-orphans >/dev/null 2>&1 || true

    # Explicitly remove named Samba Sandbox volumes
    docker volume rm -f samba_sandbox_shares samba_sandbox_config >/dev/null 2>&1 || true

    # Remove the local web image by its known tags
    docker rmi -f sambryx-web:latest sambryx_web:latest >/dev/null 2>&1 || true

    echo "=========================================================="
    echo "  [DONE] Containers, volumes, networks and web image removed!"
    echo "  The next run will start with a clean environment."
    echo "=========================================================="
}

# Handle interruption signals to ensure cleanup
trap cleanup EXIT INT TERM

# 1. Clean up resources from any previous incomplete session
echo ">>> Checking and removing old sandbox containers, volumes and web image..."
$COMPOSE_CMD down -v --rmi local --remove-orphans >/dev/null 2>&1 || true
docker volume rm -f samba_sandbox_shares samba_sandbox_config >/dev/null 2>&1 || true
docker rmi -f sambryx-web:latest sambryx_web:latest >/dev/null 2>&1 || true

# An existing dist/ directory may not match the current sources.
build_frontend

# Build the backend JAR on the host
build_backend

# 2. Build the web Docker image
build_docker_image

# 3. Start services
echo ">>> Starting services ($COMPOSE_CMD up -d)..."
$COMPOSE_CMD up -d

# 4. Wait for container readiness
wait_for_node
check_node_save_permissions
seed_sandbox_host_keys
print_banner

# 5. Interactive command loop
print_help
echo ""

while true; do
    read -r -p "sandbox> " cmd || break
    case "$cmd" in
        stop|q|quit|exit|"")
            echo "Stop requested..."
            break
            ;;
        build|b|fast|rebuild)
            rebuild_fast || true
            ;;
        build-full|bf|clean-build|rebuild-full)
            rebuild_full || true
            ;;
        logs|l)
            echo "Following logs (press Ctrl+C to return to the menu)..."
            (
                # Handle Ctrl+C in the subshell without exiting the main script
                trap 'exit 0' INT
                $COMPOSE_CMD logs -f --tail=100
            )
            echo ""
            ;;
        status|s|ps)
            $COMPOSE_CMD ps
            ;;
        restart|r)
            echo "Restarting containers..."
            $COMPOSE_CMD restart
            ;;
        help|h)
            print_help
            ;;
        *)
            echo "Unknown command: '$cmd'. Enter 'help' for available commands."
            ;;
    esac
done
