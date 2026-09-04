.PHONY: up down logs test smoke concurrency acceptance recovery reset
up:
	docker compose up --build -d
down:
	docker compose down
logs:
	docker compose logs -f --tail=200
test:
	docker run --rm -v "$(CURDIR):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test
	docker run --rm -v "$(CURDIR)/frontend:/app" -w /app node:22-alpine sh -c "npm ci --legacy-peer-deps && npm test && npm run build"
smoke:
	./scripts/smoke-test.sh
concurrency:
	node ./scripts/idempotency-test.mjs
acceptance:
	node ./scripts/acceptance-test.mjs
recovery:
	node ./scripts/recovery-test.mjs
reset:
	docker compose down -v
