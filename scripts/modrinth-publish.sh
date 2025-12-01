#!/bin/bash
# Modrinth Publishing Script for Hoardi
# Usage: ./scripts/modrinth-publish.sh [version] [changelog]
#
# First publish: Creates a new project on Modrinth
# Subsequent: Uploads a new version to existing project

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Load environment variables
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

# Configuration
API_BASE="https://api.modrinth.com/v2"
USER_AGENT="Hoardi-Publisher/1.0 (github.com/mneuhaus/hoarder)"

# Project metadata
PROJECT_SLUG="hoardi"
PROJECT_NAME="Hoardi"
PROJECT_SUMMARY="Intelligent auto-sorting chest network with shelf item displays"
PROJECT_DESCRIPTION="# Hoardi

**Smart chest organization for the discerning hoarder.**

Hoardi creates intelligent chest networks that automatically sort items by configurable categories. Place shelves next to chests to display their contents at a glance.

## Features

- **Auto-sorting network**: Connect chests to a root chest and items get sorted automatically
- **Shelf displays**: Shelves show a preview of chest contents (requires 1.21.10+ shelf blocks)
- **Configurable categories**: Define your own item hierarchies (wood, ores, tools, etc.)
- **Smart splitting**: Categories auto-split into sub-categories when chests fill up
- **Spatial ordering**: Chests are ordered by position (row-based or spiral patterns)

## Commands

