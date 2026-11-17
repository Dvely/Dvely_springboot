package com.example.dvely.deviceshare.application.service;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deviceshare.application.session.ClaimedPin;
import com.example.dvely.deviceshare.application.session.PinClaim;
import com.example.dvely.deviceshare.application.session.PinResultSanitizer;
import com.example.dvely.deviceshare.application.session.ShareFrames;
import com.example.dvely.deviceshare.application.session.ShareSession;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.domain.model.FeedbackImagePresence;
import com.example.dvely.deviceshare.domain.model.FeedbackImages;
import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.example.dvely.deviceshare.domain.repository.DeviceShareLinkRepository;
import com.example.dvely.deviceshare.domain.repository.ShareFeedbackRepository;
import com.example.dvely.deviceshare.domain.service.ShareTokens;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackNoteKind;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 짚기 피드백(#681, 설계 §14). 보는 사람의 보내기·확인·다시 열기, 기기의 받기·무시, 주인 API, 보존.
 *
 * <p>손님 글은 주인이 받아야({@link FeedbackStatus#ACCEPTED}) 에이전트로 나간다. 개인 액세스 토큰(에이전트)으로 부르면
 * 받기 전 글은 없는 것처럼 404 다 — MCP 에서만 거르면 에이전트가 PAT 로 API 를 직접 부를 수 있어서다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareFeedbackService {

    static final int MAX_BODY = 1000;
    static final int MAX_FIXED_NOTE = 300;
    static final int MAX_PER_HOUR = 30;
    static final int SHARE_LIST_LIMIT = 100;
    static final int USER_LIST_MAX = 50;
    static final int RENOTIFY_LIMIT = 10;
    static final int WATCH_LIMIT = 30;
    static final int NOTICE_PREVIEW = 200;
    static final int RETENTION_DAYS = 14;
    /** §15.5: 한 피드백에 짚은 곳 다섯 개까지 */
    static final int MAX_MARKS = 5;
    /** 뷰어에 되돌려 줄 짚기 id 의 모양. 세션이 만드는 id 는 {@code p} + 숫자다 */
    private static final java.util.regex.Pattern PROBE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9]{1,16}");
    private static final Set<String> VIEWER_TYPES = Set.of("pin-submit", "feedback-watch", "feedback-confirm",
            "feedback-reopen");
    /** 주인이 웹에서 고를 수 있는 상태. 고침은 {@link #markFixed} 로만(메모를 남긴다). */
    private static final Set<FeedbackStatus> OWNER_SETTABLE = EnumSet.of(FeedbackStatus.ACCEPTED,
            FeedbackStatus.DISMISSED, FeedbackStatus.CONFIRMED);

    private final ShareFeedbackRepository repository;
    private final DeviceShareLinkRepository links;
    private final ShareSessionRegistry sessions;
    private final ObjectMapper json;

    public static boolean handlesViewerMessage(String type) {
        return VIEWER_TYPES.contains(type);
    }

    // ================================================================== 보는 사람(공유 오리진)

    public void onViewerMessage(DeviceShareLink link, String viewerId, JsonNode message) {
        try {
            switch (message.path("type").asText("")) {
                case "pin-submit" -> submit(link, viewerId, message);
                case "feedback-watch" -> watch(link, viewerId, message.path("items"));
                case "feedback-confirm" -> confirm(link, viewerId, message);
                case "feedback-reopen" -> reopen(link, viewerId, message);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("[DeviceShare] 피드백 처리 실패 share={} viewer={}: {}", link.getId(), viewerId, e.getMessage());
            viewerError(link, viewerId, "피드백을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private void submit(DeviceShareLink link, String viewerId, JsonNode message) {
        String body = cleanBody(message.path("comment").asText(""));
        if (body.isEmpty() || length(body) > MAX_BODY) {
            viewerError(link, viewerId, "글은 1~" + MAX_BODY + "자로 써 주세요.");
            return;
        }
        LocalDateTime now = now();
        if (repository.countByShareSince(link.getId(), now.minusHours(1)) >= MAX_PER_HOUR) {
            viewerError(link, viewerId, "이 공유에는 한 시간에 피드백을 " + MAX_PER_HOUR + "개까지 남길 수 있습니다.");
            return;
        }
        // §15.9: 여러 곳은 marks, 한 곳은 옛 모양 {probeId, scope}(배포 중 열려 있던 탭)
        JsonNode marks = message.path("marks");
        boolean multi = marks.isArray();
        List<String> probeIds = new ArrayList<>();
        List<JsonNode> scopeChoices = new ArrayList<>();
        if (multi) {
            if (marks.isEmpty() || marks.size() > MAX_MARKS) {
                viewerError(link, viewerId, "짚은 곳은 1~" + MAX_MARKS + "개여야 합니다.");
                return;
            }
            for (JsonNode mark : marks) {
                probeIds.add(mark.path("probeId").asText(""));
                scopeChoices.add(mark.path("scope"));
            }
        } else {
            probeIds.add(message.path("probeId").asText(""));
            scopeChoices.add(message.path("scope"));
        }
        PinClaim claim = sessions.claimPins(link.getId(), viewerId, probeIds);
        if (claim.claimed().isEmpty()) {
            if (multi) {
                // 뷰어가 시간이 지난 곳만 다시 짚는다
                ObjectNode missing = json.createObjectNode().put("type", "pin-missing");
                ArrayNode ids = missing.putArray("probeIds");
                claim.missing().stream().filter(id -> PROBE_ID.matcher(id).matches()).forEach(ids::add);
                sessions.sendToViewer(link.getId(), viewerId, missing.toString());
            } else {
                viewerError(link, viewerId, "짚은 결과가 없습니다. 5분이 지났거나 이미 보냈다면 다시 짚어 주세요.");
            }
            return;
        }
        ClaimedPin pin = claim.claimed().get();
        ObjectNode description = null;
        byte[] crop = null;
        Map<Integer, byte[]> markCrops = new LinkedHashMap<>();
        List<AnnotatedScreen.Mark> drawn = new ArrayList<>();
        for (int i = 0; i < pin.marks().size(); i++) {
            ClaimedPin.Mark mark = pin.marks().get(i);
            ObjectNode place = mark.resolution().description().deepCopy();
            byte[] placeCrop = mark.crop();
            JsonNode scopes = place.path("scopes");
            Integer chosen = null;
            JsonNode outline = place.path("element").path("bounds");
            if (scopes.isArray() && !scopes.isEmpty()) {
                // §15.10: 사람이 고른 범위를 저장하고, 잘라 낸 그림도 그 범위로 만든다
                int fallback = place.path("defaultScope").asInt(0);
                chosen = scopeChoices.get(i).isInt() ? scopeChoices.get(i).asInt() : fallback;
                if (chosen < 0 || chosen >= scopes.size()) {
                    chosen = fallback;
                }
                placeCrop = selectedCrop(scopes.get(chosen), mark.crop(), mark.screen());
                outline = scopes.get(chosen).path("bounds");
            }
            drawn.add(new AnnotatedScreen.Mark(i + 1, outline.isObject() ? outline : null,
                    place.path("point").isObject() ? place.path("point") : null));
            if (i == 0) {
                description = place;
                if (chosen != null) {
                    description.put("v", 2).put("scope", chosen);
                }
                crop = placeCrop;
                continue;
            }
            ObjectNode entry = (description.has("marks") ? (ArrayNode) description.get("marks") : description.putArray("marks"))
                    .addObject().put("n", i + 1);
            entry.set("probe", place);
            if (chosen != null) {
                entry.put("scope", chosen);
            }
            entry.put("peers", false);
            if (placeCrop != null) {
                markCrops.put(i + 1, placeCrop);
            }
        }
        byte[] annotated = null;
        if (pin.marks().size() > 1) {
            description.put("v", 2);
            annotated = AnnotatedScreen.draw(pin.screen(), drawn);
        }
        String key = ShareTokens.newViewToken();
        ShareFeedback saved = repository.save(ShareFeedback.newFeedback(link.getId(), pin.owner(), pin.viewerName(),
                        ShareTokens.sha256Hex(key), link.getKind(), pin.resolution().screenLabel(),
                        description.toString(), body),
                new FeedbackImages(crop, pin.screen(), annotated, markCrops));
        log.info("[DeviceShare] 피드백 저장 share={} feedback={} owner={} resolved={} marks={}", link.getId(),
                saved.getId(), pin.owner(), pin.resolution().resolved(), pin.marks().size());
        sessions.watch(link.getId(), viewerId, saved.getId());
        sessions.sendToViewer(link.getId(), viewerId,
                stateMessage(saved.getId(), saved.getStatus(), null).put("key", key).toString());
        notifyDevice(saved);
    }

    private void watch(DeviceShareLink link, String viewerId, JsonNode items) {
        if (!items.isArray()) {
            return;
        }
        int seen = 0;
        for (JsonNode item : items) {
            if (++seen > WATCH_LIMIT) {
                break;
            }
            authorized(link, item.path("id").asLong(0), item.path("key").asText(null)).ifPresent(feedback -> {
                sessions.watch(link.getId(), viewerId, feedback.getId());
                sessions.sendToViewer(link.getId(), viewerId,
                        stateMessage(feedback.getId(), feedback.getStatus(), fixedNote(feedback)).toString());
            });
        }
    }

    private void confirm(DeviceShareLink link, String viewerId, JsonNode message) {
        Optional<ShareFeedback> found = authorized(link, message.path("id").asLong(0), message.path("key").asText(null));
        if (found.isEmpty()) {
            viewerError(link, viewerId, "이 피드백을 확인할 수 없습니다.");
            return;
        }
        boolean owner = sessions.viewer(link.getId(), viewerId).map(ShareSession.ViewerIdentity::owner).orElse(false);
        if (!move(found.get(), FeedbackStatus.CONFIRMED)) {
            viewerError(link, viewerId, "고쳤다고 표시된 피드백만 확인할 수 있습니다.");
            return;
        }
        repository.addNote(new ShareFeedbackNote(null, found.get().getId(),
                owner ? FeedbackAuthorRole.OWNER : FeedbackAuthorRole.GUEST, FeedbackNoteKind.CONFIRM, "", null));
        broadcastState(found.get().getShareId(), found.get().getId(), FeedbackStatus.CONFIRMED, null);
    }

    private void reopen(DeviceShareLink link, String viewerId, JsonNode message) {
        Optional<ShareFeedback> found = authorized(link, message.path("id").asLong(0), message.path("key").asText(null));
        if (found.isEmpty()) {
            viewerError(link, viewerId, "이 피드백을 다시 열 수 없습니다.");
            return;
        }
        String body = cleanBody(message.path("comment").asText(""));
        if (body.isEmpty() || length(body) > MAX_BODY) {
            viewerError(link, viewerId, "다시 여는 까닭을 1~" + MAX_BODY + "자로 써 주세요.");
            return;
        }
        boolean owner = sessions.viewer(link.getId(), viewerId).map(ShareSession.ViewerIdentity::owner).orElse(false);
        // 손님 글이 새로 붙으면 주인이 다시 받아야 에이전트로 간다
        FeedbackStatus next = owner ? FeedbackStatus.ACCEPTED : FeedbackStatus.NEW;
        if (!move(found.get(), next)) {
            viewerError(link, viewerId, "고쳤다고 표시된 피드백만 다시 열 수 있습니다.");
            return;
        }
        repository.addNote(new ShareFeedbackNote(null, found.get().getId(),
                owner ? FeedbackAuthorRole.OWNER : FeedbackAuthorRole.GUEST, FeedbackNoteKind.REOPEN, body, null));
        broadcastState(found.get().getShareId(), found.get().getId(), next, null);
        repository.findById(found.get().getId()).ifPresent(this::notifyDevice);
    }

    /** 이 링크의 피드백이고 확인 키가 맞으면. */
    private Optional<ShareFeedback> authorized(DeviceShareLink link, long feedbackId, String key) {
        if (feedbackId <= 0 || key == null) {
            return Optional.empty();
        }
        return repository.findById(feedbackId)
                .filter(feedback -> feedback.getShareId().equals(link.getId()))
                .filter(feedback -> ShareTokens.matches(key, feedback.getAuthorKeyHash()));
    }

    // ================================================================== 기기(터널)

    /** 주인의 받기·무시({@code FEEDBACK_DECISION}). 이 링크의 받기 전 피드백만 바꾼다. */
    public void onDeviceDecision(DeviceShareLink link, ShareFrames.Frame frame) {
        JsonNode node;
        try {
            node = json.readTree(frame.payloadText());
        } catch (Exception e) {
            return;
        }
        long feedbackId = node.path("feedbackId").asLong(0);
        FeedbackStatus next = node.path("accepted").asBoolean(false) ? FeedbackStatus.ACCEPTED : FeedbackStatus.DISMISSED;
        repository.findById(feedbackId)
                .filter(feedback -> feedback.getShareId().equals(link.getId()))
                .filter(feedback -> feedback.getStatus() == FeedbackStatus.NEW)
                .ifPresent(feedback -> {
                    if (move(feedback, next)) {
                        log.info("[DeviceShare] 피드백 {} share={} feedback={}", next == FeedbackStatus.ACCEPTED ? "받음" : "무시",
                                link.getId(), feedbackId);
                        broadcastState(feedback.getShareId(), feedbackId, next, null);
                    }
                });
    }

    /** 기기가 다시 연결되면 그동안 들어온 받기 전 피드백을 다시 알린다(오래된 것부터, 최근 10개). */
    public void renotifyPending(DeviceShareLink link) {
        List<ShareFeedback> pending = repository.findByShareAndStatus(link.getId(), FeedbackStatus.NEW, RENOTIFY_LIMIT);
        for (int i = pending.size() - 1; i >= 0; i--) {
            notifyDevice(pending.get(i));
        }
    }

    /**
     * 고른 칸의 그림. 요소 칸이면 기기가 잘라 보낸 것, 화면 칸이면 화면 그대로, 그 밖(덩어리·컴포넌트·영역)이면 서버가 화면에서
     * 자른 것이다. 화면을 읽지 못하면 기기가 잘라 보낸 것으로 둔다.
     */
    static byte[] selectedCrop(JsonNode scope, byte[] deviceCrop, byte[] screen) {
        return switch (scope.path("kind").asText("")) {
            // 끈 영역은 기기가 그 사각형으로 잘라 보낸다(§15.4)
            case "element", "region" -> deviceCrop != null ? deviceCrop : ScopeCrop.cut(screen, scope.path("bounds"));
            case "screen" -> screen != null ? screen : deviceCrop;
            default -> {
                byte[] cut = ScopeCrop.cut(screen, scope.path("bounds"));
                yield cut != null ? cut : deviceCrop;
            }
        };
    }

    private void notifyDevice(ShareFeedback feedback) {
        JsonNode element = parse(feedback.getElementJson());
        ObjectNode notice = json.createObjectNode().put("feedbackId", feedback.getId());
        notice.putObject("author").put("role", feedback.getAuthorRole().name()).put("name", feedback.getAuthorName());
        // 주인이 받을지 고르는 근거라, 손님이 고른 범위 그대로 보인다(§15.10)
        notice.put("label", PinResultSanitizer.selectedLabel(element));
        notice.put("comment", preview(latestBody(feedback)));
        notice.put("state", feedback.getStatus().name());
        sessions.sendToPinDevice(feedback.getShareId(), ShareFrames.FEEDBACK_NOTICE, notice.toString());
    }

    /** 다시 열었으면 그 글이 지금 판단할 글이다. */
    private String latestBody(ShareFeedback feedback) {
        if (feedback.getStatus() != FeedbackStatus.NEW) {
            return feedback.getBody();
        }
        List<ShareFeedbackNote> notes = repository.findNotes(feedback.getId());
        for (int i = notes.size() - 1; i >= 0; i--) {
            if (notes.get(i).kind() == FeedbackNoteKind.REOPEN) {
                return notes.get(i).body();
            }
        }
        return feedback.getBody();
    }

    // ================================================================== 주인 API

    public List<ShareFeedbackView> listForShare(Long userId, Long shareId, boolean agent) {
        DeviceShareLink link = links.findById(shareId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("공유를 찾을 수 없습니다."));
        return repository.findByShare(link.getId(), SHARE_LIST_LIMIT).stream()
                .filter(feedback -> !agent || feedback.getStatus().agentVisible())
                .map(this::summary)
                .toList();
    }

    public List<ShareFeedbackView> listForUser(Long userId, Collection<FeedbackStatus> statuses, int limit, boolean agent) {
        EnumSet<FeedbackStatus> wanted = statuses == null || statuses.isEmpty()
                ? EnumSet.of(FeedbackStatus.ACCEPTED) : EnumSet.copyOf(statuses);
        if (agent) {
            wanted.retainAll(FeedbackStatus.AGENT_VISIBLE);
        }
        int size = Math.max(1, Math.min(limit <= 0 ? 20 : limit, USER_LIST_MAX));
        return repository.findByUser(userId, wanted, size).stream().map(this::summary).toList();
    }

    public ShareFeedbackView get(Long userId, Long feedbackId, boolean agent) {
        ShareFeedback feedback = owned(userId, feedbackId, agent);
        ShareFeedbackView summary = summary(feedback);
        FeedbackImagePresence images = repository.imagePresence(feedback.getId());
        List<ShareFeedbackView.Mark> marks = new ArrayList<>();
        List<JsonNode> places = PinResultSanitizer.places(summary.element());
        for (int i = 0; i < places.size(); i++) {
            int n = i + 1;
            String label = PinResultSanitizer.placeLabel(places.get(i));
            marks.add(new ShareFeedbackView.Mark(n, label == null ? "짚은 자리" : label,
                    n == 1 ? images.crop() : images.marks().contains(n)));
        }
        return new ShareFeedbackView(feedback, summary.element(), summary.label(), summary.component(),
                repository.findNotes(feedback.getId()), images.crop(), images.screen(), images.annotated(), marks);
    }

    public String prompt(Long userId, Long feedbackId, boolean agent) {
        ShareFeedback feedback = owned(userId, feedbackId, agent);
        return FeedbackPromptRenderer.render(feedback, parse(feedback.getElementJson()),
                repository.findNotes(feedback.getId()));
    }

    /** 피드백 이미지가 무엇인지(§15.10) */
    public enum ImageKind { CROP, SCREEN, ANNOTATED }

    /**
     * @param mark 잘라 낸 것이면 짚은 곳 번호(1부터). ②부터는 그 곳에서 고른 범위다
     */
    public byte[] image(Long userId, Long feedbackId, ImageKind kind, int mark, boolean agent) {
        ShareFeedback feedback = owned(userId, feedbackId, agent);
        if (kind == ImageKind.CROP && mark > 1) {
            return repository.findMarkCrop(feedback.getId(), mark).filter(bytes -> bytes.length > 0)
                    .orElseThrow(() -> new NotFoundException("이미지가 없습니다."));
        }
        FeedbackImages images = repository.findImages(feedback.getId());
        byte[] bytes = switch (kind) {
            case CROP -> images.crop();
            case SCREEN -> images.screen();
            case ANNOTATED -> images.annotated();
        };
        if (bytes == null || bytes.length == 0) {
            throw new NotFoundException("이미지가 없습니다.");
        }
        return bytes;
    }

    /** 주인(브라우저)의 받기·무시·확인. */
    public ShareFeedbackView changeStatus(Long userId, Long feedbackId, FeedbackStatus next) {
        ShareFeedback feedback = owned(userId, feedbackId, false);
        if (next == null || !OWNER_SETTABLE.contains(next)) {
            throw new IllegalArgumentException("status 는 ACCEPTED · DISMISSED · CONFIRMED 중 하나여야 합니다.");
        }
        if (!move(feedback, next)) {
            throw new ShareFeedbackTransitionException(feedback.getStatus(), next);
        }
        if (next == FeedbackStatus.CONFIRMED) {
            repository.addNote(new ShareFeedbackNote(null, feedbackId, FeedbackAuthorRole.OWNER, FeedbackNoteKind.CONFIRM, "", null));
        }
        broadcastState(feedback.getShareId(), feedbackId, next, null);
        return get(userId, feedbackId, false);
    }

    /** 고쳤다고 표시한다. 메모는 짚은 사람에게 보인다. @param agent 개인 액세스 토큰(MCP·CLI)으로 불렀는지 */
    public ShareFeedbackView markFixed(Long userId, Long feedbackId, String note, boolean agent) {
        ShareFeedback feedback = owned(userId, feedbackId, agent);
        String memo = note == null ? "" : oneLine(note);
        if (length(memo) > MAX_FIXED_NOTE) {
            throw new IllegalArgumentException("메모는 " + MAX_FIXED_NOTE + "자까지 쓸 수 있습니다.");
        }
        if (!move(feedback, FeedbackStatus.FIXED)) {
            throw new ShareFeedbackTransitionException(feedback.getStatus(), FeedbackStatus.FIXED);
        }
        repository.addNote(new ShareFeedbackNote(null, feedbackId,
                agent ? FeedbackAuthorRole.AGENT : FeedbackAuthorRole.OWNER, FeedbackNoteKind.FIXED, memo, null));
        log.info("[DeviceShare] 피드백 고침 share={} feedback={} by={}", feedback.getShareId(), feedbackId,
                agent ? "agent" : "owner");
        broadcastState(feedback.getShareId(), feedbackId, FeedbackStatus.FIXED, memo);
        return get(userId, feedbackId, agent);
    }

    /** 끝난 링크의 피드백은 14일 뒤 지운다. 이미지·노트는 CASCADE. */
    @Scheduled(cron = "0 23 4 * * *")
    public void purgeEnded() {
        try {
            int deleted = repository.deleteForLinksEndedBefore(now().minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("[DeviceShare] 끝난 공유의 피드백 {}개를 지움", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("[DeviceShare] 피드백 보존 정리 실패: {}", e.getMessage());
        }
    }

    // ================================================================== 도우미

    private ShareFeedback owned(Long userId, Long feedbackId, boolean agent) {
        return repository.findById(feedbackId)
                .filter(feedback -> !agent || feedback.getStatus().agentVisible())
                .filter(feedback -> links.findById(feedback.getShareId())
                        .map(link -> link.getUserId().equals(userId)).orElse(false))
                .orElseThrow(() -> new NotFoundException("피드백을 찾을 수 없습니다."));
    }

    private ShareFeedbackView summary(ShareFeedback feedback) {
        JsonNode element = parse(feedback.getElementJson());
        return new ShareFeedbackView(feedback, element, PinResultSanitizer.selectedLabel(element),
                element.path("components").path(0).path("name").asText(null), List.of(), false, false, false, List.of());
    }

    private boolean move(ShareFeedback feedback, FeedbackStatus next) {
        FeedbackStatus current = feedback.getStatus();
        if (!current.canMoveTo(next)) {
            return false;
        }
        return repository.moveStatus(feedback.getId(), List.of(current), next, now());
    }

    private void broadcastState(Long shareId, Long feedbackId, FeedbackStatus status, String note) {
        sessions.notifyWatchers(shareId, feedbackId, stateMessage(feedbackId, status, note).toString());
    }

    private ObjectNode stateMessage(Long feedbackId, FeedbackStatus status, String note) {
        ObjectNode message = json.createObjectNode().put("type", "feedback").put("id", feedbackId)
                .put("state", status.name());
        if (note != null && !note.isEmpty()) {
            message.put("note", note);
        }
        return message;
    }

    private String fixedNote(ShareFeedback feedback) {
        if (feedback.getStatus() != FeedbackStatus.FIXED) {
            return null;
        }
        List<ShareFeedbackNote> notes = repository.findNotes(feedback.getId());
        for (int i = notes.size() - 1; i >= 0; i--) {
            if (notes.get(i).kind() == FeedbackNoteKind.FIXED) {
                return notes.get(i).body();
            }
        }
        return null;
    }

    private void viewerError(DeviceShareLink link, String viewerId, String message) {
        sessions.sendToViewer(link.getId(), viewerId,
                json.createObjectNode().put("type", "error").put("message", message).toString());
    }

    private JsonNode parse(String text) {
        try {
            JsonNode node = json.readTree(text);
            return node == null ? json.createObjectNode() : node;
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** 줄바꿈·탭은 두고 나머지 제어 문자를 지운다. 앞뒤 공백을 걷는다. */
    static String cleanBody(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }

    private static String preview(String body) {
        String line = oneLine(body == null ? "" : body);
        return length(line) > NOTICE_PREVIEW ? line.substring(0, line.offsetByCodePoints(0, NOTICE_PREVIEW)) : line;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 한 줄로 만든다. 제어 문자는 공백이 되고, 겹친 공백은 하나로. */
    static String oneLine(String value) {
        return value.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
    }
}
