package com.example.dvely.deployment.infrastructure.workflow;

/**
 * 프로젝트 templateType에 맞는 GitHub Actions 배포 워크플로우를 생성한다.
 * 워크플로우는 sourceRepository에 생성되며, 빌드 후 결과물을 gh-pages 브랜치에 push한다.
 */
public class DeployWorkflowTemplate {

    private static final String WORKFLOW_FILE = "dvely-deploy.yml";

    public static String fileName() {
        return WORKFLOW_FILE;
    }

    /**
     * templateType 기반으로 워크플로우 YAML 문자열을 생성한다.
     * @param templateType 프로젝트 템플릿 종류 (react, vue, nextjs 등)
     * @param publishDir   빌드 결과물 디렉토리 (null 이면 templateType으로 추론)
     */
    public static String generate(String templateType, String publishDir) {
        String buildCommand = resolveBuildCommand(templateType);
        String outDir = publishDir != null ? publishDir : resolvePublishDir(templateType);

        return """
                name: Dvely Deploy to GitHub Pages

                on:
                  workflow_dispatch:

                permissions:
                  contents: write
                  pages: write

                jobs:
                  deploy:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Checkout
                        uses: actions/checkout@v4

                      - name: Setup Node.js
                        uses: actions/setup-node@v4
                        with:
                          node-version: '20'

                      - name: Resolve base path
                        id: base
                        run: |
                          REPO="${{ github.event.repository.name }}"
                          OWNER="${{ github.repository_owner }}"
                          if [ "$REPO" = "${OWNER}.github.io" ]; then
                            echo "path=/" >> $GITHUB_OUTPUT
                          else
                            echo "path=/${REPO}/" >> $GITHUB_OUTPUT
                          fi

                      - name: Install dependencies
                        run: npm install

                      - name: Build
                        run: %s
                        env:
                          PUBLIC_URL: ${{ steps.base.outputs.path }}
                          VITE_BASE_URL: ${{ steps.base.outputs.path }}
                          BASE_PATH: ${{ steps.base.outputs.path }}

                      - name: Deploy to gh-pages
                        uses: peaceiris/actions-gh-pages@v4
                        with:
                          github_token: ${{ secrets.GITHUB_TOKEN }}
                          publish_dir: %s
                """.formatted(buildCommand, outDir);
    }

    private static String resolveBuildCommand(String templateType) {
        if (templateType == null) return resolveViteBuildCommand();
        return switch (templateType.toLowerCase()) {
            case "cra", "create-react-app" -> "npm run build";
            case "nextjs", "next"           -> "npm run build && npm run export";
            default                         -> resolveViteBuildCommand();
        };
    }

    private static String resolveViteBuildCommand() {
        return "npx vite build --base=${{ steps.base.outputs.path }}";
    }

    private static String resolvePublishDir(String templateType) {
        if (templateType == null) return "./dist";
        return switch (templateType.toLowerCase()) {
            case "cra", "create-react-app" -> "./build";
            case "nextjs", "next"           -> "./out";
            default                          -> "./dist";
        };
    }
}