- \`/hoardi setroot\` - Set the root chest for your network
- \`/hoardi sort\` - Trigger a full network reorganization
- \`/hoardi info\` - Show network statistics
- \`/hoardi reload\` - Reload configuration

## Permissions

- \`hoardi.admin\` - Manage the plugin (default: op)
- \`hoardi.use\` - Create shelves and use the network (default: true)

## Requirements

- Paper 1.21.10+ (requires shelf blocks)
- Java 21+"

# Colors/formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check requirements
check_requirements() {
    if [ -z "$MODRINTH_TOKEN" ]; then
        log_error "MODRINTH_TOKEN not set. Add it to .env file."
        exit 1
    fi

    if ! command -v curl &> /dev/null; then
        log_error "curl is required but not installed."
        exit 1
    fi

    if ! command -v jq &> /dev/null; then
        log_error "jq is required but not installed."
        exit 1
    fi
}

# Get version from pom.xml
get_pom_version() {
    grep -m1 '<version>' "$PROJECT_DIR/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/'
}

# Check if project exists - returns "exists:PROJECT_ID" or "not_found:"
check_project_exists() {
    local response http_code body

    response=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "User-Agent: $USER_AGENT" \
        "$API_BASE/project/$PROJECT_SLUG")

    http_code=$(echo "$response" | grep "HTTP_CODE:" | cut -d: -f2)
    body=$(echo "$response" | grep -v "HTTP_CODE:")

    if [ "$http_code" = "200" ]; then
        local project_id=$(echo "$body" | jq -r '.id')
        echo "exists:$project_id"
    else
        echo "not_found:"
    fi
}

# Create new project
create_project() {
    local jar_path="$1"
    local version="$2"
    local changelog="$3"

    log_info "Creating new project on Modrinth..."

    # Build the multipart form data
    local project_data=$(cat <<EOF
{
    "slug": "$PROJECT_SLUG",
    "title": "$PROJECT_NAME",
    "description": $(echo "$PROJECT_DESCRIPTION" | jq -Rs .),
    "body": $(echo "$PROJECT_DESCRIPTION" | jq -Rs .),
    "categories": ["storage", "utility"],
    "client_side": "unsupported",
    "server_side": "required",
    "project_type": "mod",
    "initial_versions": [{
        "name": "$PROJECT_NAME v$version",
        "version_number": "$version",
        "changelog": $(echo "$changelog" | jq -Rs .),
        "dependencies": [],
        "game_versions": ["1.21.10"],
        "version_type": "release",
        "loaders": ["paper", "purpur", "spigot", "bukkit"],
        "featured": true,
        "file_parts": ["jar"]
    }],
    "license_id": "MIT",
    "is_draft": false
}
EOF
)

    local response http_code body
    response=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
        -X POST "$API_BASE/project" \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "User-Agent: $USER_AGENT" \
        -F "data=$project_data" \
        -F "jar=@$jar_path;type=application/java-archive")

    http_code=$(echo "$response" | grep "HTTP_CODE:" | cut -d: -f2)
    body=$(echo "$response" | grep -v "HTTP_CODE:")

    if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
        local project_id=$(echo "$body" | jq -r '.id')
        log_info "Project created successfully! ID: $project_id"

        # Save project ID to .env
        if grep -q "^MODRINTH_PROJECT_ID=" "$PROJECT_DIR/.env"; then
            sed -i '' "s/^MODRINTH_PROJECT_ID=.*/MODRINTH_PROJECT_ID=$project_id/" "$PROJECT_DIR/.env"
        else
            echo "MODRINTH_PROJECT_ID=$project_id" >> "$PROJECT_DIR/.env"
        fi

        echo "https://modrinth.com/mod/$PROJECT_SLUG"
    else
        log_error "Failed to create project (HTTP $http_code)"
        echo "$body" | jq . 2>/dev/null || echo "$body"
        exit 1
    fi
}

# Upload new version to existing project
upload_version() {
    local project_id="$1"
    local jar_path="$2"
    local version="$3"
    local changelog="$4"

    log_info "Uploading version $version to Modrinth..."

    local version_data=$(cat <<EOF
{
    "name": "$PROJECT_NAME v$version",
    "version_number": "$version",
    "changelog": $(echo "$changelog" | jq -Rs .),
    "dependencies": [],
    "game_versions": ["1.21.10"],
    "version_type": "release",
    "loaders": ["paper", "purpur", "spigot", "bukkit"],
    "featured": true,
    "project_id": "$project_id",
    "file_parts": ["jar"]
}
EOF
)

    local response http_code body
    response=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
        -X POST "$API_BASE/version" \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "User-Agent: $USER_AGENT" \
        -F "data=$version_data" \
        -F "jar=@$jar_path;type=application/java-archive")

    http_code=$(echo "$response" | grep "HTTP_CODE:" | cut -d: -f2)
    body=$(echo "$response" | grep -v "HTTP_CODE:")

    if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
        local version_id=$(echo "$body" | jq -r '.id')
        log_info "Version uploaded successfully! ID: $version_id"
        echo "https://modrinth.com/mod/$PROJECT_SLUG/version/$version"
    else
        log_error "Failed to upload version (HTTP $http_code)"
        echo "$body" | jq . 2>/dev/null || echo "$body"
        exit 1
    fi
}

# Main
main() {
    local version="${1:-$(get_pom_version)}"
    local changelog="${2:-Version $version release}"
    local jar_path="$PROJECT_DIR/target/Hoardi-$version.jar"

    log_info "Modrinth Publisher for Hoardi"
    echo "  Version: $version"
    echo "  JAR: $jar_path"
    echo ""

    check_requirements

    # Check if JAR exists
    if [ ! -f "$jar_path" ]; then
        log_warn "JAR not found. Building..."
        (cd "$PROJECT_DIR" && make build)
    fi

    if [ ! -f "$jar_path" ]; then
        log_error "JAR file not found: $jar_path"
        exit 1
    fi

    # Check if project exists
    log_info "Checking if project exists on Modrinth..."
    local result=$(check_project_exists)
    local status="${result%%:*}"
    local project_id="${result##*:}"

    if [ "$status" = "exists" ]; then
        log_info "Project found (ID: $project_id)"
        upload_version "$project_id" "$jar_path" "$version" "$changelog"
    else
        log_info "Project not found. Creating new project..."
        create_project "$jar_path" "$version" "$changelog"
    fi
}

main "$@"
