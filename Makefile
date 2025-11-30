# Hoardi Plugin Makefile

# Testserver plugin directory
TESTSERVER_PLUGINS = test/data/plugins

.PHONY: build deploy clean start stop restart logs help publish publish-changelog

# Default target
all: build

# Build the plugin using Docker Maven
build:
	@echo "Building Hoardi plugin..."
	@docker run --rm -v "$(shell pwd)":/app -w /app maven:3.9-eclipse-temurin-21 mvn clean package -q
	@echo "Build complete: target/Hoardi-1.0.0.jar"

# Build and copy to testserver
deploy: build
	@echo "Deploying to testserver..."
	@mkdir -p $(TESTSERVER_PLUGINS)
	@cp target/Hoardi-1.0.0.jar $(TESTSERVER_PLUGINS)/
	@echo "Deployed to $(TESTSERVER_PLUGINS)/Hoardi-1.0.0.jar"

# Start testserver
start:
	@echo "Starting testserver..."
	@cd test && docker compose up -d
	@echo ""
	@echo "========================================="
	@echo "  Server starting at localhost:25566"
	@echo "========================================="
	@echo ""
	@echo "Use 'make logs' to view server output"

# Stop testserver
stop:
	@echo "Stopping testserver..."
	@cd test && docker compose down

# Restart testserver (deploy + restart container)
restart: deploy
	@echo "Restarting testserver..."
	@cd test && docker compose restart minecraft
	@echo "Server restarting... use 'make logs' to view output"

# View server logs
logs:
	@cd test && docker compose logs -f minecraft

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	@docker run --rm -v "$(shell pwd)":/app -w /app maven:3.9-eclipse-temurin-21 mvn clean -q
	@echo "Clean complete"

# Publish to Modrinth
publish: build
	@echo "Publishing to Modrinth..."
	@./scripts/modrinth-publish.sh

# Publish with changelog
# Usage: make publish-changelog CHANGELOG="Fixed bug X"
publish-changelog: build
	@echo "Publishing to Modrinth with changelog..."
	@./scripts/modrinth-publish.sh "$(shell grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')" "$(CHANGELOG)"

# Show help
help:
	@echo "Hoardi Plugin - Available targets:"
	@echo ""
	@echo "  Build:"
	@echo "    make build    - Build the plugin JAR"
	@echo "    make deploy   - Build and copy to testserver"
	@echo "    make clean    - Remove build artifacts"
	@echo ""
	@echo "  Testserver:"
	@echo "    make start    - Start the testserver"
	@echo "    make stop     - Stop the testserver"
	@echo "    make restart  - Deploy and restart server"
	@echo "    make logs     - View server logs (Ctrl+C to exit)"
	@echo ""
	@echo "  Publishing:"
	@echo "    make publish           - Build and publish to Modrinth"
	@echo "    make publish-changelog - Publish with custom changelog"
	@echo "                             CHANGELOG=\"Your message here\""
