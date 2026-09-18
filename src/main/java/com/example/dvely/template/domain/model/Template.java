package com.example.dvely.template.domain.model;

import java.util.List;

/**
 * 퍼블리싱 템플릿 한 종.
 *
 * 정본은 이 서버가 아니라 템플릿 저장소(qeploy-templates)가 발행하는 catalog.json 이다. 서버는
 * 그것을 읽어 나를 뿐 소스를 들지 않는다 — 템플릿 변경 주기와 서버 배포 주기를 묶지 않기 위해서다.
 *
 * @param demoUrl   고르기 전에 조작해보는 데모. FE 갤러리가 iframe 으로 띄운다
 * @param sourceUrl 씨앗 tarball. 첫 CODE 스텝에서 컨테이너가 받아 /workspace/app 에 푼다
 */
public record Template(
        String id,
        String name,
        String description,
        List<String> tags,
        String stack,
        String entry,
        List<ContentHint> contentHints,
        String demoUrl,
        String sourceUrl
) {

    /**
     * 템플릿이 스스로 선언하는 "여기가 바꿔도 되는 내용" 표시.
     *
     * 이게 없으면 코딩 에이전트가 무엇이 내용이고 무엇이 구조인지 추측해야 한다. 사용자가 원하는
     * 것은 "내용만 바꾸기"인데, 추측이 빗나가면 레이아웃까지 건드린다.
     */
    public record ContentHint(String key, String where, String desc) {
    }
}
