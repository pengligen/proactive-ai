# Backend Migration to AWS App Runner

## Local first
1. Start backend locally:
   ```bash
   cd backend/orchestrator
   ./scripts/run_local.sh
   ```
2. Validate:
   ```bash
   curl -sS http://localhost:8080/health
   ```

## Option A (recommended): Deploy container image
1. Build image:
   ```bash
   cd backend/orchestrator
   docker build -t proactiveai-orchestrator:latest .
   ```
2. Push to ECR.
3. Create App Runner service from ECR image.
4. Configure:
   - Port `8080`
   - Health check path `/health`
   - Auto scaling min/max as needed

## Option B: Source deploy with `apprunner.yaml`
1. Connect repo to App Runner.
2. Set source directory to `backend/orchestrator`.
3. App Runner reads [`apprunner.yaml`](/Users/ligenpeng/Documents/work/ProactiveAI/backend/orchestrator/apprunner.yaml) for build/run.

## Post-deploy wiring
1. Copy App Runner service URL.
2. Update Android orchestrator base URL config.
3. Keep a dev/staging switch so emulator can still hit `http://10.0.2.2:8080`.
