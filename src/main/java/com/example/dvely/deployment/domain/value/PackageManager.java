package com.example.dvely.deployment.domain.value;

public enum PackageManager {
    NPM, YARN, PNPM, BUN;

    public String installCommand() {
        return switch (this) {
            case NPM  -> "npm ci";
            case YARN -> "yarn install --frozen-lockfile";
            case PNPM -> "pnpm install --frozen-lockfile";
            case BUN  -> "bun install";
        };
    }

    /** `{pm} run {script}` 형태의 스크립트 실행 명령어 */
    public String runScript(String script) {
        return switch (this) {
            case NPM  -> "npm run " + script;
            case YARN -> "yarn " + script;
            case PNPM -> "pnpm run " + script;
            case BUN  -> "bun run " + script;
        };
    }

    /** node_modules/.bin 의 바이너리를 실행하는 명령어 (npx/bunx/pnpm exec) */
    public String execBin(String bin) {
        return switch (this) {
            case NPM, YARN -> "npx " + bin;
            case PNPM      -> "pnpm exec " + bin;
            case BUN       -> "bunx " + bin;
        };
    }
}
